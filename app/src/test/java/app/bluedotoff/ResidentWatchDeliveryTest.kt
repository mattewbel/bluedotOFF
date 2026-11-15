package app.bluedotoff

import android.app.Application
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.*
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.ResidentWatchHelper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises the production event windows, async delivery and heartbeat reconfiguration in a host fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ResidentWatchDeliveryTest {
    class Holder(@JvmField val provider: Endpoint)
    class Manager(val holder: Holder) {
        fun getContentProviderExternal(name:String,user:Int,token:IBinder,tag:String)=holder
        fun removeContentProviderExternalAsUser(name:String,token:IBinder,user:Int) {}
    }
    class Endpoint(val uid:Int,val targetList:String) {
        val calls=mutableListOf<String>()
        val accepted=mutableListOf<String>()
        var offline=true
        var checked=false
        fun call(source:AttributionSource,authority:String,method:String,arg:String?,extras:Bundle):Bundle {
            calls+=method
            check(!offline) { "Fixture: provider temporarily unavailable" }
            accepted+=extras.getStringArrayList("packages").orEmpty()
            return Bundle().apply { putInt("uid",uid);putBoolean("enabled",true);putString("targets",targetList);if(checked)putLongArray("checkedRevisions",extras.getLongArray("revisions")) }
        }
    }
    private fun field(name:String)=ResidentWatchHelper::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun invoke(name:String,types:Array<Class<*>>,vararg args:Any?) =
        ResidentWatchHelper::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}.invoke(ResidentWatchHelper,*args)
    @Test fun aLateProviderReplyCannotUndoARegistrationOrReinstateARestoredTarget() {
        val retained="test.retained"
        val restored="test.restored"
        fun list(vararg names:String)=JSONArray().apply{
            names.forEachIndexed{i,name->put(JSONObject().put("package",name).put("uid",10500+i))}
        }
        field("main").set(null,Handler(Looper.getMainLooper()))
        field("targets").set(null,mapOf(retained to 10500,restored to 10501))
        val revision=field("targetVersion").getLong(null)
        invoke("configure",arrayOf(JSONArray::class.java),list(retained))
        invoke("configureReply",arrayOf(JSONArray::class.java,Long::class.javaPrimitiveType!!),list(restored),revision)
        assertEquals(mapOf(retained to 10500),field("targets").get(null))
    }
    @Test fun bothMissedWindowsAreReplayedOnTheNextHeartbeatUntilTheCheckIsAcknowledged() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val pm=app.packageManager
        val membership=pm.getPackageInfo(app.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val sig=Signature(byteArrayOf(1,2,3,4))
        val signed=SigningInfo()
        shadowOf(signed).setSignatures(arrayOf(sig))
        membership.signingInfo=signed
        membership.signatures=arrayOf(sig)
        shadowOf(pm).installPackage(membership)
        val pi=pm.getPackageInfo(app.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val appUid=pi.applicationInfo!!.uid
        shadowOf(pm).setPackagesForUid(appUid,app.packageName)
        check(pi.longVersionCode==BuildConfig.VERSION_CODE.toLong())
        field("context").set(null,app)
        field("apk").set(null,pi.applicationInfo!!.sourceDir)
        field("signer").set(null,listOf(BinderBackend.digest(sig.toByteArray())))
        field("appUid").setInt(null,appUid)
        field("appUpdatedAt").setLong(null,pi.lastUpdateTime)
        field("proof").set(null,JSONObject())
        field("main").set(null,Handler(Looper.getMainLooper()))
        field("stopping").setBoolean(null,false)
        // Verify the fixture's own identity path before starting asynchronous sends.
        invoke("validateInstall",emptyArray())
        val target="test.mapped.app"
        val targetUid=10550
        val list=JSONArray().put(JSONObject().put("package",target).put("uid",targetUid)).toString()
        val endpoint=Endpoint(appUid,list)
        field("am").set(null,Manager(Holder(endpoint)))
        field("targets").set(null,mapOf(target to targetUid))
        val worker=field("callbacks").get(null) as ExecutorService
        fun tick(ms:Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            worker.submit {}.get(6,TimeUnit.SECONDS)
        }
        fun changed() { invoke("changed",arrayOf(String::class.java),target) }
        fun heartbeat() {
            invoke("send",arrayOf(String::class.java,Set::class.java),"state",emptySet<String>())
            worker.submit {}.get(6,TimeUnit.SECONDS)
        }
        changed()
        tick(1000);tick(5000)
        assertEquals(listOf("event","event"),endpoint.calls)
        assertTrue(endpoint.accepted.isEmpty())
        endpoint.offline=false
        heartbeat()
        assertEquals(listOf(target),endpoint.accepted)
        endpoint.checked=true
        heartbeat()
        assertEquals(listOf(target,target),endpoint.accepted)
        heartbeat()
        assertEquals(listOf(target,target),endpoint.accepted)
        assertEquals(listOf("event","event","state","state","state"),endpoint.calls)
        changed();tick(1000);tick(5000)
        assertEquals(listOf(target,target,target,target),endpoint.accepted)
        assertFalse(field("stopping").getBoolean(null))
    }
}
