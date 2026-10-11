package app.bluedotoff

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Kept-connection lifecycle on a fake transport (from the GPT release review 2026-10-11): no native ADB, device or metadata writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class KeptConnectionTest {
    private class Wire:AdbWire {
        val incoming=java.util.ArrayDeque<Byte>()
        val sent=mutableListOf<AdbPacket>()
        @Volatile var closes=0
        fun packet(p:AdbPacket){p.encode().forEach{incoming.add(it)}}
        override fun write(bytes:ByteArray){sent+=AdbPacket.decode(bytes.copyOfRange(0,24),bytes.copyOfRange(24,bytes.size))}
        override fun read(length:Int):ByteArray{if(incoming.size<length)throw IOException("fixture EOF");return ByteArray(length){incoming.removeFirst()}}
        override fun startTls(){}
        override fun close(){closes++}
        fun helper(local:Int){
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
            packet(AdbPacket(AdbPacket.CLSE,remote,local))
        }
        fun connect():LocalAdbConnection {
            packet(AdbPacket(AdbPacket.STLS,0x01000000));packet(AdbPacket(AdbPacket.CNXN,0x01000001,4096))
            return LocalAdbConnection(this).apply{authenticate()}
        }
    }
    private val context get()=ApplicationProvider.getApplicationContext<Application>().also{
        it.applicationInfo.sourceDir="/data/app/~~fixture/app-test/base.apk"
    }
    private fun keep(w:Wire):LocalAdbConnection {
        val c=w.connect();w.helper(1)
        val r=LocalAdbSession.CloseReceipt()
        LocalAdbSession.exclusive{LocalAdbSession.useAuthenticated(context,c,r,"review",keep=true,port=45678){
            it.run(LocalAdbSession.CloseReceipt()){b->b.verify()}
        }}
        assertTrue(r.kept);assertFalse(r.socketClosed);assertTrue(LocalAdbSession.holdsConnection)
        return c
    }
    private fun cleanup(){
        LocalAdbSession.closeHeld()
        context.getSharedPreferences("wireless-management",0).edit().clear().commit()
        while(ForegroundState.active)ForegroundState.stopped()
    }
    private fun seedPairingPresence(){
        Settings.Global.putInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)
        context.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","fixture-not-a-key").commit()
        File(context.noBackupFilesDir,"local-adb-identity.enc").writeBytes(byteArrayOf())
    }
    @Test fun manualManagementStillClosesTheIdleConnectionWhenLeaving(){
        cleanup();val w=Wire()
        try{
            // MainActivity always passes hold=true, including when automatic management is off.
            context.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",false).putBoolean("heldOn",false).commit()
            seedPairingPresence();keep(w);w.helper(2)
            ForegroundState.started()
            WirelessWork.run(context,true,45678,hold=true){it.verify()}
            assertFalse(WirelessWork.held(context))
            ForegroundState.stopped()
            WirelessWork.release(context)
            Thread.sleep(300)
            assertEquals("An idle authenticated socket must close on exit even without automatic management consent",1,w.closes)
            assertFalse(LocalAdbSession.holdsConnection)
        }finally{cleanup()}
    }
    @Test fun automaticHeldMarkerAllowsClosingTheIdleConnection(){
        cleanup();val w=Wire()
        try{
            keep(w)
            context.getSharedPreferences("wireless-management",0).edit().putBoolean("heldOn",true).commit()
            WirelessWork.release(context)
            repeat(100){if(w.closes==0)Thread.sleep(10)}
            assertEquals(1,w.closes);assertFalse(LocalAdbSession.holdsConnection)
        }finally{cleanup()}
    }
    @Test fun successiveForegroundTasksReuseTheSameAuthenticatedSocketAndInvalidateOldBackend(){
        cleanup();val w=Wire()
        try{
            seedPairingPresence()
            keep(w);w.helper(2);w.helper(3)
            var previous:LocalAdbBackend?=null
            val second=LocalAdbSession.CloseReceipt()
            LocalAdbSession.withConnection(context,45678,second,keep=true){it.run(LocalAdbSession.CloseReceipt()){b->previous=b;b.verify()}}
            assertTrue(second.kept);assertEquals(0,w.closes)
            assertTrue(runCatching{previous!!.verify()}.isFailure)
            val third=LocalAdbSession.CloseReceipt()
            LocalAdbSession.withConnection(context,45678,third){it.run(LocalAdbSession.CloseReceipt()){b->b.verify()}}
            assertTrue(third.socketClosed);assertEquals(1,w.closes);assertFalse(LocalAdbSession.holdsConnection)
            assertEquals(3,w.sent.count{it.command==AdbPacket.OPEN})
            assertEquals(1,w.sent.count{it.command==AdbPacket.CNXN})
        }finally{cleanup()}
    }
}
