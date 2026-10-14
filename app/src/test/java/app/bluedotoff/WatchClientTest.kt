package app.bluedotoff

import android.app.Application
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WatchClientTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    private class Server:Binder(){var calls=0;var stopped=false;init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{data.enforceInterface(WatchProtocol.DESCRIPTOR);val q=JSONObject(data.readString()!!);calls++;if(code==WatchProtocol.STOP)stopped=true
            reply!!.writeNoException();reply.writeString(if(code==WatchProtocol.REQUEST)JSONObject().put("pid",100).toString() else JSONObject().put("configured",true).toString());return true}}
    private fun profile()=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture").put("watchProtocol",1).put("watchId","fixture-id").put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
    private fun attach(s:Server,p:JSONObject=profile()):Boolean{Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true);return WatchClient.attach(c,s,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())}
    @After fun clean(){BackgroundWatch.set(c,false);WatchClient.stopNow(c)}
    @Test fun usbOnDoesNotRequireWifiOrWirelessForAnExistingHelper(){val s=Server();Settings.Global.putInt(c.contentResolver,"wifi_on",0);Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0);assertTrue(attach(s));assertTrue(WatchClient.available(c));WatchClient.exclusive(c){session->session.run{assertEquals("fixture",it.verify().abiProfile);assertTrue(it.liveShellServer())}};assertTrue(s.calls>0)}
    @Test fun switchOffBlocksNewWorkWithoutBreakingAnInflightRollback(){val s=Server();attach(s);WatchClient.exclusive(c){session->session.run{backend->BackgroundWatch.set(c,false);assertFalse(WatchClient.available(c));assertTrue(backend.liveShellServer())}};WatchClient.stopNow(c);assertTrue(s.stopped);assertFalse(WatchClient.connected())}
    @Test fun usbOffMakesHelperUnavailableEvenWhenBinderIsStillAlive(){val s=Server();attach(s);Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0);assertFalse(WatchClient.available(c))}
    @Test fun wrongInstallOrProfileCannotBeAdopted(){val s=Server();BackgroundWatch.set(c,true);Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);try{WatchClient.attach(c,s,profile(),"/data/app/other/base.apk",BuildConfig.VERSION_CODE.toLong());fail()}catch(_:IllegalArgumentException){};assertFalse(WatchClient.available(c));try{attach(s,profile().put("uid",10000));fail()}catch(_:Exception){};assertFalse(WatchClient.available(c))}
    @Test fun oldVersionCannotAttachAndDisabledSwitchRefusesHandshake(){val s=Server();BackgroundWatch.set(c,true);Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);try{WatchClient.attach(c,s,profile(),c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()-1);fail()}catch(_:IllegalArgumentException){};BackgroundWatch.set(c,false);assertFalse(WatchClient.attach(c,s,profile(),c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))}
    @Test fun providerDoesNotAcceptOrdinaryAppCalls(){try{WatchBridgeProvider().call("attach",null,Bundle());fail()}catch(_:IllegalStateException){}}
    @Test fun launchHasFixedEntrypointAndRejectsShellInjection(){val command=WatchProtocol.launch("/data/app/~~fixture/bluedot/base.apk");assertTrue(command.contains("nohup setsid app_process"));assertTrue(command.contains("ResidentWatchHelper"));try{WatchProtocol.launch("/data/app/test/base.apk;id");fail()}catch(_:IllegalArgumentException){}}
    @Test fun foregroundRoutingUsesResidentHelperWithoutOpeningWireless(){val s=Server();attach(s);Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0);Settings.Global.putInt(c.contentResolver,"wifi_on",0);WirelessWork.run(c,true){assertTrue(it.liveShellServer());assertEquals("fixture",it.verify().abiProfile)};assertFalse(WirelessWork.leasing);assertFalse(WirelessControls(c).wireless)}
    @Test fun updateWithTheSameVersionAndPathCannotKeepTheOldHelper(){val s=Server();try{attach(s,profile().put("appUpdatedAt",profile().getLong("appUpdatedAt")+1));fail()}catch(_:IllegalArgumentException){};assertFalse(WatchClient.available(c))}
    @Test fun stopWaitsForAnInflightOperationBeforeClosingHelper(){
        val s=Server();attach(s)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val stopped=java.util.concurrent.CountDownLatch(1)
        val owner=Thread{WatchClient.exclusive(c){session->session.run{entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);assertTrue(it.liveShellServer())}}}
        owner.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));BackgroundWatch.set(c,false)
        val stopper=Thread{WatchClient.stopNow(c);stopped.countDown()};stopper.start()
        try{assertFalse(stopped.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertFalse(s.stopped)}finally{release.countDown()}
        assertTrue(stopped.await(2,java.util.concurrent.TimeUnit.SECONDS));owner.join(2000);stopper.join(2000);assertTrue(s.stopped)
    }
}
