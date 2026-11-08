package app.bluedotoff

import android.app.Application
import android.content.Intent
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Codex review 2026-11-06: the widget refresh must not claim a full check while an app waits for its retry, and an
 *  unreadable restore journal must not escape the refresh receiver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class Review130WidgetFixesTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    private class Server:Binder(){
        var requests=0
        init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            JSONObject(data.readString()!!)
            if(code==WatchProtocol.REQUEST)requests++
            reply!!.writeNoException()
            reply.writeString(if(code==WatchProtocol.REQUEST)JSONObject().put("pid",100).toString() else JSONObject().put("configured",true).toString())
            return true
        }
    }
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
    private fun attach():Server {
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        BackgroundWatch.set(c,true)
        val p=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true).put("historyAbiVerified",true)
            .put("indicatorAbiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
            .put("watchProtocol",1).put("watchId","review-fixture").put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        val server=Server()
        assertTrue(WatchClient.attach(c,server,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
        worker().submit{}.get(3,TimeUnit.SECONDS)
        return server
    }
    @After fun cleanup(){
        File(c.noBackupFilesDir,"original-bit-journal.json").delete()
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
        StatusWidget::class.java.getDeclaredField("refreshing").apply{isAccessible=true}.setBoolean(StatusWidget,false)
        BackgroundWatch.set(c,false);WatchClient.stopNow(c)
    }
    @Test fun retryOnlyRefreshMustNotClaimANewFullCheckWithoutReadingTheTarget(){
        val server=attach()
        val e=Entry(Identity("test.waiting",0,10500,listOf("cert"),1,1,1),"Waiting",256,true,TargetState.FAILED_SAFE,
            System.currentTimeMillis(),"fp","boot",false,settlingVerified=false,retryHide=true)
        assertTrue(JournalStore(c).commit(e))
        val oldTime=1000L
        c.getSharedPreferences("widget",0).edit().putLong("checkedAt",oldTime).commit()
        val before=server.requests
        StatusWidget.refresh(c)
        worker().submit{}.get(3,TimeUnit.SECONDS)
        assertEquals("the retry target was not read",before,server.requests)
        assertEquals("no target read must not advance the full-check timestamp",oldTime,c.getSharedPreferences("widget",0).getLong("checkedAt",0))
    }
    @Test fun brokenJournalMustNotEscapeTheWidgetBroadcastCallback(){
        attach()
        File(c.noBackupFilesDir,"original-bit-journal.json").writeText("broken-journal")
        WidgetRefreshReceiver().onReceive(c,Intent(c,WidgetRefreshReceiver::class.java))
        assertEquals(0L,c.getSharedPreferences("widget",0).getLong("checkedAt",0))
        assertFalse(StatusWidget::class.java.getDeclaredField("refreshing").apply{isAccessible=true}.getBoolean(StatusWidget))
        assertEquals("broken-journal",File(c.noBackupFilesDir,"original-bit-journal.json").readText())
    }
}
