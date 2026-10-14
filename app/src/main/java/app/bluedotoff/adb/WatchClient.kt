package app.bluedotoff.adb

import android.content.Context
import android.database.ContentObserver
import android.os.*
import android.provider.Settings
import app.bluedotoff.*
import app.bluedotoff.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One app process, one operation owner shared with Local ADB. The privileged daemon never owns journal data. */
object WatchClient {
    internal data class Connection(val binder:IBinder,val profile:JSONObject,val death:IBinder.DeathRecipient)
    @Volatile private var current:Connection?=null
    @Volatile private var attaching=CountDownLatch(1)
    @Volatile private var starting=false
    private var lastStart=0L
    private val worker=Executors.newSingleThreadExecutor()
    private val queueLock=Any()
    private val pending=mutableSetOf<String>()
    private val deferred=mutableSetOf<String>()
    private var app:Context?=null
    private var queued=false
    private val cooldown=WatchCooldown()
    private var initialized=false
    private var states=""
    private var wifiObserver:AutoCloseable?=null
    private val handler by lazy {Handler(Looper.getMainLooper())}
    fun usbEnabled(c:Context)=Settings.Global.getInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)==1
    fun available(c:Context)=BackgroundWatch.enabled(c)&&usbEnabled(c)&&current?.binder?.isBinderAlive==true
    internal fun connected()=current?.binder?.isBinderAlive==true
    internal fun draining(binder:IBinder)=current?.binder==binder&&LocalAdbSession.busy
    fun initialize(context:Context){if(!BackgroundWatch.enabled(context))return;synchronized(queueLock){if(initialized)return;initialized=true}
        val c=context.applicationContext
        app=c
        BatchRuntime.onIdle=::foregroundIdle
        val observer=object:ContentObserver(handler){override fun onChange(selfChange:Boolean){state(c);if(BackgroundWatch.enabled(c)&&!usbEnabled(c))stop(c,WatchNotification.Reason.USB_DEBUGGING_OFF)}}
        c.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),false,observer)
        c.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"),false,observer)
        wifiObserver=WifiConnection.observe(c){state(c)}
        state(c)
        handler.postDelayed({if(BackgroundWatch.enabled(c)&&current==null&&!starting&&c.getSharedPreferences("ui-state",0).getBoolean("watchRunning",false))lost(c)},5000)
    }
    private fun state(c:Context){
        val next="usbDebugging=${if(usbEnabled(c))1 else 0} wirelessDebugging=${Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled",0)} wifi=${Settings.Global.getInt(c.contentResolver,"wifi_on",0)} wifiConnected=${if(WifiConnection.connected(c))1 else 0} watchRunning=${available(c)}"
        synchronized(queueLock){if(next==states)return;states=next}
        WatchLog.event("state",next)
    }
    /** Called only by the protected provider, with Binder shell UID already checked. */
    internal fun attach(c:Context,binder:IBinder,profile:JSONObject,apk:String,version:Long):Boolean {
        if(!BackgroundWatch.enabled(c)||!usbEnabled(c))return false
        require(apk==c.applicationInfo.sourceDir&&version==BuildConfig.VERSION_CODE.toLong())
        @Suppress("DEPRECATION")
        require(profile.getLong("appUpdatedAt")==c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        require(profile.getInt("watchProtocol")==1&&profile.getString("watchId").isNotBlank()&&binder.interfaceDescriptor==WatchProtocol.DESCRIPTOR)
        LocalAdbPolicy.validateShell(profile.getInt("uid"),profile.getString("selinux"))
        LocalAdbPolicy.validateProfile(profile.getBoolean("abiVerified"),profile.getBoolean("historyAbiVerified"),profile.getBoolean("indicatorAbiVerified"),profile.getString("fingerprint"),Build.FINGERPRINT)
        synchronized(queueLock){
            val previous=current
            if(previous?.binder==binder)return true
            // A second launch cannot silently replace an existing live owner (no competing daemons).
            if(previous?.binder?.isBinderAlive==true)return false
            val death=IBinder.DeathRecipient{if(current?.binder==binder){current=null;lost(c)}}
            binder.linkToDeath(death,0)
            current=Connection(binder,profile,death)
            LocalAdbSession.rememberRegion(c,profile)
            BackgroundWatch.running(c)
            WatchLog.event("state","watchRunning=true pid=${profile.getInt("pid")} packageEvents=${profile.optBoolean("packageEvents")} permissionEvents=${profile.optBoolean("permissionEvents")}")
            attaching.countDown()
        }
        signal(c,JournalStore(c).entries().filter(WatchPolicy::eligible).map{it.identity.packageName}.toSet())
        LifecycleEvents.changed()
        return true
    }
    private fun lost(c:Context){
        c.getSharedPreferences("ui-state",0).edit().putBoolean("watchRunning",false).apply()
        state(c)
        WatchNotification.stopped(c,if(!usbEnabled(c))WatchNotification.Reason.USB_DEBUGGING_OFF else WatchNotification.Reason.STOPPED)
        LifecycleEvents.changed()
    }
    internal fun config(c:Context)=JSONArray(JournalStore(c).entries().filter(WatchPolicy::eligible).map{e->JSONObject().put("package",e.identity.packageName).put("uid",e.identity.uid)})
    private fun rpc(connection:Connection,code:Int,request:JSONObject):JSONObject {
        val encoded=request.toString();require(encoded.toByteArray().size<=WatchProtocol.MAX_REQUEST)
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try{
            data.writeInterfaceToken(WatchProtocol.DESCRIPTOR);data.writeString(encoded)
            check(connection.binder.transact(code,data,reply,0));reply.readException()
            val text=reply.readString()?:error("Watch response missing");check(text.toByteArray().size<=WatchProtocol.MAX_RESPONSE&&reply.dataAvail()==0)
            return JSONObject(text)
        }finally{reply.recycle();data.recycle()}
    }
    internal class Session(private val c:Context,private val connection:Connection) {
        fun <T> run(block:(LocalAdbBackend)->T):T {
            check(current===connection&&available(c)&&LocalAdbSession.busy)
            return try{block(LocalAdbBackend(c,connection.profile){request->
                check(current===connection&&connection.binder.isBinderAlive)
                try{rpc(connection,WatchProtocol.REQUEST,request)}catch(e:SecurityException){throw HelperOperationRejectedException()}
            })}finally{if(current===connection&&available(c))runCatching{rpc(connection,WatchProtocol.CONFIGURE,JSONObject().put("targets",config(c)))} }
        }
    }
    internal fun <T> exclusive(c:Context,block:(Session)->T):T=LocalAdbSession.exclusive {
        val connection=current?:error("Watch helper unavailable")
        check(available(c))
        block(Session(c,connection))
    }
    /** Start only during a visible app visit, through the existing authenticated wireless path. Never turn USB ON. */
    fun ensureStarted(c:Context,userAction:Boolean=false):Boolean {
        if(!BackgroundWatch.enabled(c))return false
        initialize(c)
        if(available(c))return true
        if(!usbEnabled(c)){lost(c);return false}
        synchronized(queueLock){
            val now=SystemClock.elapsedRealtime()
            if(starting||(!userAction&&lastStart>0&&now-lastStart<30_000))return false
            starting=true;lastStart=now;attaching=CountDownLatch(1)
        }
        try{
            if(attaching.await(500,TimeUnit.MILLISECONDS)&&available(c))return true
            if(!ForegroundState.active||!WifiConnection.connected(c)){WatchLog.event("state","watchStartBlocked foreground=${ForegroundState.active} wifiConnected=${WifiConnection.connected(c)}");lost(c);return false}
            WirelessWork.run(c,true,hold=false,forceWireless=true){it.startWatch()}
            check(attaching.await(10,TimeUnit.SECONDS)&&available(c)){"Watch helper handshake failed"}
            return true
        }catch(e:Exception){WatchLog.event("state","watchStartFailed=${e.javaClass.simpleName}");lost(c);return false}
        finally{starting=false}
    }
    /** Invalidate pending automatic work immediately; drain an already-started operation before ending its helper. */
    fun stop(c:Context,reason:WatchNotification.Reason=WatchNotification.Reason.STOPPED){
        synchronized(queueLock){pending.clear();deferred.clear()}
        worker.execute{if(!BackgroundWatch.enabled(c)||!usbEnabled(c))stopNow(c,reason)}
    }
    fun stopNow(c:Context,reason:WatchNotification.Reason=WatchNotification.Reason.STOPPED)=LocalAdbSession.exclusive {
        val previous=current
        current=null
        if(previous!=null){runCatching{rpc(previous,WatchProtocol.STOP,JSONObject())};runCatching{previous.binder.unlinkToDeath(previous.death,0)}}
        c.getSharedPreferences("ui-state",0).edit().putBoolean("watchRunning",false).commit()
        state(c);WatchNotification.stopped(c,reason)
    }
    /** Receiver/provider merely enqueue names. No Binder thread holds a client lock while calling back into the helper. */
    internal fun signal(c:Context,packages:Set<String>){
        if(!available(c))return
        synchronized(queueLock){pending+=packages;if(queued)return;queued=true}
        worker.execute{
            try{
                while(true){
                    val names=synchronized(queueLock){pending.toSet().also{pending.clear()}}
                    if(names.isEmpty()||!available(c))break
                    exclusive(c){session->
                        if(BatchRuntime.state.value.busy){synchronized(queueLock){deferred+=names};return@exclusive}
                        session.run{active->
                            val allowed=names.filter{cooldown.allowed(it,SystemClock.elapsedRealtime())}.toSet()
                            WatchEngine(active,JournalStore(c),System::currentTimeMillis,{BootSession.current(c)},observe=active::recordVerification)
                                .reapply(allowed,{available(c)&&!BatchRuntime.state.value.busy},
                                    {_->},
                                    {result->cooldown.result(result.packageName,SystemClock.elapsedRealtime(),result.success)
                                        ReapplyHistoryStore(c).record(result,ReapplySource.BACKGROUND)
                                        if(result.success)c.getSystemService(android.app.NotificationManager::class.java).cancel(result.packageName.hashCode())})
                        }
                    }
                    LifecycleEvents.changed()
                }
            }catch(e:Exception){WatchLog.event("state","watchWorkFailed=${e.javaClass.simpleName}");if(current?.binder?.isBinderAlive!=true)lost(c)}
            finally{synchronized(queueLock){queued=false};val remaining=synchronized(queueLock){pending.toSet()};if(remaining.isNotEmpty())signal(c,remaining)}
        }
    }
    internal fun reportState(c:Context){state(c)}
    fun startFromSwitch(c:Context,done:(Boolean)->Unit){worker.execute{done(ensureStarted(c,true))}}
    fun foregroundIdle(){val c=app?:return;val names=synchronized(queueLock){deferred.toSet().also{deferred.clear()}};if(names.isNotEmpty())signal(c,names)}
}
