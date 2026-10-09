package app.bluedotoff

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class LocalAdbSessionTest {
    private class Wire:AdbWire {
        val incoming=java.util.ArrayDeque<Byte>()
        val sent=mutableListOf<AdbPacket>()
        var tls=0;var closes=0;var failClose=false
        fun packet(p:AdbPacket){p.encode().forEach{incoming.add(it)}}
        override fun write(bytes:ByteArray){sent+=AdbPacket.decode(bytes.copyOfRange(0,24),bytes.copyOfRange(24,bytes.size))}
        override fun read(length:Int):ByteArray{if(incoming.size<length)throw IOException("fixture EOF");return ByteArray(length){incoming.removeFirst()}}
        override fun startTls(){tls++}
        override fun close(){closes++;if(failClose)throw IOException("fixture close failure")}
        fun helper(local:Int,normalClose:Boolean=true){
            val remote=local+100
            packet(AdbPacket(AdbPacket.OKAY,remote,local))
            packet(AdbPacket(AdbPacket.OKAY,remote,local))
            val profile=JSONObject().put("pid",local+1000).put("uid",2000).put("selinux","u:r:shell:s0")
                .put("profileId","fixture").put("oneUi","9.0").put("fingerprint",Build.FINGERPRINT)
                .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
            val reply=HelperFrames.request(JSONObject().put("ok",true).put("result",profile).toString().toByteArray())
            val shell=ByteBuffer.allocate(5+reply.size).order(ByteOrder.LITTLE_ENDIAN).put(1).putInt(reply.size).put(reply).array()
            packet(AdbPacket(AdbPacket.WRTE,remote,local,shell))
            packet(AdbPacket(AdbPacket.OKAY,remote,local))
            packet(AdbPacket(AdbPacket.WRTE,remote,local,byteArrayOf(3,1,0,0,0,0)))
            if(normalClose)packet(AdbPacket(AdbPacket.CLSE,remote,local))
        }
        fun connect():LocalAdbConnection {
            packet(AdbPacket(AdbPacket.STLS,0x01000000));packet(AdbPacket(AdbPacket.CNXN,0x01000001,4096))
            return LocalAdbConnection(this).apply{authenticate()}
        }
    }
    private val context get()=ApplicationProvider.getApplicationContext<Application>().also{
        it.applicationInfo.sourceDir="/data/app/~~fixture/app-test/base.apk"
    }
    @Test fun sevenHelpersHaveFreshProfilesAndCannotUsePreviousBackendOrCloseSocketEarly(){
        val w=Wire();val c=w.connect();repeat(7){w.helper(it+1)}
        val receipt=LocalAdbSession.CloseReceipt();var previous:LocalAdbBackend?=null
        LocalAdbSession.exclusive {
            LocalAdbSession.useAuthenticated(context,c,receipt,"fixture-connection"){connection->
                repeat(7){
                    connection.run(LocalAdbSession.CloseReceipt()){backend->
                        previous?.let{assertTrue(runCatching{it.verify()}.isFailure)}
                        assertEquals("9.0",backend.verify().oneUi);previous=backend
                    }
                    assertTrue(runCatching{previous!!.verify()}.isFailure)
                    assertEquals(0,w.closes);assertFalse(receipt.socketClosed)
                }
            }
        }
        assertEquals(1,w.tls);assertEquals(1,w.closes)
        assertTrue(receipt.helperExited&&receipt.streamClosed&&receipt.socketClosed)
        assertEquals(7,w.sent.count{it.command==AdbPacket.OPEN})
        assertEquals(7,w.sent.count{it.command==AdbPacket.WRTE&&it.payload.contains("PROFILE".toByteArray())})
    }
    private fun ByteArray.contains(needle:ByteArray)=indices.any{start->start+needle.size<=size&&needle.indices.all{this[start+it]==needle[it]}}
    @Test fun failedChunkStopsEvenIfCallerCatchesAndNextHelperIsAvailable(){
        for(missingClose in listOf(false,true)){
            val w=Wire();val c=w.connect();w.helper(1,!missingClose);w.helper(2)
            val receipt=LocalAdbSession.CloseReceipt();var nextTarget=false
            LocalAdbSession.exclusive {
                LocalAdbSession.useAuthenticated(context,c,receipt,"fixture-failure"){connection->
                    assertTrue(runCatching{connection.run(LocalAdbSession.CloseReceipt()){it.verify();if(!missingClose)error("uncertain outcome")}}.isFailure)
                    assertTrue(runCatching{connection.run(LocalAdbSession.CloseReceipt()){nextTarget=true}}.isFailure)
                }
            }
            assertFalse(nextTarget);assertFalse(receipt.helperExited);assertTrue(receipt.socketClosed)
            assertEquals(1,w.sent.count{it.command==AdbPacket.OPEN})
        }
    }
    @Test fun failedFinalSocketCloseCannotAuthorizeAutomaticOff(){
        val w=Wire();val c=w.connect();w.helper(1);w.failClose=true
        val receipt=LocalAdbSession.CloseReceipt()
        LocalAdbSession.exclusive {LocalAdbSession.useAuthenticated(context,c,receipt,"fixture-close"){connection->connection.run(LocalAdbSession.CloseReceipt()){it.verify()}}}
        assertTrue(receipt.helperExited);assertFalse(receipt.socketClosed)
        assertFalse(WirelessManagementPolicy.mayDisable(true,true,receipt.helperExited,receipt.socketClosed,true))
    }
    @Test fun foregroundExitStopsNextChunkAndStillClosesTransport(){
        val w=Wire();val c=w.connect();w.helper(1);w.helper(2)
        val receipt=LocalAdbSession.CloseReceipt();var nextTarget=false
        ForegroundState.started()
        try {
            LocalAdbSession.exclusive {LocalAdbSession.useAuthenticated(context,c,receipt,"fixture-foreground"){connection->
                val batch=WirelessWork.BatchSession(connection)
                batch.run{it.verify()}
                ForegroundState.stopped()
                assertTrue(runCatching{batch.run{nextTarget=true}}.isFailure)
            }}
        }finally{if(ForegroundState.active)ForegroundState.stopped()}
        assertFalse(nextTarget);assertEquals(1,w.sent.count{it.command==AdbPacket.OPEN});assertEquals(1,w.closes)
    }
}
