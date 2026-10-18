package app.bluedotoff.adb

import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import org.json.JSONObject
import org.json.JSONArray
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/** Experimental shell daemon. Owns event subscriptions, not the app-private journal or automatic write policy.
 * App callbacks always re-read the durable journal and use the existing SafetyEngine. Independently authored;
 * no Shizuku source/dependency. No root, settings writes, socket, command executor or arbitrary Binder forwarding. */
@androidx.annotation.RequiresApi(37)
object ResidentWatchHelper {
    private lateinit var context:Context
    private lateinit var apk:String
    private lateinit var proof:JSONObject
    private lateinit var am:Any
    private lateinit var main:Handler
    private lateinit var signer:List<String>
    private var appUid=-1
    private var appUpdatedAt=0L
    private val operationLock=Any()
    private val callbacks=Executors.newSingleThreadExecutor()
    private val targets=java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val windows=listOf(app.bluedotoff.core.EventWindow(750,3_000),app.bluedotoff.core.EventWindow(5_000,15_000))
    private val ends=windows.map{w->Runnable{val names=w.take();if(names.isNotEmpty())send("event",names)}}
    private val receivers=mutableListOf<Any>()
    @Volatile private var stopping=false
    private var permissionListener:Any?=null
    private fun pm()=context.packageManager
    private fun signatures(pi:android.content.pm.PackageInfo)=pi.signingInfo!!.apkContentsSigners.map{app.bluedotoff.BinderBackend.digest(it.toByteArray())}.sorted()
    private fun validateInstall() {
        val pi=pm().getPackageInfo("app.bluedotoff",PackageManager.GET_SIGNING_CERTIFICATES)
        check(pi.applicationInfo!!.uid==appUid&&pi.applicationInfo!!.sourceDir==apk&&pi.longVersionCode==app.bluedotoff.BuildConfig.VERSION_CODE.toLong()&&pi.lastUpdateTime==appUpdatedAt&&signatures(pi)==signer)
        check(pm().getPackagesForUid(appUid)?.sorted()==listOf("app.bluedotoff"))
    }
    private val server=object:Binder(){
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            if(code==INTERFACE_TRANSACTION){reply?.writeString(WatchProtocol.DESCRIPTOR);return true}
            check(Binder.getCallingUid()==appUid){"Watch caller rejected"}
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            val json=data.readString()?:error("Watch request missing")
            require(json.toByteArray().size<=WatchProtocol.MAX_REQUEST&&data.dataAvail()==0)
            require(code in WatchProtocol.REQUEST..WatchProtocol.STOP&&reply!=null)
            val identity=Binder.clearCallingIdentity()
            try {
                synchronized(operationLock){
                    check(!stopping);validateInstall()
                    val r=JSONObject(json)
                    val result=when(code){
                        WatchProtocol.REQUEST->MinimalPrivilegedHelper.residentRequest(r)
                        WatchProtocol.CONFIGURE->{require(r.keys().asSequence().toSet()==setOf("targets"));configure(r.getJSONArray("targets"));JSONObject().put("configured",true)}
                        else->{require(r.length()==0);stopping=true;main.postDelayed({System.exit(0)},250);JSONObject().put("stopped",true)}
                    }
                    val encoded=result.toString();check(encoded.toByteArray().size<=WatchProtocol.MAX_RESPONSE)
                    reply.writeNoException();reply.writeString(encoded)
                }
            }catch(_:Exception){reply.writeException(SecurityException("WATCH_OPERATION_REJECTED"))}
            finally{Binder.restoreCallingIdentity(identity)}
            return true
        }
    }
    private fun configure(list:JSONArray){
        require(list.length()<=2000)
        val next=mutableMapOf<String,Int>()
        repeat(list.length()){i->val row=list.getJSONObject(i);require(row.keys().asSequence().toSet()==setOf("package","uid"));val p=row.getString("package");val uid=row.getInt("uid");require(p.length<=255&&p.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))&&uid in 0..19999);next[p]=uid}
        val added=next.keys-targets.keys.toSet()
        targets.clear();targets.putAll(next)
        // A package toggle can precede registration while its flag reset arrives several seconds later.
        // Newly registered targets get the same bounded two checks; existing targets are not polled again.
        added.forEach(::changed)
    }
    /** External provider starts/reconnects the ordinary app process without opening an Activity. Token is always removed. */
    private fun deliver(method:String,packages:Set<String> = emptySet()) {
        if(stopping)return
        validateInstall()
        val token=Binder()
        val get=am.javaClass.getMethod("getContentProviderExternal",String::class.java,Int::class.javaPrimitiveType,IBinder::class.java,String::class.java).apply{isAccessible=true}
        val holder=get.invoke(am,WatchProtocol.AUTHORITY,0,token,"bluedotOFF-watch")?:error("Watch provider unavailable")
        try {
            val provider=holder.javaClass.getField("provider").get(holder)
            val extras=Bundle().apply{putBinder("helper",server);putString("profile",proof.toString());putStringArrayList("packages",ArrayList(packages));putString("apk",apk);putLong("version",app.bluedotoff.BuildConfig.VERSION_CODE.toLong())}
            val call=provider.javaClass.methods.single{it.name=="call"&&it.parameterTypes.size==5}.apply{isAccessible=true}
            val source=AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
            val response=call.invoke(provider,source,WatchProtocol.AUTHORITY,method,null,extras) as? Bundle?:error("Watch attach refused")
            check(response.getInt("uid",-1)==appUid)
            if(!response.getBoolean("enabled")){targets.clear();if(!response.getBoolean("draining")){stopping=true;main.post{System.exit(0)}};return}
            configure(JSONArray(response.getString("targets")?:"[]"))
        }finally{
            am.javaClass.getMethod("removeContentProviderExternalAsUser",String::class.java,IBinder::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(am,WatchProtocol.AUTHORITY,token,0)
        }
    }
    private fun send(method:String,packages:Set<String> = emptySet()){callbacks.execute{try{deliver(method,packages)}catch(_:Exception){if(runCatching{validateInstall()}.isFailure){stopping=true;System.exit(0)}}}}
    private fun changed(pkg:String){
        if(!targets.containsKey(pkg)||stopping)return
        // PermissionController may recompute flags asynchronously after PACKAGE_CHANGED. Two bounded checks, no polling.
        main.post{
            val now=SystemClock.uptimeMillis()
            windows.forEachIndexed{i,w->
                val due=w.add(pkg,now);if(due.isNotEmpty())send("event",due)
                main.removeCallbacks(ends[i]);main.postDelayed(ends[i],w.delay)
            }
        }
    }
    /** Resolve and validate the runtime Stub's public generated mapping, without restricted TRANSACTION_* fields. */
    private fun callbackCode(stub:Class<*>,method:String):Int {
        val name=stub.getMethod("getDefaultTransactionName",Int::class.javaPrimitiveType)
        val matches=(IBinder.FIRST_CALL_TRANSACTION until IBinder.FIRST_CALL_TRANSACTION+64).filter{name.invoke(null,it)==method}
        check(matches.size==1){"Watch callback ABI unavailable"};return matches.single()
    }
    /** Android's PackageMonitor now uses this callback; it needs no AMS-registered ApplicationThread.
     * AOSP core/java/com/android/internal/content/PackageMonitor.java and IPackageManager.aidl. */
    private fun packageChanges() {
        val stub=Class.forName("android.os.IRemoteCallback\$Stub")
        val transaction=callbackCode(stub,"sendResult")
        val key=PackageManager::class.java.getField("EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT").get(null) as String
        val b=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            if(code==INTERFACE_TRANSACTION){reply?.writeString("android.os.IRemoteCallback");return true}
            if(code!=transaction)return false
            check(Binder.getCallingUid()==1000)
            data.enforceInterface("android.os.IRemoteCallback")
            val bundle=data.readTypedObject(Bundle.CREATOR)?:return true
            val intent=bundle.getParcelable(key,Intent::class.java)?:return true
            main.post{
                val pkg=intent.data?.schemeSpecificPart?:return@post
                if(pkg=="app.bluedotoff"){
                    if(intent.action in setOf(Intent.ACTION_PACKAGE_REPLACED,Intent.ACTION_PACKAGE_REMOVED)||runCatching{validateInstall()}.isFailure){stopping=true;System.exit(0)}
                }
                else if(intent.action in setOf(Intent.ACTION_PACKAGE_CHANGED,Intent.ACTION_PACKAGE_REPLACED,Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_ADDED))changed(pkg)
            }
            reply?.writeNoException();return true
        }}
        val receiver=stub.getMethod("asInterface",IBinder::class.java).invoke(null,b)?:error("Receiver interface unavailable")
        val type=Class.forName("android.os.IRemoteCallback")
        pm().javaClass.getMethod("registerPackageMonitorCallback",type,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(pm(),receiver,0)
        receivers+=receiver
    }
    private fun foregroundChanges(){
        val stub=Class.forName("android.app.IProcessObserver\$Stub")
        val transaction=callbackCode(stub,"onForegroundActivitiesChanged")
        val b=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{
            if(code==INTERFACE_TRANSACTION){reply?.writeString("android.app.IProcessObserver");return true}
            if(code==transaction){check(Binder.getCallingUid()==1000);data.enforceInterface("android.app.IProcessObserver");data.readInt();val uid=data.readInt();val visible=data.readInt()!=0;if(uid==appUid&&visible)send("attach")}
            return true
        }}
        val observer=stub.getMethod("asInterface",IBinder::class.java).invoke(null,b)?:error("Process observer unavailable")
        am.javaClass.getMethod("registerProcessObserver",Class.forName("android.app.IProcessObserver")).apply{isAccessible=true}.invoke(am,observer)
        receivers+=observer
    }
    private fun permissionChanges():Boolean=runCatching{
        val type=Class.forName("android.content.pm.PackageManager\$OnPermissionsChangedListener")
        val listener=Proxy.newProxyInstance(type.classLoader,arrayOf(type)){_,m,args->
            when(m.name){"onPermissionsChanged"->{val uid=args!![0] as Int;targets.filterValues{it==uid}.keys.forEach(::changed);null};"hashCode"->System.identityHashCode(server);"equals"->false;"toString"->"BlueDotWatchPermissionListener";else->null}}
        pm().javaClass.getMethod("addOnPermissionsChangeListener",type).apply{isAccessible=true}.invoke(pm(),listener)
        permissionListener=listener
    }.isSuccess
    @JvmStatic fun main(args:Array<String>) {
        var phase="PROFILE"
        try{start(args){phase=it}}catch(e:Exception){
            runCatching{proof.put("startupPhase",phase).put("startupError",e.javaClass.simpleName);deliver("failed")}
            System.exit(71)
        }
    }
    private fun start(args:Array<String>,phase:(String)->Unit) {
        check(args.size==1&&Process.myUid()==2000)
        apk=args.single();proof=MinimalPrivilegedHelper.initializeResident(apk)
        main=Handler(Looper.getMainLooper()!!)
        val thread=Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
        context=thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        am=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)?:error("Activity service unavailable")
        val pi=pm().getPackageInfo("app.bluedotoff",PackageManager.GET_SIGNING_CERTIFICATES);appUid=pi.applicationInfo!!.uid;appUpdatedAt=pi.lastUpdateTime;signer=signatures(pi);validateInstall()
        proof.put("watchProtocol",1).put("watchId",java.util.UUID.randomUUID().toString()).put("appUpdatedAt",appUpdatedAt)
        phase("PACKAGE_CALLBACK");packageChanges();phase("PROCESS_CALLBACK");foregroundChanges()
        proof.put("packageEvents",true)
        proof.put("permissionEvents",permissionChanges())
        server.attachInterface(null,WatchProtocol.DESCRIPTOR)
        // An orphan whose initial handshake fails must not remain privileged indefinitely.
        phase("CLIENT_HANDSHAKE");deliver("attach")
        val health=object:Runnable{override fun run(){if(stopping)return;if(runCatching{validateInstall()}.isFailure){System.exit(0);return};send("state");main.postDelayed(this,60_000)}}
        main.postDelayed(health,60_000)
        Looper.loop()
    }
}
