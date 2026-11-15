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
    internal class Connection(val binder:IBinder,val profile:JSONObject,val death:IBinder.DeathRecipient,val sameHelper:Boolean) {
        @Volatile var needsInitialTargets=true
        // Guarded by queueLock. An ordinary timer or startup signal has no helper-held revision.
        val retryOnContact=mutableSetOf<String>()
    }
    @Volatile private var current:Connection?=null
    @Volatile private var attaching=CountDownLatch(1)
    @Volatile private var starting=false
    private var lastStart=0L
    private val worker=Executors.newSingleThreadExecutor()
    private val queueLock=Any()
    private val pending=mutableSetOf<String>()
    private var eventReceipts=WatchReceipts()
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
    // A verified foreground hide or restore also ends the preceding run of failures.
    internal fun clearCompletedRetry(c:Context,pkg:String) {
        runCatching { retry(c).succeeded(pkg) }.onFailure { WatchLog.event("state","retryCleanupFailed") }
    }
    private val retryTimers=HashSet<String>()
    // Interrupted hides settled in this round: their next round skips the 10-minute retry wait once (Fold7 2026-11-13:
    // Toss settled at 01:31 waited until 01:46 with the notice up).
    private val settledNow=HashSet<String>()
    private var initialized=false
    private var states=""
    private var wifiObserver:AutoCloseable?=null
    private val handler by lazy {Handler(Looper.getMainLooper())}
    fun usbEnabled(c:Context)=Settings.Global.getInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)==1
    fun available(c:Context)=BackgroundWatch.enabled(c)&&usbEnabled(c)&&current?.binder?.isBinderAlive==true
    internal fun owns(c:Context,binder:IBinder)=synchronized(queueLock){current?.binder==binder&&available(c)}
    internal fun connected()=current?.binder?.isBinderAlive==true
    internal fun draining(binder:IBinder)=current?.binder==binder&&LocalAdbSession.busy
    private val initLock=Any()
    fun initialize(context:Context){if(!BackgroundWatch.enabled(context))return
        val c=context.applicationContext
        synchronized(initLock){
            if(initialized)return
            synchronized(queueLock){
                if(c.getSharedPreferences("ui-state",0).getBoolean("watchRunning",false)&&current==null)awaitReport(c)
            }
            registerObservers(c)
            synchronized(queueLock){
                val prefs=c.getSharedPreferences("ui-state",0)
                if(BackgroundWatch.enabled(c)&&!usbEnabled(c)&&(prefs.getBoolean("watchRunning",false)||WatchNotification.unconfirmed(c))){
                    clearReportWait()
                    recordAvailability(c,WatchNotification.Reason.USB_DEBUGGING_OFF)
                }
            }
            initialized=true
        }
    }
    private fun registerObservers(c:Context){
        app=c
        BatchRuntime.onIdle=::foregroundIdle
        val observer=object:ContentObserver(handler){
            override fun onChange(selfChange:Boolean){
                state(c)
                if(!BackgroundWatch.enabled(c))return
                if(!usbEnabled(c))stop(c,WatchNotification.Reason.USB_DEBUGGING_OFF)
                else if(!BatchRuntime.state.value.busy)resumeDeferred(c)
            }
        }
        try{
            c.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),false,observer)
            c.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"),false,observer)
            wifiObserver=WifiConnection.observe(c){state(c)}
            state(c)
        }catch(e:Exception){
            runCatching{c.contentResolver.unregisterContentObserver(observer)}
            runCatching{wifiObserver?.close()};wifiObserver=null
            throw e
        }
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
        val ui=c.getSharedPreferences("ui-state",0)
        val sameHelper:Boolean
        val newConnection:Boolean
        val owner:Connection
        synchronized(queueLock){
            // The remote proof can block while USB or the user's watch switch is turned off.
            if(!BackgroundWatch.enabled(c)||!usbEnabled(c))return false
            val previous=current
            newConnection=previous?.binder!=binder
            sameHelper=ui.getString("watchId",null)==profile.getString("watchId")
            if(newConnection){
                // A second launch cannot silently replace an existing live owner.
                if(previous?.binder?.isBinderAlive==true)return false
                val death=IBinder.DeathRecipient{helperDied(c,binder)}
                binder.linkToDeath(death,0)
                if(!BackgroundWatch.enabled(c)||!usbEnabled(c)||!binder.isBinderAlive){
                    runCatching{binder.unlinkToDeath(death,0)}
                    return false
                }
                eventReceipts=WatchReceipts()
                current=Connection(binder,profile,death,sameHelper)
                ui.edit().putString("watchId",profile.getString("watchId")).apply()
                LocalAdbSession.rememberRegion(c,profile)
            }else{
                if(!available(c))return false
            }
            owner=checkNotNull(current)
            val repair=ui.contains("watchStopNotified")||ui.contains("watchUsbBackNotified")
            if(newConnection||repair||!ui.getBoolean("watchRunning",false)){
                if(!sameHelper||contactDelayed||repair)
                    WatchDiagnostics.record(c,if(sameHelper)WatchDiagnostics.Event.ATTACH_SAME else WatchDiagnostics.Event.ATTACH_NEW)
                clearReportWait()
                BackgroundWatch.running(c)
                WatchLog.event("state","watchRunning=true sameHelper=$sameHelper pid=${profile.getInt("pid")} packageEvents=${profile.optBoolean("packageEvents")} permissionEvents=${profile.optBoolean("permissionEvents")}")
                attaching.countDown()
            }else if(!owner.needsInitialTargets&&owner.retryOnContact.isEmpty())return true
        }
        synchronized(owner){
            val targets=if(owner.needsInitialTargets)attachTargets(JournalStore(c).entries(),owner.sameHelper) else emptySet()
            synchronized(queueLock){
                if(current!==owner||!available(c))return false
                val work=targets+owner.retryOnContact
                if(work.isNotEmpty()&&!signal(c,work))return false
                owner.needsInitialTargets=false
                owner.retryOnContact.clear()
            }
        }
        LifecycleEvents.changed()
        return true
    }
    /** What a newly attached helper checks. A new helper (watch start, restart, update): every app it guards. The same helper
     *  finding a revived app process (Android ends the idle process; the helper's 60 s report starts it again, about 90 times
     *  a day on the Fold7, each time re-reading all 214 apps): only apps with work left, since the helper kept watching the
     *  others and reports their changes as events (1.3.2). */
    internal fun attachTargets(entries:List<Entry>,sameHelper:Boolean)=entries.filter{WatchPolicy.eligible(it)&&!(sameHelper&&it.state==TargetState.APPLIED)}.map{it.identity.packageName}.toSet()
    // Like the helper's 60-second heartbeat, Handler delays count awake time. Let a missed heartbeat recover.
    private const val REPORT_CHECK_MS=75_000L
    private var reportCheck:Runnable?=null
    private var contactDelayed=false
    /** All contact transitions, including timeout delivery, are serialized with attach/death. */
    private fun clearReportWait(){
        reportCheck?.let(handler::removeCallbacks)
        reportCheck=null;contactDelayed=false
    }
    private fun awaitReport(c:Context){
        if(reportCheck!=null||c.getSharedPreferences("ui-state",0).contains("watchStopNotified"))return
        val check=object:Runnable{
            override fun run(){
                synchronized(queueLock){
                    if(reportCheck!==this)return
                    if(!BackgroundWatch.enabled(c)||available(c)){clearReportWait();return}
                    if(!usbEnabled(c)){
                        clearReportWait()
                        recordAvailability(c,WatchNotification.Reason.USB_DEBUGGING_OFF)
                    }else if(!contactDelayed){
                        contactDelayed=true
                        WatchDiagnostics.record(c,WatchDiagnostics.Event.CONTACT_DELAYED)
                        handler.postDelayed(this,REPORT_CHECK_MS)
                        return
                    }else{
                        reportCheck=null
                        WatchNotification.contactMissing(c)
                    }
                }
                LifecycleEvents.changed()
            }
        }
        reportCheck=check
        handler.postDelayed(check,REPORT_CHECK_MS)
    }
    internal fun startupFailed(c:Context){
        WatchDiagnostics.record(c,WatchDiagnostics.Event.START_FAILED)
        lost(c)
    }
    internal fun initFailed(c:Context){
        WatchDiagnostics.record(c,WatchDiagnostics.Event.INIT_FAILED)
        lost(c)
    }

    private fun lost(c:Context){
        synchronized(queueLock){
            when{
                !BackgroundWatch.enabled(c)->clearReportWait()
                !usbEnabled(c)->{clearReportWait();recordAvailability(c,WatchNotification.Reason.USB_DEBUGGING_OFF)}
                available(c)->recordAvailability(c)
                else->awaitReport(c)
            }
        }
        LifecycleEvents.changed()
    }

    private fun helperDied(c:Context,binder:IBinder){
        synchronized(queueLock){
            if(current?.binder!=binder)return
            current=null
            clearReportWait()
            WatchDiagnostics.record(c,WatchDiagnostics.Event.BINDER_DIED)
            recordAvailability(c)
        }
        LifecycleEvents.changed()
    }

    /** An update invalidates the old installation, but the new helper can attach before the broadcast arrives. */
    internal fun packageReplaced(c:Context){
        synchronized(queueLock){
            clearReportWait()
            if(BackgroundWatch.enabled(c))WatchDiagnostics.record(c,WatchDiagnostics.Event.APP_UPDATED)
            recordAvailability(c,WatchNotification.Reason.STOPPED)
        }
        LifecycleEvents.changed()
    }

    internal fun bootCompleted(c:Context)=synchronized(queueLock){
        if(available(c))WatchDiagnostics.record(c,WatchDiagnostics.Event.BOOT)
        else {
            clearReportWait()
            WatchNotification.stopped(c,WatchNotification.Reason.BOOT)
        }
    }

    internal fun watchDisabled(c:Context)=synchronized(queueLock){
        if(!BackgroundWatch.enabled(c)){
            clearReportWait()
            WatchNotification.clearDisabled(c)
        }
    }

    /** Caller holds queueLock so a late failure cannot overwrite a verified attachment. */
    private fun recordAvailability(c:Context,reason:WatchNotification.Reason=if(usbEnabled(c))WatchNotification.Reason.STOPPED else WatchNotification.Reason.USB_DEBUGGING_OFF){
        if(available(c))BackgroundWatch.running(c) else {
            state(c)
            WatchNotification.stopped(c,reason)
        }
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
        try{initialize(c)}catch(e:Exception){initFailed(c);return available(c)}
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
            WirelessWork.run(c,true,hold=false,forceWireless=true,noScreen=afterRestart){
                if(!available(c)&&c.getSharedPreferences("ui-state",0).getBoolean("watchRunning",false))
                    WatchDiagnostics.record(c,WatchDiagnostics.Event.RESTART_REQUESTED)
                it.startWatch()
            }
            check(attaching.await(10,TimeUnit.SECONDS)&&available(c)){"Watch helper handshake failed"}
            return true
        }catch(e:Exception){
            WatchLog.event("state","watchStartFailed=${e.javaClass.simpleName}")
            // A refused key (the pairing expired) is not a try either: pairing again seconds later starts at once (Fold7 2026-10-29).
            if((e as? LocalAdbStageException)?.state==LocalAdbState.ADB_AUTHENTICATION_FAILED)synchronized(queueLock){lastStart=0}
            startupFailed(c);return available(c)}
        finally{starting=false}
    }
    /** Drain a started operation, then discard the queue only if the stop is still required. */
    fun stop(c:Context,reason:WatchNotification.Reason=WatchNotification.Reason.STOPPED){
        worker.execute{stopCurrent(c,reason,onlyIfDisabled=true)}
    }
    fun stopNow(c:Context,reason:WatchNotification.Reason=WatchNotification.Reason.STOPPED)=stopCurrent(c,reason,onlyIfDisabled=false)
    private fun stopCurrent(c:Context,reason:WatchNotification.Reason,onlyIfDisabled:Boolean)=LocalAdbSession.exclusive {
        val previous=synchronized(queueLock){
            if(onlyIfDisabled&&BackgroundWatch.enabled(c)&&usbEnabled(c)){
                if(!BatchRuntime.state.value.busy)resumeDeferred(c)
                return@exclusive
            }
            pending.clear();deferred.clear()
            val old=current;current=null;clearReportWait();old
        }
        if(previous!=null){runCatching{timedRpc(previous,WatchProtocol.STOP,JSONObject(),10_000)};runCatching{previous.binder.unlinkToDeath(previous.death,0)}}
        synchronized(queueLock){
            val now=if(reason==WatchNotification.Reason.USB_DEBUGGING_OFF&&usbEnabled(c))WatchNotification.Reason.STOPPED else reason
            recordAvailability(c,now)
        }
    }
    internal fun usbMayBeBack(c:Context)=synchronized(queueLock){
        if(usbEnabled(c)&&BackgroundWatch.enabled(c)&&!available(c))WatchNotification.usbBack(c)
    }
    /** Receiver/provider merely enqueue names. No Binder thread holds a client lock while calling back into the helper. */
    /** First change signal of the current queue (elapsedRealtime), for the B-4 timing line. */
    @Volatile private var firstSignalAt=0L
    @Volatile private var workFailures=0
    /** Apps a watch run read to an end (still hidden, hidden again, or left for a reason); guarded by [queueLock]. An app
     *  skipped by the retry pause, put off for the app's own work or not reached is not here (GPT rc11 review). */
    private val checked=HashSet<String>()
    /** Widget "지금 확인" (owner 2026-10-29): every app the watch guards goes through the same check as after a change
     *  event (an app still hidden is only read). [done] gets true only when every one of them was read to an end; an app
     *  waiting for its retry is not read here, so it keeps [done] false (Codex review 2026-11-06). */
    fun checkNow(c:Context,done:(Boolean)->Unit){
        val eligible=JournalStore(c).entries().filter(WatchPolicy::eligible)
        val names=eligible.filterNot(WatchPolicy::retrying).map{it.identity.packageName}.toSet()
        val waiting=eligible.any(WatchPolicy::retrying)
        if(!available(c)){done(false);return}
        val failures=workFailures
        synchronized(queueLock){checked-=names}
        signal(c,names)
        // The single worker runs this after the queue that holds [names].
        worker.execute{done(!waiting&&available(c)&&workFailures==failures&&synchronized(queueLock){checked.containsAll(names)})}
    }
    internal fun receiveEvents(c:Context,binder:IBinder,packet:Map<String,Long>,eligible:Set<String>):WatchReceipt=synchronized(queueLock){
        if(current?.binder!=binder||!available(c))return WatchReceipt()
        val work=eventReceipts.receive(packet,eligible)
        if(work.isNotEmpty())signal(c,work)
        eventReceipts.receipt(packet,eligible)
    }
    internal fun signal(c:Context,packages:Set<String>):Boolean {
        if(!available(c))return false
        synchronized(queueLock){if(pending.isEmpty())firstSignalAt=SystemClock.elapsedRealtime();pending+=packages;if(queued)return true;queued=true}
        worker.execute{
            var inFlight=emptySet<String>()
            try{
                while(true){
                    val signalAt=synchronized(queueLock){firstSignalAt};val workerAt=SystemClock.elapsedRealtime()
                    val (names,receipts,revisions)=synchronized(queueLock){
                        val batch=pending.toSet()
                        pending.clear()
                        Triple(batch,eventReceipts,eventReceipts.starting(batch))
                    }
                    inFlight=names
                    if(names.isEmpty())break
                    if(!availableForQueued(c,names)){runCatching{ReapplyNotification.watchFinished(c,names)};break}
                    exclusive(c){session->
                        val lockAt=SystemClock.elapsedRealtime()
                        if(BatchRuntime.state.value.busy){synchronized(queueLock){deferred+=names};return@exclusive}
                        session.run{active->
                            val handled=mutableSetOf<String>()
                            val errors=mutableSetOf<String>()
                            val settled=mutableSetOf<String>()
                            val needsTimer=mutableSetOf<String>()
                            try {
                                val readyAt=SystemClock.elapsedRealtime();val firstRead=HashMap<String,Long>();val rewrote=HashSet<String>()
                                val started=SystemClock.elapsedRealtime()
                                val wall=System.currentTimeMillis()
                                val confirmedWrites=HashSet<String>()
                                val entries=JournalStore(c).entries()
                                val retrying=entries.filter(WatchPolicy::retrying).associateBy{it.identity.packageName}
                                val allowed=names.filter{p->cooldown.allowed(p,started)&&retrying[p]?.let{retry(c).allowed(it,wall,synchronized(queueLock){settledNow.remove(p)})}!=false}.toSet()
                                if(revisions.isNotEmpty()){
                                    // A restored/incomplete group has no unit. Journal-owned retries keep
                                    // their existing timer and budget instead of running on each heartbeat.
                                    val units=WatchPolicy.units(entries,revisions.keys).flatten().mapTo(mutableSetOf()){it.identity.packageName}
                                    handled+=revisions.keys-units
                                    needsTimer+=(revisions.keys intersect retrying.keys)-allowed
                                }
                                // A temporary permission mismatch or an interrupted check is not a success.
                                for (entry in entries) {
                                    if (entry.identity.packageName in names && (entry.restoreRequested || !entry.enabled ||
                                            entry.state==TargetState.APPLIED && entry.settlingVerified))
                                        retry(c).succeeded(entry.identity.packageName)
                                }
                                WatchEngine(active,JournalStore(c),System::currentTimeMillis,{BootSession.current(c)},observe={phase,s,wrote->if(wrote)rewrote+=s.identity.packageName;active.recordVerification(phase,s,wrote)},
                                    onSkip={pkgs,reason->
                                        if(reason==WatchEngine.INTERRUPTED)settled+=pkgs else {
                                            handled+=pkgs
                                            synchronized(queueLock){checked+=pkgs}
                                        }
                                        WatchLog.event("skip","pkg=${pkgs.joinToString(",")} reason=$reason")
                                    },
                                    beforeAttempt={entries->entries.filter(WatchPolicy::retrying).forEach{retry(c).tried(it.identity.packageName,System.currentTimeMillis())}},
                                    onReread={pkgs,matched->WatchLog.event("reread","pkg=${pkgs.joinToString(",")} matched=$matched")},
                                    onRead={pkgs->pkgs.forEach{firstRead.putIfAbsent(it,SystemClock.elapsedRealtime())}})
                                    .reapply(allowed,{availableForQueued(c,names)&&!BatchRuntime.state.value.busy},
                                        {_->},
                                        {result->val doneAt=SystemClock.elapsedRealtime();cooldown.result(result.packageName,doneAt,result.success)
                                            if(result.entry?.let(WatchPolicy::retrying)==true)needsTimer+=result.packageName
                                            else if(result.entry!=null)handled+=result.packageName
                                            else if(available(c)&&!BatchRuntime.state.value.busy)errors+=result.packageName
                                            if(result.success&&result.packageName in rewrote)confirmedWrites+=result.packageName
                                            if(result.success){retry(c).succeeded(result.packageName);StatusWidget.watchConfirmed(c)}
                                            synchronized(queueLock){if(result.success)checked+=result.packageName else checked-=result.packageName}
                                            WatchLog.event("timing","pkg=${result.packageName} ok=${result.success} toWorker=${workerAt-signalAt}ms lockWait=${lockAt-workerAt}ms connect=${readyAt-lockAt}ms checks=${doneAt-(firstRead[result.packageName]?:readyAt)}ms total=${doneAt-signalAt}ms")
                                            ReapplyHistoryStore(c).record(result,ReapplySource.BACKGROUND,rewrote=result.packageName in rewrote)})
                                if(firstRead.isNotEmpty())WatchDiagnostics.watchWork(c,firstRead.size,confirmedWrites.size)
                                // Every app of this round, not only those with a result: the watch passes over some without one
                                // (an old record it cannot take, the retry delay, a check cut short; Claude review 2026-10-31).
                                // An interrupted hide just settled as "hide again" goes straight into the next round (no notice in between).
                                val again=JournalStore(c).entries().filter{WatchPolicy.retrying(it)&&it.identity.packageName in settled}.map{it.identity.packageName}.toSet()
                                if(again.isNotEmpty())synchronized(queueLock){pending+=again;settledNow+=again}
                                handled+=settled-again
                                runCatching{ReapplyNotification.watchFinished(c,names-again)}
                                retryLater(c,names-again)
                                handled+=needsTimer-again
                                // The engine can stop after one target fails without throwing to this scope.
                                // Replay only eligible, unstarted work without a helper-owned revision.
                                val unfinished=WatchPolicy.units(entries,allowed).flatten().map{it.identity.packageName}.toSet()-revisions.keys-handled-errors-needsTimer-settled
                                synchronized(queueLock){
                                    if(receipts===eventReceipts)current?.retryOnContact?.addAll(unfinished)
                                }
                            } catch(e:Exception){
                                synchronized(queueLock){
                                    if(receipts===eventReceipts)current?.retryOnContact?.addAll(names-revisions.keys-handled-errors)
                                }
                                throw e
                            } finally {
                                // A journal error can happen before the first read or after a target finished.
                                // Keep each observed outcome; only an actual target failure spends its budget.
                                if(revisions.isNotEmpty())synchronized(queueLock){receipts.finish(revisions,handled,errors)}
                            }
                        }
                    }
                    LifecycleEvents.changed()
                }
            }catch(e:Exception){availableForQueued(c,inFlight);workFailures++;WatchLog.event("state","watchWorkFailed=${e.javaClass.simpleName}");runCatching{ReapplyNotification.show(c,null,checkInApp=true)};if(current?.binder?.isBinderAlive!=true)lost(c)}
            finally{synchronized(queueLock){queued=false};val remaining=synchronized(queueLock){pending.toSet()};if(remaining.isNotEmpty()&&!signal(c,remaining))runCatching{ReapplyNotification.watchFinished(c,remaining)}}
        }
        return true
    }
    private fun availableForQueued(c:Context,names:Set<String>):Boolean=synchronized(queueLock){
        if(!BackgroundWatch.enabled(c)||!connected())return false
        if(usbEnabled(c))return true
        deferred+=names
        false
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
            val posted=handler.postDelayed({
                synchronized(queueLock){
                    retryTimers-=pkg
                    if(!signal(c,setOf(pkg))&&BackgroundWatch.enabled(c))current?.retryOnContact?.add(pkg)
                }
            },next)
            if(!posted){synchronized(queueLock){retryTimers-=pkg};error("RETRY_TIMER_NOT_SCHEDULED")}
        }
    }
    internal fun reportState(c:Context){state(c)}
    fun startFromSwitch(c:Context,done:(Boolean)->Unit){worker.execute{done(ensureStarted(c,true))}}
    fun foregroundIdle(){app?.let(::resumeDeferred)}
    private fun resumeDeferred(c:Context)=synchronized(queueLock){
        if(deferred.isNotEmpty()&&signal(c,deferred.toSet()))deferred.clear()
    }
}
