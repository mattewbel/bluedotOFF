package app.bluedotoff

import android.app.Application
import android.net.*
import android.os.*
import android.provider.Settings
import app.bluedotoff.adb.*
import app.bluedotoff.core.ApplicationScope
import app.bluedotoff.core.SetupFlow
import app.bluedotoff.core.SetupStage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** 1.2.1 Codex review R1-R4: the audit reproductions (artifacts/review-130-20261020) turned around to assert the fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class PcReviewRegressionTest {
    private fun runCase(body:(SetupSession,FakeSetupRuntime,SetupQueue)->Unit){
        val c=RuntimeEnvironment.getApplication()
        c.getSharedPreferences("ui-state",0).edit().putBoolean("welcomed",true).commit()
        ScopePreferences(c).select(ApplicationScope.ALL)
        val runtime=FakeSetupRuntime().apply{wifiConnected=false;wireless=false;pcStart=true;pcHelper=true;finalCheckResolved=false}
        val queue=SetupQueue()
        val session=SetupSession(c,runtime,queue)
        try{session.attach{};body(session,runtime,queue)}finally{session.close()}
    }
    /** R1: Wi-Fi coming back while the PC helper is being checked must not drop the check and leave setup on VERIFYING. */
    @Test fun wifiReconnectDuringPcVerificationStillFinishes(){runCase{s,r,q->
        assertEquals(1,q.tasks.size)
        r.wifiConnected=true;s.refresh()
        q.next();shadowOf(Looper.getMainLooper()).idleFor(10,TimeUnit.SECONDS)
        assertNotEquals(SetupStage.VERIFYING,SetupFlow.stage(s.facts))
        assertFalse(s.facts.connecting||s.facts.verifying)
    }}
    /** R4: the PC helper going away after the first check (and a new one coming) is noticed without the user tapping. */
    @Test fun helperLossAndReturnAfterTheFirstCheckIsNoticedAndRechecked(){runCase{s,r,q->
        RuntimeEnvironment.getApplication().getSharedPreferences("application-scope",0).edit().clear().commit()
        q.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.APPLICATION_SCOPE,SetupFlow.stage(s.facts))
        r.pcHelper=false
        shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS)
        assertEquals(SetupStage.PC_START,SetupFlow.stage(s.facts))
        r.pcHelper=true
        shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS)
        assertEquals("the new helper is checked again",1,q.tasks.size)
        q.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.APPLICATION_SCOPE,SetupFlow.stage(s.facts))
    }}
    private class Server:Binder(){init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{
            data.enforceInterface(WatchProtocol.DESCRIPTOR);data.readString()
            reply!!.writeNoException();reply.writeString(if(code==WatchProtocol.REQUEST)"{\"pid\":100}" else "{\"configured\":true}");return true
        }
    }
    /** R2: a paired user who chose the PC way does not get wireless debugging turned on (and left on) by setup. */
    @Test fun pcSetupDoesNotTurnOnWirelessDebugging(){
        val c=RuntimeEnvironment.getApplication()
        c.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"
        val cm=c.getSystemService(ConnectivityManager::class.java)
        val network=checkNotNull(cm.activeNetwork)
        val caps=NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(cm).setNetworkCapabilities(network,caps)
        shadowOf(cm).setLinkProperties(network,LinkProperties().apply{interfaceName="wlan0"})
        Settings.Global.putInt(c.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0)
        c.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","fixture-guid").commit()
        // Only the existing-file check is exercised; never creates or reads a real pairing key.
        File(c.noBackupFilesDir,"local-adb-identity.enc").writeText("synthetic placeholder, never loaded")
        PcStart.choose(c,true)
        val controls=WirelessControls(c)
        controls.setConsent(true);shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
        BackgroundWatch.set(c,true)
        val profile=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
            .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
            .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
            .put("watchProtocol",1).put("watchId","fixture-id")
            .put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        ForegroundState.started()
        try{
            assertTrue(WatchClient.attach(c,Server(),profile,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
            assertTrue(controls.mayStart(true))
            val runtime=AndroidSetupRuntime(c)
            runtime.foregroundUse()
            assertFalse(controls.wireless)
            runtime.verifyConnection(0,{},{true})
            assertFalse(controls.wireless)
        }finally{
            BackgroundWatch.set(c,false);WatchClient.stopNow(c);ForegroundState.stopped();PcStart.choose(c,false)
            c.getSharedPreferences("local-adb",0).edit().clear().commit();File(c.noBackupFilesDir,"local-adb-identity.enc").delete()
        }
    }
    /** R3: an old wireless pairing does not hide the PC restart guidance from someone who chose the PC way. */
    @Test fun aPairedPcUserStillGetsThePcGuidance(){
        val c=RuntimeEnvironment.getApplication()
        try{
            PcStart.choose(c,true)
            c.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","fixture-guid").commit()
            File(c.noBackupFilesDir,"local-adb-identity.enc").writeText("synthetic placeholder, never loaded")
            assertEquals("다시 켜려면 PC에서 bluedotOFF 시작 명령을 다시 실행해주세요.",WatchNotification.advice(true,false,PcStart.chosen(c)))
        }finally{PcStart.choose(c,false);c.getSharedPreferences("local-adb",0).edit().clear().commit();File(c.noBackupFilesDir,"local-adb-identity.enc").delete()}
    }
}
