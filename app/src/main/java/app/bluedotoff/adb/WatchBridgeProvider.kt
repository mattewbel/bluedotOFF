package app.bluedotoff.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import app.bluedotoff.BackgroundWatch
import org.json.JSONObject

/** Only shell can deliver a helper/event. The manifest also requires INTERACT_ACROSS_USERS_FULL.
 * No exported generic query, command or mutation API. All writes remain in the app's serialized safety engines. */
class WatchBridgeProvider:ContentProvider() {
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        check(Binder.getCallingUid()==2000&&arg==null&&method in setOf("attach","event","state","failed"))
        val c=context!!.applicationContext
        val b=extras?.getBinder("helper")?:error("Missing watch helper")
        val profile=JSONObject(extras.getString("profile")?:error("Missing profile"))
        if(method=="failed"){
            check(extras.getString("apk")==c.applicationInfo.sourceDir&&extras.getLong("version")==app.bluedotoff.BuildConfig.VERSION_CODE.toLong())
            val phase=profile.getString("startupPhase");val error=profile.getString("startupError")
            require(phase in setOf("PROFILE","PACKAGE_CALLBACK","PROCESS_CALLBACK","CLIENT_HANDSHAKE")&&error.matches(Regex("[A-Za-z0-9_]{1,100}")))
            app.bluedotoff.WatchLog.event("state","watchStartupFailed phase=$phase error=$error")
            if(!WatchClient.available(c))app.bluedotoff.WatchNotification.stopped(c,app.bluedotoff.WatchNotification.Reason.STOPPED)
            return Bundle().apply{putInt("uid",c.applicationInfo.uid);putBoolean("enabled",false)}
        }
        val accepted=WatchClient.attach(c,b,profile,extras.getString("apk")?:error("Missing install"),extras.getLong("version"))
        if(accepted){
            if(method=="event")WatchClient.signal(c,extras.getStringArrayList("packages")?.takeIf{it.size<=2000}?.toSet()?:emptySet())
            WatchClient.reportState(c)
        }
        return Bundle().apply{putInt("uid",c.applicationInfo.uid);putBoolean("enabled",accepted&&BackgroundWatch.enabled(c));putBoolean("draining",WatchClient.draining(b));putString("targets",if(accepted)WatchClient.config(c).toString() else "[]")}
    }
    override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=throw UnsupportedOperationException()
    override fun getType(u:Uri):String?=null
    override fun insert(u:Uri,v:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(u:Uri,s:String?,a:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?):Int=throw UnsupportedOperationException()
}
