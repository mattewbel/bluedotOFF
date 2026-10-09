package app.bluedotoff.adb

import app.bluedotoff.core.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

interface AdbWire:AutoCloseable {
    fun write(bytes:ByteArray)
    fun read(length:Int):ByteArray
    fun startTls()
}
private class NativeWire(private val handle:Long):AdbWire {
    override fun write(bytes:ByteArray)=NativeAdb.write(handle,bytes)
    override fun read(length:Int)=NativeAdb.read(handle,length)
    override fun startTls()=NativeAdb.startTls(handle)
    override fun close()=NativeAdb.close(handle)
}
class HelperOperationRejectedException(val reason:String="HELPER_OPERATION_REJECTED"):app.bluedotoff.core.SafetyException(reason)
class LocalAdbStageException(val state:LocalAdbState,cause:Throwable?=null):IOException(state.name,cause)

/** One owner and TLS connection; bounded helper streams are opened sequentially. */
class LocalAdbConnection(private val wire:AdbWire):AutoCloseable {
    constructor(native:Long):this(NativeWire(native))
    private var remote=0
    private var streamId=0
    private val stdout=java.util.ArrayDeque<Byte>()
    private val shellBytes=java.util.ArrayDeque<Byte>()
    private var ended=false
    private var helperExitConfirmed=false
    private var closed=false
    private var authenticated=false
    private var poisoned=false
    private var streamClosed=true
    private var lastClosedRemote=0
    private var maxPayload=4096
    private fun send(p:AdbPacket)=wire.write(p.encode())
    private fun receive():AdbPacket {val h=wire.read(24);return AdbPacket.decode(h,wire.read(AdbPacket.length(h)))}
    private fun <T> protocol(block:()->T):T {
        check(!closed&&!poisoned){"ADB transport is no longer usable"}
        return try{block()}catch(e:HelperOperationRejectedException){throw e}catch(e:Exception){poisoned=true;throw e}
    }
    fun authenticate():Unit=protocol {
        check(!authenticated&&streamId==0)
        send(AdbPacket(AdbPacket.CNXN,0x01000001,AdbPacket.MAX_PAYLOAD,"host::features=shell_v2\u0000".toByteArray()))
        val tls=receive();check(tls.command==AdbPacket.STLS){"Modern wireless ADB TLS required"}
        send(AdbPacket(AdbPacket.STLS,0x01000000));wire.startTls()
        val ready=receive();check(ready.command==AdbPacket.CNXN&&ready.arg1>=4096){"Wireless ADB authentication failed"}
        maxPayload=minOf(ready.arg1,AdbPacket.MAX_PAYLOAD)
        authenticated=true
    }
    fun startHelper(apk:String):Unit=protocol {
        check(authenticated&&remote==0&&streamClosed&&streamId<Int.MAX_VALUE)
        streamId++;ended=false;helperExitConfirmed=false;streamClosed=false
        send(AdbPacket(AdbPacket.OPEN,streamId,0,("shell,v2,raw:"+HelperLaunch.command(apk)+"\u0000").toByteArray()))
        var p=receive();var duplicateCloses=0
        // A closed stream's terminal packet can still be queued on the transport.
        // Only this immediately preceding, proven closed identity is discarded.
        while(lastClosedRemote>0&&p.command==AdbPacket.CLSE&&p.arg0==lastClosedRemote&&p.arg1==streamId-1&&p.payload.isEmpty()){
            check(++duplicateCloses<=8){"Too many closed-stream packets"};p=receive()
        }
        check(p.command==AdbPacket.OKAY&&p.arg1==streamId&&p.arg0>0){"Helper start packet command=${p.command} remote=${p.arg0} local=${p.arg1} expected=$streamId"};remote=p.arg0
    }
    private fun readShell(){
        check(!ended&&!closed){"Helper already exited"}
        acceptShell(receive())
    }
    private fun acceptShell(p:AdbPacket){
        when(p.command){
            AdbPacket.OKAY->check(p.arg0==remote&&p.arg1==streamId)
            AdbPacket.WRTE->{
                check(!ended){"Data after helper exit"}
                check(p.arg0==remote&&p.arg1==streamId)
                send(AdbPacket(AdbPacket.OKAY,streamId,remote));p.payload.forEach{shellBytes.addLast(it)}
                while(shellBytes.size>=5){
                    val h=shellBytes.take(5).toByteArray();val id=h[0].toInt() and 255
                    val n=ByteBuffer.wrap(h,1,4).order(ByteOrder.LITTLE_ENDIAN).int
                    check(n in 0..AdbPacket.MAX_PAYLOAD&&id in 1..3){"Helper shell frame invalid"}
                    if(shellBytes.size<5+n)break
                    repeat(5){shellBytes.removeFirst()};val body=ByteArray(n){shellBytes.removeFirst()}
                    when(id){1->{check(stdout.size+n<=HelperFrames.MAX_RESPONSE+8);body.forEach{stdout.addLast(it)}};2->check(n<=16384);3->{check(n==1&&body[0].toInt()==0){"Helper exited unsuccessfully"};helperExitConfirmed=true;ended=true}}
                }
            }
            AdbPacket.CLSE->{
                check(p.arg0==remote&&p.arg1==streamId){"Wrong closed helper stream"}
                check(helperExitConfirmed&&stdout.isEmpty()&&shellBytes.isEmpty()){"Helper normal exit not confirmed"}
                // CLSE is terminal, not a request for another CLSE response.
                lastClosedRemote=remote;streamClosed=true;remote=0
            }
            else->throw IOException("Unexpected ADB stream packet")
        }
    }
    @Synchronized fun exchange(request:JSONObject):JSONObject=protocol {
        check(authenticated&&remote!=0&&!ended&&!streamClosed)
        val bytes=HelperFrames.request(request.toString().toByteArray())
        val shell=ByteBuffer.allocate(5+bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(0).putInt(bytes.size).put(bytes).array()
        // Every chunk is acknowledged before the next; never exceed the negotiated peer limit.
        var offset=0
        while(offset<shell.size){
            val end=minOf(offset+maxPayload,shell.size)
            send(AdbPacket(AdbPacket.WRTE,streamId,remote,shell.copyOfRange(offset,end)))
            while(true){
                val p=receive()
                if(p.command==AdbPacket.OKAY){check(p.arg0==remote&&p.arg1==streamId);break}
                acceptShell(p)
            }
            offset=end
        }
        while(stdout.size<8)readShell()
        val header=ByteArray(8){stdout.removeFirst()};val size=HelperFrames.responseSize(header)
        while(stdout.size<size)readShell()
        val reply=JSONObject(String(ByteArray(size){stdout.removeFirst()},Charsets.UTF_8))
        check(reply.keys().asSequence().toSet() in listOf(setOf("ok","result"),setOf("ok","error")))
        if(!reply.getBoolean("ok")){
            val reason=reply.getString("error")
            check(reason in setOf("SHELL_IDENTITY_INVALID","COMPATIBILITY_FAILED","HELPER_PROTOCOL_FAILED","HELPER_OPERATION_REJECTED")){"Unknown helper error schema"}
            throw HelperOperationRejectedException(reason)
        }
        reply.getJSONObject("result")
    }
    internal fun finishHelper():Unit=protocol {
        if(streamClosed){check(streamId>0&&helperExitConfirmed);return@protocol}
        check(remote!=0)
        if(!ended){
            val eof=ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(4).putInt(0).array()
            send(AdbPacket(AdbPacket.WRTE,streamId,remote,eof))
        }
        // The shell exit frame precedes ADB CLSE. Drain both before opening another stream.
        while(!streamClosed)acceptShell(receive())
        check(helperExitConfirmed)
    }
    override fun close(){
        if(closed)return;closed=true
        runCatching{if(remote!=0&&!ended){val eof=ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(4).putInt(0).array();send(AdbPacket(AdbPacket.WRTE,streamId,remote,eof));send(AdbPacket(AdbPacket.CLSE,streamId,remote))}}
        wire.close();stdout.clear();shellBytes.clear()
    }
}
