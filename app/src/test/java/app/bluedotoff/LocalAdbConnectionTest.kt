package app.bluedotoff

import android.app.Application
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
class LocalAdbConnectionTest {
    private class Wire:AdbWire {
        val incoming=java.util.ArrayDeque<Byte>();val sent=mutableListOf<AdbPacket>();var tls=0;var closed=false
        fun packet(p:AdbPacket){p.encode().forEach{incoming.add(it)}}
        override fun write(bytes:ByteArray){sent+=AdbPacket.decode(bytes.copyOfRange(0,24),bytes.copyOfRange(24,bytes.size))}
        override fun read(length:Int):ByteArray{if(incoming.size<length)throw IOException("controlled timeout or EOF");return ByteArray(length){incoming.removeFirst()}}
        override fun startTls(){tls++}
        override fun close(){closed=true}
        fun handshake(){packet(AdbPacket(AdbPacket.STLS,0x01000000));packet(AdbPacket(AdbPacket.CNXN,0x01000001,4096));packet(AdbPacket(AdbPacket.OKAY,9,1))}
        fun ack(){packet(AdbPacket(AdbPacket.OKAY,9,1))}
        fun stdout(bytes:ByteArray){val b=ByteBuffer.allocate(5+bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(1).putInt(bytes.size).put(bytes).array();packet(AdbPacket(AdbPacket.WRTE,9,1,b))}
    }
    private fun session(w:Wire)=LocalAdbConnection(w).apply{authenticate();startHelper("/data/app/~~test/app-test/base.apk")}
    private fun fails(block:()->Unit){try{block();fail("Malformed or unavailable transport must fail closed")}catch(_:Exception){}}
    @Test fun onlyModernTlsHandshakeCanOpenTheFixedHelper(){val w=Wire();w.handshake();session(w).use{ };assertEquals(1,w.tls);assertTrue(w.sent.single{it.command==AdbPacket.OPEN}.payload.decodeToString().startsWith("shell,v2,raw:CLASSPATH=/data/app/"));assertTrue(w.closed)}
    @Test fun missingOrLegacyAuthenticationNeverLaunchesHelper(){for(p in listOf(AdbPacket(AdbPacket.CNXN,0x01000001,4096),AdbPacket(AdbPacket.CLSE))){val w=Wire();w.packet(p);LocalAdbConnection(w).use{c->fails{c.authenticate()}};assertTrue(w.sent.none{it.command==AdbPacket.OPEN});assertTrue(w.closed)}}
    @Test fun wrongStreamIdentityCannotProduceTrustedReply(){val w=Wire();w.handshake();session(w).use{c->w.packet(AdbPacket(AdbPacket.OKAY,10,1));fails{c.exchange(JSONObject().put("op","HEALTH"))}}}
    @Test fun malformedHelperHeaderAndJsonAreRejected(){for(reply in listOf(ByteArray(8),HelperFrames.request("not-json".toByteArray()),HelperFrames.request("{\"ok\":true,\"result\":{},\"extra\":1}".toByteArray()))){val w=Wire();w.handshake();session(w).use{c->w.ack();w.stdout(reply);fails{c.exchange(JSONObject().put("op","HEALTH"))}}}}
    @Test fun helperCrashAndConnectionLossBeforeReadbackCannotBeSuccess(){val w=Wire();w.handshake();session(w).use{c->w.ack();w.packet(AdbPacket(AdbPacket.CLSE,9,1));fails{c.exchange(JSONObject().put("op","HEALTH"))}};assertTrue(w.closed)}
    @Test fun lostSocketDuringRequestHasNoRetryWrite(){val w=Wire();w.handshake();session(w).use{c->fails{c.exchange(JSONObject().put("op","HEALTH"))};assertEquals(1,w.sent.count{it.command==AdbPacket.WRTE})}}
    @Test fun completeTypedReplyWorksAcrossShellFrames(){val w=Wire();w.handshake();session(w).use{c->w.ack();val response=HelperFrames.request("{\"ok\":true,\"result\":{\"uid\":2000}}".toByteArray());w.stdout(response.copyOfRange(0,10));w.stdout(response.copyOfRange(10,response.size));assertEquals(2000,c.exchange(JSONObject().put("op","HEALTH")).getInt("uid"))}}
    @Test fun logicalHelperRejectionIsDistinctFromMalformedWire(){val w=Wire();w.handshake();session(w).use{c->w.ack();w.stdout(HelperFrames.request("{\"ok\":false,\"error\":\"HELPER_OPERATION_REJECTED\"}".toByteArray()));try{c.exchange(JSONObject().put("op","HEALTH"));fail()}catch(_:HelperOperationRejectedException){}}}
    @Test fun negotiatedSmallPayloadLimitIsRespected(){val w=Wire();w.handshake();session(w).use{c->val request=JSONObject().put("op","HEALTH").put("fixture","x".repeat(10000));repeat(3){w.ack()};w.stdout(HelperFrames.request("{\"ok\":true,\"result\":{}}".toByteArray()));c.exchange(request);val writes=w.sent.filter{it.command==AdbPacket.WRTE};assertEquals(3,writes.size);assertTrue(writes.all{it.payload.size<=4096})}}
    @Test fun orderlyExitRequiresShellZeroExitBeforeSocketClose(){
        val w=Wire();w.handshake();session(w).use{c->
            w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)))
            w.packet(AdbPacket(AdbPacket.CLSE,9,1))
            c.finishHelper();assertFalse(w.closed)
            assertTrue(w.sent.any{it.command==AdbPacket.WRTE&&it.payload.contentEquals(byteArrayOf(4,0,0,0,0))})
        };assertTrue(w.closed)
    }
    @Test fun unexpectedCloseNeverBecomesNormalExitOnSecondAttempt(){
        val w=Wire();w.handshake();session(w).use{c->w.packet(AdbPacket(AdbPacket.CLSE,9,1));fails{c.finishHelper()};fails{c.finishHelper()}}
    }
    @Test fun missingOrNonzeroExitCannotAuthorizeAutomaticOff(){
        for(exit in listOf<ByteArray?>(null,byteArrayOf(3,1,0,0,0,1))){
            val w=Wire();w.handshake();session(w).use{c->w.ack();exit?.let{w.packet(AdbPacket(AdbPacket.WRTE,9,1,it))};fails{c.finishHelper()}};assertTrue(w.closed)
        }
    }
    @Test fun twoBoundedHelpersReuseTlsButUseDifferentStreamIdentities(){
        val w=Wire();w.handshake();session(w).use{c->
            w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)));w.packet(AdbPacket(AdbPacket.CLSE,9,1))
            c.finishHelper();assertFalse(w.closed)
            w.packet(AdbPacket(AdbPacket.OKAY,10,2));c.startHelper("/data/app/~~test/app-test/base.apk")
            w.packet(AdbPacket(AdbPacket.OKAY,10,2))
            val response=HelperFrames.request("{\"ok\":true,\"result\":{\"uid\":2000}}".toByteArray())
            val shell=ByteBuffer.allocate(5+response.size).order(ByteOrder.LITTLE_ENDIAN).put(1).putInt(response.size).put(response).array()
            w.packet(AdbPacket(AdbPacket.WRTE,10,2,shell));assertEquals(2000,c.exchange(JSONObject().put("op","PROFILE")).getInt("uid"))
            w.packet(AdbPacket(AdbPacket.OKAY,10,2));w.packet(AdbPacket(AdbPacket.WRTE,10,2,byteArrayOf(3,1,0,0,0,0)));w.packet(AdbPacket(AdbPacket.CLSE,10,2))
            c.finishHelper();assertFalse(w.closed)
        }
        assertEquals(1,w.tls);assertEquals(1,w.sent.count{it.command==AdbPacket.CNXN})
        assertEquals(listOf(1,2),w.sent.filter{it.command==AdbPacket.OPEN}.map{it.arg0});assertTrue(w.closed)
    }
    @Test fun zeroExitWithoutAdbCloseCannotOpenNextHelper(){
        val w=Wire();w.handshake();session(w).use{c->
            w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)))
            fails{c.finishHelper()};fails{c.startHelper("/data/app/~~test/app-test/base.apk")}
            assertEquals(1,w.sent.count{it.command==AdbPacket.OPEN})
        }
    }
    @Test fun stalePacketsCannotBeAcceptedByNextHelper(){
        for(command in listOf(AdbPacket.WRTE,AdbPacket.OKAY,AdbPacket.CLSE)){
            val w=Wire();w.handshake();session(w).use{c->
                w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)));w.packet(AdbPacket(AdbPacket.CLSE,9,1));c.finishHelper()
                w.packet(AdbPacket(AdbPacket.OKAY,10,2));c.startHelper("/data/app/~~test/app-test/base.apk")
                w.packet(AdbPacket(command,9,1,if(command==AdbPacket.WRTE)byteArrayOf(3,1,0,0,0,0) else byteArrayOf()))
                fails{c.exchange(JSONObject().put("op","PROFILE"))};fails{c.finishHelper()}
            }
        }
    }
    @Test fun incompleteShellFrameAfterExitPoisonsStream(){
        val w=Wire();w.handshake();session(w).use{c->
            w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0,1)));w.packet(AdbPacket(AdbPacket.CLSE,9,1))
            fails{c.finishHelper()};fails{c.startHelper("/data/app/~~test/app-test/base.apk")}
        }
    }
    @Test fun duplicateCloseOfProvenPreviousStreamDoesNotBecomeNextHelperReplyOrEcho(){
        val w=Wire();w.handshake();session(w).use{c->
            w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)));w.packet(AdbPacket(AdbPacket.CLSE,9,1));c.finishHelper()
            w.packet(AdbPacket(AdbPacket.CLSE,9,1));w.packet(AdbPacket(AdbPacket.OKAY,10,2))
            c.startHelper("/data/app/~~test/app-test/base.apk")
            assertEquals(listOf(1,2),w.sent.filter{it.command==AdbPacket.OPEN}.map{it.arg0})
            assertTrue(w.sent.none{it.command==AdbPacket.CLSE})
        }
    }
    @Test fun arbitraryStalePacketsWhileOpeningNextStreamStillFailClosed(){
        for(packet in listOf(AdbPacket(AdbPacket.CLSE,99,1),AdbPacket(AdbPacket.CLSE,9,99),AdbPacket(AdbPacket.WRTE,9,1),AdbPacket(AdbPacket.OKAY,9,1))){
            val w=Wire();w.handshake();session(w).use{c->
                w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)));w.packet(AdbPacket(AdbPacket.CLSE,9,1));c.finishHelper()
                w.packet(packet);w.packet(AdbPacket(AdbPacket.OKAY,10,2));fails{c.startHelper("/data/app/~~test/app-test/base.apk")};fails{c.exchange(JSONObject().put("op","PROFILE"))}
            }
        }
    }
    @Test fun duplicateCloseFloodHasABoundedFailureWithoutRetryOpening(){
        val w=Wire();w.handshake();session(w).use{c->
            w.ack();w.packet(AdbPacket(AdbPacket.WRTE,9,1,byteArrayOf(3,1,0,0,0,0)));w.packet(AdbPacket(AdbPacket.CLSE,9,1));c.finishHelper()
            repeat(9){w.packet(AdbPacket(AdbPacket.CLSE,9,1))}
            fails{c.startHelper("/data/app/~~test/app-test/base.apk")};fails{c.startHelper("/data/app/~~test/app-test/base.apk")}
            assertEquals(2,w.sent.count{it.command==AdbPacket.OPEN})
        }
    }
    @Test fun zeroIdentityCloseBeforeFirstHelperIsNotAProvenPreviousStream(){
        val w=Wire();w.packet(AdbPacket(AdbPacket.STLS,0x01000000));w.packet(AdbPacket(AdbPacket.CNXN,0x01000001,4096))
        LocalAdbConnection(w).use{c->
            c.authenticate();w.packet(AdbPacket(AdbPacket.CLSE,0,0));w.packet(AdbPacket(AdbPacket.OKAY,9,1))
            fails{c.startHelper("/data/app/~~test/app-test/base.apk")};fails{c.startHelper("/data/app/~~test/app-test/base.apk")}
            assertEquals(1,w.sent.count{it.command==AdbPacket.OPEN})
        }
    }
}
