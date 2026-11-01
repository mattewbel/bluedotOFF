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
/** B-3: a live helper that stopped answering a read or control call within its deadline. */
class UnresponsiveHelperException:java.io.IOException(WatchClient.UNRESPONSIVE)

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
    private var retryPolicy:WatchRetry?=null
    private fun retry(c:Context)=retryPolicy?:WatchRetry(object:WatchRetry.Store{
        private val prefs=c.applicationContext.getSharedPreferences("watch-retry",Context.MODE_PRIVATE)
        override fun load(pkg:String)=prefs.getString(pkg,null)?.split(',')?.mapNotNull{it.toLongOrNull()}?:emptyList()
        override fun save(pkg:String,times:List<Long>){check(prefs.edit().apply{if(times.isEmpty())remove(pkg) else putString(pkg,times.joinToString(","))}.commit()){"RETRY_RECORD_SAVE_FAILED"}}
    }).also{retryPolicy=it}
    private val retryTimers=HashSet<String>()
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
        LocalAdbPolicy.validateProfile(profile.getBoolean("abiVerified"),profile.getString("fingerprint"),Build.FINGERPRINT)
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
        synchronized(queueLock){
            // A wireless start can fail just after a PC-launched helper attached. Its late failure is not a stop.
            if(available(c))BackgroundWatch.running(c) else {
                c.getSharedPreferences("ui-state",0).edit().putBoolean("watchRunning",false).apply()
                state(c)
                WatchNotification.stopped(c,if(!usbEnabled(c))WatchNotification.Reason.USB_DEBUGGING_OFF else WatchNotification.Reason.STOPPED)
            }
        }
        LifecycleEvents.changed()
    }
    internal fun config(c:Context)=JSONArray(JournalStore(c).entries().filter(WatchPolicy::eligible).map{e->JSONObject().put("package",e.identity.packageName).put("uid",e.identity.uid)})
    const val UNRESPONSIVE="백그라운드 감시가 응답하지 않아요. 휴대폰을 다시 시작한 뒤 Wi-Fi에 연결된 상태에서 bluedotOFF를 열거나, PC에서 시작 명령을 다시 실행해주세요."
    private val rpcThreads=java.util.concurrent.Executors.newCachedThreadPool()
    /** B-3: a helper that is alive but silent must not hold the app's session forever. Reads and control calls get a
     *  deadline; a permission write never does (it is never retried elsewhere, so a late answer cannot write twice).
     *  The stuck call stays on its own thread; the caller gets a clear error instead of waiting. */
    private fun timedRpc(connection:Connection,code:Int,request:JSONObject,ms:Long):JSONObject {
        val call=rpcThreads.submit<JSONObject>{rpc(connection,code,request)}
        return try{call.get(ms,java.util.concurrent.TimeUnit.MILLISECONDS)}
        catch(_:java.util.concurrent.TimeoutException){WatchLog.event("state","helperUnresponsive code=$code op=${request.optString("op")}");throw UnresponsiveHelperException()}
        catch(e:java.util.concurrent.ExecutionException){throw e.cause?:e}
    }
    private fun isRead(request:JSONObject)=request.optString("op").let{it.startsWith("GET_")||it in setOf("PROFILE","HEALTH")}
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
                try{if(isRead(request))timedRpc(connection,WatchProtocol.REQUEST,request,30_000) else rpc(connection,WatchProtocol.REQUEST,request)}catch(e:SecurityException){throw HelperOperationRejectedException()}
            })}finally{if(current===connection&&available(c))runCatching{timedRpc(connection,WatchProtocol.CONFIGURE,JSONObject().put("targets",config(c)),10_000)} }
        }
    }
    internal fun <T> exclusive(c:Context,block:(Session)->T):T=LocalAdbSession.exclusive {
        val connection=current?:error("Watch helper unavailable")
        check(available(c))
        block(Session(c,connection))
    }
    /** Start only during a visible app visit, through the existing authenticated wireless path. Never turn USB ON. */
    /** [afterRestart]: [RestartRecoveryJob] may start the watch without a visible screen (R-1); everything else needs one. */
    fun ensureStarted(c:Context,userAction:Boolean=false,afterRestart:Boolean=false):Boolean {
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
            // A start blocked before any connection is not a try: Wi-Fi turned on seconds later must not wait 30 s (owner 2026-10-26).
            if(!(ForegroundState.active||afterRestart)||!WifiConnection.connected(c)){WatchLog.event("state","watchStartBlocked foreground=${ForegroundState.active} wifiConnected=${WifiConnection.connected(c)}");synchronized(queueLock){lastStart=0};lost(c);return available(c)}
            WirelessWork.run(c,true,hold=false,forceWireless=true,afterRestart=afterRestart){it.startWatch()}
            check(attaching.await(10,TimeUnit.SECONDS)&&available(c)){"Watch helper handshake failed"}
            return true
        }catch(e:Exception){
            WatchLog.event("state","watchStartFailed=${e.javaClass.simpleName}")
            // A refused key (the pairing expired) is not a try either: pairing again seconds later starts at once (Fold7 2026-10-29).
            if((e as? LocalAdbStageException)?.state==LocalAdbState.ADB_AUTHENTICATION_FAILED)synchronized(queueLock){lastStart=0}
            lost(c);return available(c)}
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
        if(previous!=null){runCatching{timedRpc(previous,WatchProtocol.STOP,JSONObject(),10_000)};runCatching{previous.binder.unlinkToDeath(previous.death,0)}}
        c.getSharedPreferences("ui-state",0).edit().putBoolean("watchRunning",false).commit()
        state(c);WatchNotification.stopped(c,reason)
    }
    /** Receiver/provider merely enqueue names. No Binder thread holds a client lock while calling back into the helper. */
    /** First change signal of the current queue (elapsedRealtime), for the B-4 timing line. */
    @Volatile private var firstSignalAt=0L
    @Volatile private var workFailures=0
    /** Apps a watch run read to an end (still hidden, hidden again, or left for a reason); guarded by [queueLock]. An app
     *  skipped by the retry pause, put off for the app's own work or not reached is not here (GPT rc11 review). */
    private val checked=HashSet<String>()
    /** Widget "지금 확인" (owner 2026-10-29): every app the watch guards goes through the same check as after a change
     *  event (an app still hidden is only read). [done] gets true only when every one of them was read to an end. */
    fun checkNow(c:Context,done:(Boolean)->Unit){
        val names=JournalStore(c).entries().filter{WatchPolicy.eligible(it)&&!WatchPolicy.retrying(it)}.map{it.identity.packageName}.toSet()
        if(!available(c)){done(false);return}
        val failures=workFailures
        synchronized(queueLock){checked-=names}
        signal(c,names)
        // The single worker runs this after the queue that holds [names].
        worker.execute{done(available(c)&&workFailures==failures&&synchronized(queueLock){checked.containsAll(names)})}
    }
    internal fun signal(c:Context,packages:Set<String>):Boolean {
        if(!available(c))return false
        synchronized(queueLock){if(pending.isEmpty())firstSignalAt=SystemClock.elapsedRealtime();pending+=packages;if(queued)return true;queued=true}
        worker.execute{
            try{
                while(true){
                    val signalAt=synchronized(queueLock){firstSignalAt};val workerAt=SystemClock.elapsedRealtime()
                    val names=synchronized(queueLock){pending.toSet().also{pending.clear()}}
                    if(names.isEmpty())break
                    if(!available(c)){runCatching{ReapplyNotification.watchFinished(c,names)};break}
                    exclusive(c){session->
                        val lockAt=SystemClock.elapsedRealtime()
                        if(BatchRuntime.state.value.busy){synchronized(queueLock){deferred+=names};return@exclusive}
                        session.run{active->
                            val readyAt=SystemClock.elapsedRealtime();val firstRead=HashMap<String,Long>();val rewrote=HashSet<String>()
                            val started=SystemClock.elapsedRealtime()
                            val wall=System.currentTimeMillis()
                            val retrying=JournalStore(c).entries().filter(WatchPolicy::retrying).associateBy{it.identity.packageName}
                            val allowed=names.filter{p->cooldown.allowed(p,started)&&retrying[p]?.let{retry(c).allowed(it,wall)}!=false}.toSet()
                            (names-retrying.keys).forEach{retry(c).succeeded(it)}
                            WatchEngine(active,JournalStore(c),System::currentTimeMillis,{BootSession.current(c)},observe={phase,s,wrote->firstRead.putIfAbsent(s.identity.packageName,SystemClock.elapsedRealtime());if(wrote)rewrote+=s.identity.packageName;active.recordVerification(phase,s,wrote)},
                                onSkip={pkgs,reason->synchronized(queueLock){checked+=pkgs};WatchLog.event("skip","pkg=${pkgs.joinToString(",")} reason=$reason")},
                                beforeAttempt={entries->entries.filter(WatchPolicy::retrying).forEach{retry(c).tried(it.identity.packageName,System.currentTimeMillis())}})
                                .reapply(allowed,{available(c)&&!BatchRuntime.state.value.busy},
                                    {_->},
                                    {result->val doneAt=SystemClock.elapsedRealtime();cooldown.result(result.packageName,doneAt,result.success)
                                        if(result.success)retry(c).succeeded(result.packageName)
                                        synchronized(queueLock){if(result.success)checked+=result.packageName else checked-=result.packageName}
                                        WatchLog.event("timing","pkg=${result.packageName} ok=${result.success} toWorker=${workerAt-signalAt}ms lockWait=${lockAt-workerAt}ms connect=${readyAt-lockAt}ms checks=${doneAt-(firstRead[result.packageName]?:readyAt)}ms total=${doneAt-signalAt}ms")
                                        ReapplyHistoryStore(c).record(result,ReapplySource.BACKGROUND,rewrote=result.packageName in rewrote)})
                            // Every app of this round, not only those with a result: the watch passes over some without one
                            // (an old record it cannot take, the retry delay, a check cut short; Claude review 2026-10-31).
                            runCatching{ReapplyNotification.watchFinished(c,names)}
                            retryLater(c,names)
                        }
                    }
                    LifecycleEvents.changed()
                }
            }catch(e:Exception){workFailures++;WatchLog.event("state","watchWorkFailed=${e.javaClass.simpleName}");runCatching{ReapplyNotification.show(c,null,checkInApp=true)};if(current?.binder?.isBinderAlive!=true)lost(c)}
            finally{synchronized(queueLock){queued=false};val remaining=synchronized(queueLock){pending.toSet()};if(remaining.isNotEmpty()&&!signal(c,remaining))runCatching{ReapplyNotification.watchFinished(c,remaining)}}
        }
        return true
    }
    /** A hide that failed safely is tried again later even without a new event (owner 2026-10-31). A killed app process
     *  drops the timer; the next event, watch start or app visit tries again. */
    private fun retryLater(c:Context,names:Set<String>){
        val now=System.currentTimeMillis()
        JournalStore(c).entries().filter{WatchPolicy.retrying(it)&&it.identity.packageName in names}.forEach{e->
            val pkg=e.identity.packageName;val delay=retry(c).wait(e,now)?:return@forEach
            if(!synchronized(queueLock){retryTimers.add(pkg)})return@forEach
            // A due retry skipped before an attempt spends no quota, but must not become a 30-second polling loop.
            val next=if(delay==0L)10*60_000L else maxOf(delay,30_000L)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({synchronized(queueLock){retryTimers-=pkg};signal(c,setOf(pkg))},next)
        }
    }
    internal fun reportState(c:Context){state(c)}
    fun startFromSwitch(c:Context,done:(Boolean)->Unit){worker.execute{done(ensureStarted(c,true))}}
    fun foregroundIdle(){val c=app?:return;val names=synchronized(queueLock){deferred.toSet().also{deferred.clear()}};if(names.isNotEmpty())signal(c,names)}
}
