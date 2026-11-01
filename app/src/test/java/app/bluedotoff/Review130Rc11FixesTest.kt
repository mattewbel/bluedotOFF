package app.bluedotoff

import android.app.Application
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** GPT rc11 review (2026-10-29): the widget's "check now" is done only when every app was read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class Review130Rc11FixesTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    @After fun clean(){BackgroundWatch.set(c,false);WatchClient.stopNow(c)}
    private class Server:Binder(){
        var requests=0
        init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{
            data.enforceInterface(WatchProtocol.DESCRIPTOR);data.readString()
            if(code==WatchProtocol.REQUEST)requests++
            reply!!.writeNoException();reply.writeString(JSONObject().put("configured",true).put("pid",100).toString());return true
        }
    }
    @Test fun anAppSkippedByTheRetryPauseIsNotACompletedCheck(){
        val server=Server()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true)
        val profile=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true)
            .put("historyAbiVerified",true).put("indicatorAbiVerified",true).put("fingerprint",Build.FINGERPRINT)
            .put("oneUi","90000").put("profileId","fixture").put("watchProtocol",1).put("watchId","fixture-id")
            .put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        assertTrue(WatchClient.attach(c,server,profile,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
        val pkg="test.cooldown"
        assertTrue(JournalStore(c).commit(Entry(Identity(pkg,0,10123,listOf("signer"),100,1,100),"Map",256,true,TargetState.APPLIED,200,"fixture","boot",true,invariantDigest="fixture",settlingVerified=true)))
        (WatchClient::class.java.getDeclaredField("cooldown").apply{isAccessible=true}.get(WatchClient) as WatchCooldown).result(pkg,SystemClock.elapsedRealtime(),false)
        val done=CountDownLatch(1);val checked=AtomicReference<Boolean>()
        WatchClient.checkNow(c){checked.set(it);done.countDown()}
        assertTrue(done.await(5,TimeUnit.SECONDS))
        assertEquals(0,server.requests)
        assertEquals(false,checked.get())
    }
}
