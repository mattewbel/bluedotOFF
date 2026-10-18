package app.bluedotoff

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.bluedotoff.core.*
import kotlinx.coroutines.*
import app.bluedotoff.adb.*
import android.provider.Settings

class MainActivity : ComponentActivity() {
    private lateinit var backend: BinderBackend
    private lateinit var store: JournalStore
    private lateinit var engine: SafetyEngine
    private var model by mutableStateOf(UiModel())
    private var handledBatchId=0L
    private var skipNotice=""
    private var refreshJob: Job? = null
    private var reapplyHistoryJob:Job?=null
    private var discoveryJob: Job? = null
    private var discoveryGeneration=0
    private var pickerJob:Job?=null
    private var scanOpened=0L
    private var pickerOpened=0L
    private var scanFrameRecorded=false
    private var interactionOpened=0L
    private var interactionPhase=""
    private var pickerRevision by mutableLongStateOf(0L)
    private lateinit var discovery:LocalAdbDiscovery
    private var endpointPort:Int?=null
    @Volatile private var cleanupNotice=""
    @Volatile private var scanStamp:ScanStamp?=null
    private var startupScanAdopted=false
    private var sharedGeneration=0L
    private var initialScopePreviewShown=false
    @Volatile private var packageEpoch=0L
    /** Packages Android reported as installed, updated, removed or with toggled components since they were last read. */
    private val changedPackages=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val clock=RecheckClock(10*60_000L)
    private var lastHistoryElapsed=0L
    private var checkOnResume=false
    private val BIG_GROUP=10
    private var wifiObserver:AutoCloseable?=null
    private val controls by lazy {WirelessControls(this)}
    private val scopes by lazy {ScopePreferences(this)}
    private val packageChanges=object:android.content.BroadcastReceiver(){override fun onReceive(c:android.content.Context,i:Intent){
        i.data?.schemeSpecificPart?.let{changedPackages+=it}
        // A component toggle (e.g. Toss) can reset the hide flag but does not change the app inventory.
        if(i.action==Intent.ACTION_PACKAGE_CHANGED)return
        invalidateScan();if(!model.busy)model=model.copy(discovered=model.discovered.map{it.copy(check=CandidateCheck.NEEDS_CHECK)})}}
    private fun invalidateScan(){StartupScanHandoff.cache.invalidate();startupScanAdopted=false;packageEpoch++;scanStamp=null;model=model.copy(pickerRows=null,rejectedGroups=emptySet())}
    private fun cacheValid()=scanStamp?.valid(Build.FINGERPRINT,packageEpoch,android.os.SystemClock.elapsedRealtime())==true
    private fun automaticReady()=WatchClient.available(this)||controls.consent&&controls.granted&&Settings.Global.getInt(contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1&&WifiConnection.connected(this)&&getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(this).exists
    private fun refreshFromUser(){invalidateScan();refresh(true)}
    private enum class Check { AUTO, LOCAL, REGISTERED, HISTORY, FULL }
    /** Coming back to the app reconnects only when needed: Android reported a change to a registered app, the last check
     *  is older than ten minutes ([RecheckClock]), or the app is not ready yet. Every write still re-reads its target first. */
    private fun resumeCheck(){
        val registered=model.entries.filter{it.enabled}.flatMap{e->listOf(e.identity.packageName)+e.groupIdentities.map{it.packageName}}.toSet()
        val touched=synchronized(changedPackages){changedPackages.filter{it in registered}.toSet()}
        val plan=clock.plan(android.os.SystemClock.elapsedRealtime(),touched)
        when{
            checkOnResume||model.gate!=Gate.READY||!clock.started->{checkOnResume=false;refresh(true)}
            plan==null->refresh(true,Check.LOCAL)
            else->refresh(true,Check.REGISTERED,plan)
        }
    }
    /** Home refresh icon: all registered apps, not the whole app inventory. */
    private fun recheck()=refresh(true,Check.REGISTERED)
    private val prefs by lazy { getSharedPreferences("ui-state", MODE_PRIVATE) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model = model.copy(notifications = it, notificationOffer = false) }
    private val journalListener:()->Unit = {
        // Watch start/stop also arrives here: show it even when a refresh is skipped (busy or already running).
        model=model.copy(watchRunning=WatchClient.available(this),usbDebugging=WatchClient.usbEnabled(this))
        if(ForegroundState.active&&!model.busy){loadReapplyHistory();refresh()} }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = JournalStore(this)
        // A recreated screen (fold, rotation) must not handle the last result again; batch ids restart in a new process.
        if(savedInstanceState?.getInt("batchPid")==android.os.Process.myPid())handledBatchId=savedInstanceState.getLong("handledBatchId")
        model=model.copy(applicationScope=scopes.current,scopePending=scopes.pending!=null||scopes.requested!=null,scopeResolved=scopes.resolved)
        prefs.getString("setupNotice",null)?.let{model=model.copy(notice=it);prefs.edit().remove("setupNotice").apply()}
        LifecycleEvents.listener=journalListener
        lifecycleScope.launch {
            BatchRuntime.state.collect { state ->
                if(state.id==0L)return@collect
                if(state.busy){model=model.copy(busy=true,operationHide=state.hide,operationShow=state.show,autoReapplying=state.auto,progress=state.progress,loadingApps=false,notice="")}
                else state.outcome?.let { outcome ->
                    val succeeded=outcome.results.filter{it.success}.map{it.packageName}.toSet()
                    val first=handledBatchId!=state.id;handledBatchId=state.id
                    val scopeChanged=model.applicationScope!=scopes.current
                    model=model.copy(busy=false,autoReapplying=false,progress=null,completion=outcome,applicationScope=scopes.current,scopePending=scopes.pending!=null||scopes.requested!=null,
                        page=if(first&&!outcome.auto&&outcome.results.isNotEmpty()&&outcome.results.all{it.success}&&model.page==(if(outcome.hide)Page.PICK else if(outcome.show)Page.SHOW else Page.RESTORE))Page.HOME else model.page,
                        entries=outcome.entries.ifEmpty{model.entries},
                        candidates=model.candidates.filterNot{s->outcome.snapshots.any{it.identity.packageName==s.identity.packageName}}+outcome.snapshots,
                        freshPackages=outcome.snapshots.map{it.identity.packageName}.toSet(),
                        connected=controls.wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady(),
                        picked=model.picked-succeeded,homePicked=model.homePicked-succeeded,showPicked=model.showPicked-succeeded,
                        batchResults=outcome.results,wirelessWarning=outcome.cleanupWarning,
                        notice=if(first){(if(outcome.auto&&outcome.error.isBlank()&&outcome.results.isNotEmpty()&&outcome.results.all{it.success})outcome.summary() else if(outcome.error.isBlank()&&outcome.results.all{it.success})"" else outcome.error.ifBlank{outcome.summary()}).ifBlank{skipNotice}.also{skipNotice=""}}else model.notice,
                        successToken=if(first&&outcome.hide&&succeeded.isNotEmpty())model.successToken+1 else model.successToken)
                    if(first)loadReapplyHistory()
                    if(first&&outcome.hide&&succeeded.isNotEmpty())ReapplyNotification.clear(this@MainActivity,succeeded,model.reapplyCount==0)
                    if(first&&outcome.beforeUninstall){model=model.copy(uninstallCheck=model.entries.count{it.enabled&&it.state!=TargetState.MISSING_TARGET});stopWatchForUninstall()}
                    // Target results update display hints; they do not change package inventory.
                    // Keep the 30-minute/package-event inventory expiry and fresh write preflights.
                    if(scopeChanged){invalidateScan();scopeSelected()}else{
                        val latest=outcome.snapshots.associateBy{it.identity.packageName}
                        val uncertain=outcome.results.filterNot{it.success}.map{it.packageName}.toSet()
                        model=model.copy(pickerRows=null,discovered=model.discovered.map{app->
                            val snapshot=latest[app.packageName]
                            when{app.packageName in uncertain->app.copy(check=CandidateCheck.NEEDS_CHECK)
                                snapshot!=null->app.copy(indicatorHint=HistoryPolicy.fineHint(snapshot.fineFlags))
                                else->app}
                        })
                        filterPicker()
                    }
                }
                if(model.busy)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (savedInstanceState != null) {
            model = model.copy(uninstallCheck = if (savedInstanceState.containsKey("uninstallCheck")) savedInstanceState.getInt("uninstallCheck") else model.uninstallCheck, page = Page.valueOf(savedInstanceState.getString("page") ?: "HOME"), selected = savedInstanceState.getString("selected"), picked=savedInstanceState.getStringArrayList("picked")?.toSet()?:emptySet(),query=savedInstanceState.getString("query")?:"",filter=AppFilter.valueOf(savedInstanceState.getString("filter")?:"RECENT"),homePicked=savedInstanceState.getStringArrayList("homePicked")?.toSet()?:emptySet(),historyView=HistoryView.valueOf(savedInstanceState.getString("historyView")?:"APPS"))
        }
        if(savedInstanceState!=null)model=model.copy(restoreSkipped=savedInstanceState.getInt("restoreSkipped",0),pageBack=Page.valueOf(savedInstanceState.getString("pageBack")?:"HOME"))
        if(savedInstanceState==null&&pendingRestore.isNotEmpty())model=model.copy(page=Page.RESTORE,homePicked=pendingRestore)
        pendingRestore=emptySet()
        if(model.page==Page.HISTORY&&model.historyView==HistoryView.REAPPLY)loadReapplyHistory()
        discovery=LocalAdbDiscovery(this){
            if(WatchClient.available(this)){model=model.copy(connected=true,automaticReady=true);return@LocalAdbDiscovery}
            val guid=getSharedPreferences("local-adb",0).getString("pairedGuid",null)
            val current=discovery.candidates(LocalAdbDiscovery.CONNECT,guid).singleOrNull()?.port
            if(current!=endpointPort){
                endpointPort=current;model=model.copy(connected=false,freshPackages=emptySet())
                if(!model.busy&&!WirelessWork.leasing){
                    if(startupScanAdopted&&cacheValid())model=model.copy(connected=controls.wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady())
                    else refresh()
                }
            }
        }
        discovery.start()
        val packageFilter=android.content.IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_REPLACED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packageChanges,packageFilter,android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(packageChanges,packageFilter)
        wifiObserver=WifiConnection.observe(this){runOnUiThread{model=model.copy(automaticReady=automaticReady());if(ForegroundState.active&&!model.busy)refresh()}}
        navigateNotification(intent)
        setContent { BlueDotApp(model, ::navigate, ::select, ::requestHide, ::requestRestore, ::batchHide, ::restoreAll, ::openBackend, ::openBackend, ::openBackend, ::refreshFromUser, ::allowNotifications, ::dismissNotificationOffer, ::begin, ::loadApps, ::clearNotice, ::togglePick, ::addSelected, ::changeQuery, ::changeFilter, { model=model.copy(homePicked=emptySet());navigate(Page.RESTORE) }, ::toggleHomePick, ::restoreSelected, ::contentShown, pickerRevision, ::historyViewChanged,pickerViewChanged={model=model.copy(pickerView=it,pickerRows=null);filterPicker()},wirelessExit={if(!model.busy)WirelessDebuggingExit.open(this)},toggleAll=::toggleAll,managementChanged=::managementChanged,removeManagementPermission=::removeManagementPermission,sharedInspect={pickGroups(listOf(it))},sharedAccept=::acceptShared,sharedDismiss={sharedGeneration++;model=model.copy(sharedOffer=null)},scopeChange=::chooseScope,reapplyNeeded={perform(it,true)},notificationChanged=::reapplyNotificationChanged,recheck=::recheck,bigGroupsAccept=::acceptBigGroups,bigGroupsDismiss={model=model.copy(bigGroups=emptyList())},rejectedDismiss={model=model.copy(rejectedGroupList=emptyList())},restorePickAll=::toggleAllRestore,showPick=::toggleShowPick,showPickAll=::toggleAllShow,showSelected=::showSelected,refreshHistory=::refreshHistory,autoReapplyChanged=::autoReapplyChanged,openShow={model=model.copy(showPicked=emptySet());navigate(Page.SHOW)},beforeUninstall=::beforeUninstall,uninstallDismiss={model=model.copy(uninstallCheck=null)},openAppInfo=::openAppInfo,copyDiagnostics=::copyDiagnostics,backgroundWatchChanged=::backgroundWatchChanged,openDeveloperOptions={SettingsEntrypoints.developerOptions(this)},openBattery={enable->if(!model.busy)BatteryOptimization.open(this,enable)},dismissUsNotice=::dismissUsNotice,updateOfferAnswer=::updateCheckChanged,updateCheckChanged=::updateCheckChanged,checkUpdateNow=::checkUpdateNow,openUpdate={UpdateCheck.open(this,UpdateCheck.Release(it));model=model.copy(updateCard=null)},skipUpdate={UpdateCheck.skip(this,it);model=model.copy(updateCard=null)}) }
    }
    // Leaving bluedotOFF turns a held wireless debugging off after a short grace (quick switches back keep it on).
    // The system job repeats it if the process is killed first (swiped away from recents, B-7).
    private val releaseHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseHeld=Runnable{WirelessWork.release(applicationContext)}
    override fun onStart() { super.onStart(); ForegroundState.started(); releaseHandler.removeCallbacks(releaseHeld); WirelessReleaseJob.cancel(applicationContext) }
    override fun onStop() { interactionOpened=0L;autoReapplyTried=false;ForegroundState.stopped(); releaseHandler.postDelayed(releaseHeld,2000); WirelessReleaseJob.schedule(applicationContext); super.onStop() }
    override fun onPause() { ForegroundState.paused();super.onPause() }
    override fun onResume() { super.onResume();pendingRestore=emptySet();ForegroundState.resumed();autoReapplyArmed=true;model=model.copy(notifications=reapplyNotificationsEnabled(),usbDebugging=WatchClient.usbEnabled(this),watchRunning=WatchClient.available(this),batteryUnrestricted=BatteryOptimization.unrestricted(this)); if (::store.isInitialized) resumeCheck(); updateOnOpen() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); navigateNotification(intent); refresh() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("page",model.page.name); out.putString("selected",model.selected); out.putStringArrayList("picked",ArrayList(model.picked));out.putString("query",model.query);out.putString("filter",model.filter.name);out.putString("historyView",model.historyView.name);out.putStringArrayList("homePicked",ArrayList(model.homePicked));out.putInt("restoreSkipped",model.restoreSkipped);out.putString("pageBack",model.pageBack.name);out.putInt("batchPid",android.os.Process.myPid());out.putLong("handledBatchId",handledBatchId);model.uninstallCheck?.let{out.putInt("uninstallCheck",it)};super.onSaveInstanceState(out) }
    override fun onDestroy() { if(LifecycleEvents.listener===journalListener)LifecycleEvents.listener=null;discovery.close();wifiObserver?.close();unregisterReceiver(packageChanges); super.onDestroy() }
    private fun navigateNotification(intent: Intent?) {
        if (intent?.action != "app.bluedotoff.REAPPLY") return
        // An external Intent only navigates. No automatic setter is reachable here.
        val target = intent.getStringExtra("target")
        val auto = prefs.getBoolean("autoReapply",true) && automaticReady()
        model = model.copy(page = if (target != null) Page.DETAIL else if (auto) Page.HOME else Page.REAPPLY, selected = target)
    }
    private fun begin() { prefs.edit().putBoolean("welcomed",true).apply(); openBackend() }
    private fun notificationsEnabled() = (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    /** The reapply switch is only on when Android also lets the "재적용 안내" channel through (null = not created yet). */
    private fun reapplyNotificationsEnabled() = notificationsEnabled() && getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(ReapplyNotification.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    private fun markLocalEvents(): List<Entry> {
        val boot = BootSession.current(this)
        check(store.transform { e ->
            LocalEntryEvents.read(e,Build.FINGERPRINT,boot) { try {
                val info = packageManager.getPackageInfo(e.identity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners?.map { BinderBackend.digest(it.toByteArray()) }?.sorted()
                Identity(e.identity.packageName,e.identity.userId,info.applicationInfo!!.uid,signers.orEmpty(),info.firstInstallTime,info.longVersionCode,info.lastUpdateTime)
            } catch (_: PackageManager.NameNotFoundException) { null } }
        }) { "복원 기록을 저장하지 못해서 바꾸지 않았어요." }
        val current=store.entries()
        return current
    }
    /** Shared groups left PENDING by an interruption, settled with SharedGroupEngine.recover. Returns the notice for what
     *  actually happened (null = nothing to settle): a lock is reported before a restored interrupted hide, then a
     *  finished interrupted restore (review 2026-10-17: a locked group was announced as restored). */
    private fun recoverInterrupted():String? {
        val groups=store.entries().filter{it.groupIdentities.isNotEmpty()&&it.state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE)}
            .map{e->e.groupIdentities.map{it.packageName}.sorted()}.distinct()
        val results=groups.map{g->SharedGroupEngine(backend,store,System::currentTimeMillis,{BootSession.current(this)},observe=backend::recordVerification).recover(g)}.flatten()
        results.firstOrNull{it.state in listOf(TargetState.NEEDS_REVIEW,TargetState.FAILED_ROLLBACK)}?.let{return it.error}
        if(results.any{it.state==TargetState.NEEDS_REAPPLY_POLICY&&it.error==SharedGroupEngine.INTERRUPTED})return SharedGroupEngine.INTERRUPTED
        val restored=results.count{it.state==TargetState.NOT_APPLIED&&!it.enabled}
        return if(restored>0)"${restored}개 앱을 원래 표시 상태로 복원했어요." else null
    }
    private var recoveredNotice:String?=null
    private fun refresh(userAction:Boolean=false,mode:Check=Check.AUTO,only:Set<String> = emptySet()) {
        if((!userAction&&SetupSession.current()!=null)||model.busy||BatchRuntime.state.value.busy||refreshJob?.isActive==true)return
        val job=lifecycleScope.launch {
            try {
                prefs.getString("setupNotice",null)?.let{cleanupNotice=it;prefs.edit().remove("setupNotice").apply()}
                val entries=withContext(Dispatchers.IO){markLocalEvents()}
                model=model.copy(journalProblem=false)
                if(userAction&&prefs.getBoolean("welcomed",false)&&scopes.resolved&&DeviceSupport.basic()&&BackgroundWatch.enabled(this@MainActivity)){
                    // Home hides the "멈춰 있어요" card while a stopped watch is being started.
                    model=model.copy(watchStarting=!WatchClient.available(this@MainActivity))
                    try{withContext(Dispatchers.IO){WatchClient.ensureStarted(applicationContext)}}finally{model=model.copy(watchStarting=false)}
                }
                model=model.copy(applicationScope=scopes.current,scopePending=scopes.pending!=null||scopes.requested!=null,scopeResolved=scopes.resolved,entries=entries,usModel=prefs.getBoolean("usModel",false)&&!prefs.getBoolean("usNoticeDismissed",false),reapplyNotify=prefs.getBoolean("reapplyNotify",true),autoReapply=prefs.getBoolean("autoReapply",true),backgroundWatch=BackgroundWatch.enabled(this@MainActivity),usbDebugging=WatchClient.usbEnabled(this@MainActivity),watchRunning=WatchClient.available(this@MainActivity),watchAcknowledged=BackgroundWatch.acknowledged(this@MainActivity),notifications=reapplyNotificationsEnabled(),automaticReady=automaticReady(),connected=controls.wireless||WatchClient.available(this@MainActivity),autoManagement=controls.consent,autoPermission=controls.granted,updateCheck=UpdateCheck.enabled(this@MainActivity),updateOffer=!UpdateCheck.asked(this@MainActivity),latestVersion=UpdateCheck.latest(this@MainActivity))
                if(mode==Check.LOCAL)return@launch
                if(!prefs.getBoolean("welcomed",false)){model=model.copy(gate=Gate.WELCOME);return@launch}
                if(!DeviceSupport.basic()){model=model.copy(gate=Gate.UNSUPPORTED,compatibility="Samsung One UI 9 이상에서 지원 여부를 확인할 수 있어요.");return@launch}
                val dev=Settings.Global.getInt(contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1
                val wireless=controls.wireless
                val paired=getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(this@MainActivity).exists
                if(!WatchClient.available(this@MainActivity)&&(!dev||!WifiConnection.connected(this@MainActivity)||!paired||(!wireless&&!model.automaticReady))){
                    model=model.copy(gate=if(entries.any{it.enabled}||model.automaticReady)Gate.READY else Gate.NOT_RUNNING,connected=false,freshPackages=emptySet(),compatibility=when{!dev->"먼저 개발자 옵션을 켜주세요.";!WifiConnection.connected(this@MainActivity)->"먼저 Wi-Fi에 연결해주세요.";!paired->"이 기기를 한 번 페어링해주세요.";model.automaticReady->"필요할 때 자동으로 연결해요.";else->"무선 디버깅을 켜주세요."})
                    return@launch
                }
                if(!scopes.resolved){openBackend();return@launch}
                val prepared=withContext(Dispatchers.IO){StartupScanHandoff.cache.take(Build.FINGERPRINT,BootSession.current(this@MainActivity),entries)}
                if(prepared!=null){
                    startupScanAdopted=true;changedPackages.clear()
                    lastHistoryElapsed=android.os.SystemClock.elapsedRealtime();clock.fullChecked(lastHistoryElapsed)
                    val scan=prepared.discovery
                    scanStamp=ScanStamp(Build.FINGERPRINT,packageEpoch,android.os.SystemClock.elapsedRealtime())
                    model=model.copy(gate=Gate.READY,entries=entries,candidates=prepared.snapshots,discovered=scan.apps,
                        historyAvailable=scan.historyAvailable,metrics=scan.metrics,loadingApps=false,
                        connected=wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady(),freshPackages=emptySet(),compatibility="앱 확인 완료",rechecking=false,lastCheckedAt=System.currentTimeMillis(),
                        notice=cleanupNotice,notificationOffer=!notificationsEnabled()&&!prefs.getBoolean("notificationExplained",false))
                    filterPicker()
                    if(model.page==Page.DETAIL&&model.selected!=null&&prepared.snapshots.none{it.identity.packageName==model.selected})select(model.selected!!)
                    return@launch
                }
                // Background triggers never connect by themselves when the app manages wireless debugging.
                if(!WatchClient.available(this@MainActivity)&&!userAction&&(!wireless||controls.consent&&controls.granted)){
                    model=model.copy(gate=Gate.READY,connected=false,freshPackages=emptySet(),compatibility="필요할 때 자동으로 연결해요.")
                    return@launch
                }
                startupScanAdopted=false
                val reuseInventory=model.discovered.isNotEmpty()&&when(mode){Check.FULL->false;Check.REGISTERED,Check.HISTORY->true;else->cacheValid()}
                // Set-up users keep their screen and see a small progress line; the full-screen check is for first use only.
                val quiet=model.gate==Gate.READY||prefs.getBoolean("welcomed",false)&&scopes.resolved
                model=if(quiet)model.copy(gate=Gate.READY,rechecking=true,scanProgress=null,checkingRegistered=reuseInventory,loadingApps=!reuseInventory)
                    else model.copy(gate=Gate.CHECKING,scanProgress=null,checkingRegistered=reuseInventory,loadingApps=!reuseInventory,compatibility="앱 확인 중…")
                val snapshots=mutableListOf<Snapshot>();val epoch=packageEpoch
                val knownApps=model.discovered;var refreshedHistory:List<DiscoveredApp>?=null
                val scan=withContext(Dispatchers.IO){
                    val coroutine=currentCoroutineContext()
                    var reportedPercent=-1
                    val reportScan:(Int,Int)->Unit={done,total->
                        if(total>0){
                            val progress=ScanProgress(done,total)
                            if(progress.percent!=reportedPercent||progress.finishing){
                                reportedPercent=progress.percent
                                runOnUiThread{if(coroutine.isActive&&(model.gate==Gate.CHECKING||model.rechecking)&&epoch==packageEpoch)model=model.copy(scanProgress=progress)}
                            }
                        }
                    }
                    localBatch(userAction){
                    val firmware=backend.verify();val boot=BootSession.current(this@MainActivity)
                    val found=if(reuseInventory){
                        // Only the apps Android reported as changed (plus their shared-group members), or all registered apps.
                        val all=store.entries()
                        val wanted=when{mode==Check.HISTORY->emptyList<String>()
                            only.isEmpty()->all.map{it.identity.packageName}
                            else->all.filter{e->(listOf(e.identity)+e.groupIdentities).any{it.packageName in only}}.flatMap{e->listOf(e.identity.packageName)+e.groupIdentities.map{it.packageName}}.distinct()}
                        reportScan(0,wanted.size)
                        val current=wanted.mapIndexed{index,pkg->
                            val snapshot=try{backend.read(pkg)}catch(_:PackageManager.NameNotFoundException){null}
                            reportScan(index+1,wanted.size)
                            pkg to snapshot
                        }.toMap()
                        snapshots.addAll(current.values.filterNotNull())
                        if(current.isNotEmpty())check(store.transform{e->if(e.identity.packageName in current)Reconcile.read(e,current[e.identity.packageName],firmware.key,boot,current) else e}){"복원 기록을 저장하지 못해서 바꾸지 않았어요."}
                        if(mode!=Check.HISTORY){if(only.isEmpty())changedPackages.clear() else changedPackages.removeAll(current.keys)}
                        // The connection is open anyway: refresh the display-only location history (about a second)
                        // so apps used since the last full check appear. Inventory and its 30-minute expiry are unchanged.
                        refreshedHistory=runCatching{backend.refreshHistory(knownApps){coroutine.ensureActive()}}.getOrNull()
                        null
                    }else{
                        val prepared=StartupScan.read({firmware},store::entries,
                            {capture->backend.discover(checkCancelled={coroutine.ensureActive()},onSnapshot=capture,onProgress=reportScan)},
                            {pkg->try{backend.read(pkg)}catch(_:PackageManager.NameNotFoundException){null}},
                            {targets,fw->check(store.transform{e->Reconcile.read(e,targets[e.identity.packageName],fw.key,boot,targets)}){"복원 기록을 저장하지 못해서 바꾸지 않았어요."};store.entries()},
                            boot,{coroutine.isActive})
                        snapshots.addAll(prepared.snapshots)
                        prepared.discovery
                    }
                    // B-1: on app open, settle shared groups an interruption left PENDING (checked; never from the watch).
                    recoveredNotice=recoverInterrupted()
                    found
                }}
                val checkedNow=android.os.SystemClock.elapsedRealtime();lastHistoryElapsed=checkedNow
                recoveredNotice?.let{cleanupNotice=it;recoveredNotice=null}
                val fullCheck=mode!=Check.HISTORY&&only.isEmpty()
                if(fullCheck)clock.fullChecked(checkedNow)
                if(scan!=null){changedPackages.clear();scanStamp=ScanStamp(Build.FINGERPRINT,epoch,android.os.SystemClock.elapsedRealtime());model=model.copy(discovered=scan.apps,historyAvailable=scan.historyAvailable,metrics=scan.metrics)}
                else refreshedHistory?.let{if(epoch==packageEpoch&&model.discovered===knownApps)model=model.copy(discovered=it,historyAvailable=true)}
                model=model.copy(gate=Gate.READY,scanProgress=null,rechecking=false,checkingRegistered=false,lastCheckedAt=if(fullCheck)System.currentTimeMillis() else model.lastCheckedAt,entries=store.entries(),candidates=if(mode==Check.HISTORY||only.isNotEmpty())model.candidates.filterNot{c->snapshots.any{it.identity.packageName==c.identity.packageName}}+snapshots else snapshots,loadingApps=false,connected=controls.wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady(),freshPackages=if(!controls.wireless&&!WatchClient.available(this@MainActivity))emptySet() else if(mode==Check.HISTORY||only.isNotEmpty())model.freshPackages+snapshots.map{it.identity.packageName} else snapshots.map{it.identity.packageName}.toSet(),compatibility="앱 확인 완료",notice=cleanupNotice,notificationOffer=!notificationsEnabled()&&!prefs.getBoolean("notificationExplained",false))
                filterPicker()
                if(model.page==Page.DETAIL&&model.selected!=null&&snapshots.none{it.identity.packageName==model.selected})select(model.selected!!)
            }catch(_:CancellationException){model=model.copy(rechecking=false,checkingRegistered=false,scanProgress=null,loadingApps=false)}catch(error:Exception){android.util.Log.w("bluedotOFF","refresh failed: ${error.javaClass.simpleName}: ${error.message?.take(160)}")
                // A broken or unsavable restore journal is not a connection problem: reconnecting cannot fix it, and clearing data would lose it.
                val journalProblem=error.message=="복원 기록을 저장하지 못해서 바꾸지 않았어요."||withContext(Dispatchers.IO){runCatching{store.entries()}.isFailure}
                model=model.copy(journalProblem=journalProblem,gate=if(journalProblem||model.entries.any{it.enabled}||model.automaticReady)Gate.READY else Gate.NOT_RUNNING,rechecking=false,checkingRegistered=false,scanProgress=null,loadingApps=false,connected=false,freshPackages=emptySet(),compatibility=if(error.message=="COMPATIBILITY_FAILED")SetupFlow.OS_CHANGED else if(error is UnresponsiveHelperException)WatchClient.UNRESPONSIVE else "현재 기기의 변경 작업을 확인할 수 없어요. 연결 준비에서 다시 확인해주세요.",notice=if(error is UnresponsiveHelperException)WatchClient.UNRESPONSIVE else cleanupNotice)}
        }
        refreshJob=job
        job.invokeOnCompletion{cause->if(cause==null)releaseHandler.post{val armed=autoReapplyArmed;autoReapplyArmed=false;if(armed)maybeAutoReapply()}}
    }
    private fun loadApps(){invalidateScan();refresh(true)}
    private fun changeQuery(query:String){interactionOpened=android.os.SystemClock.elapsedRealtime();interactionPhase="search_to_frame_ms";model=model.copy(query=query);filterPicker()}
    private fun changeFilter(filter:AppFilter){interactionOpened=android.os.SystemClock.elapsedRealtime();interactionPhase="filter_to_frame_ms";model=model.copy(filter=filter);filterPicker()}
    private fun filterPicker(){
        if(model.gate==Gate.READY&&!model.busy&&!initialScopePreviewShown)scopes.requested?.let{initialScopePreviewShown=true;previewScope(it)}
        pickerJob?.cancel()
        val apps=model.displayApps;val filter=model.filter;val query=model.query;val history=model.historyAvailable;val view=model.pickerView;val managed=model.managedPackages;val scope=model.applicationScope
        pickerJob=lifecycleScope.launch{val rows=withContext(Dispatchers.Default){ScopePolicy.list(apps,scope,filter,query,history)};if(model.filter==filter&&model.query==query&&model.pickerView==view&&model.managedPackages==managed&&model.applicationScope==scope){model=model.copy(pickerRows=rows);pickerRevision++}}
    }
    private fun contentShown(){
        val now=android.os.SystemClock.elapsedRealtime()
        if(model.page==Page.PICK&&model.discovered.isNotEmpty()&&!scanFrameRecorded){scanFrameRecorded=true;val ms=now-(if(pickerOpened>0L)pickerOpened else scanOpened);model=model.copy(metrics=model.metrics.copy(firstUsableMs=ms));lifecycleScope.launch(Dispatchers.IO){runCatching{backend.recordUiMetric("picker_first_frame_ms",ms)}}}
        if(interactionOpened>0L){val ms=now-interactionOpened;val phase=interactionPhase;interactionOpened=0L;lifecycleScope.launch(Dispatchers.IO){runCatching{backend.recordUiMetric(phase,ms)}}}
    }
    private fun navigate(page: Page) { if(page!=model.page)interactionOpened=0L;if(page==Page.PICK){pickerOpened=android.os.SystemClock.elapsedRealtime();scanFrameRecorded=false}
        // Screens opened from Settings (숨김이 남은 앱, 도움말) go back to where they were opened.
        model=model.copy(page=page,pageBack=if(page in listOf(Page.SHOW,Page.HELP)&&page!=model.page)model.page else model.pageBack); if(page in listOf(Page.PICK,Page.SHOW)&&(!cacheValid()||model.discovered.isEmpty()))refresh(true,Check.FULL)
        // History shows the remembered list at once and re-reads only location access times (about a second).
        else if(page==Page.HISTORY&&model.historyView==HistoryView.REAPPLY)loadReapplyHistory()
        else if(page==Page.HISTORY)refresh(true,if(model.discovered.isEmpty())Check.FULL else if(android.os.SystemClock.elapsedRealtime()-lastHistoryElapsed>=60_000L)Check.HISTORY else Check.LOCAL) }
    private fun select(pkg:String) {
        // Back from the detail page returns to where it was opened (History or Home).
        model=model.copy(page=Page.DETAIL,selected=pkg,loadingDetail=true,detailBack=if(model.page==Page.DETAIL)model.detailBack else model.page)
        lifecycleScope.launch {
            try {
                val start=android.os.SystemClock.elapsedRealtime()
                val s=withContext(Dispatchers.IO){localBatch{backend.read(pkg)}}
                val ms=android.os.SystemClock.elapsedRealtime()-start
                if(model.selected==pkg)model=model.copy(candidates=model.candidates.filterNot{it.identity.packageName==pkg}+s,loadingDetail=false,freshPackages=model.freshPackages+pkg,metrics=model.metrics.copy(targetPreflightMs=ms))
                withContext(Dispatchers.IO){backend.recordUiMetric("target_detail_ms",ms)}
            }catch(_:Exception){if(model.selected==pkg)model=model.copy(loadingDetail=false,notice="앱 상태를 확인하지 못했어요.")}
        }
    }
    private fun togglePick(pkg:String) {if(model.busy)return;val group=model.selectionGroup(pkg);if(pkg in model.picked){model=model.copy(picked=model.picked-group.toSet());return};if(model.displayApps.none{it.packageName==pkg&&model.canSelect(it)})return;model=model.copy(picked=model.picked+group)}
    private fun toggleAll(){if(!model.busy&&!model.loadingApps){val covered=model.pickerApps.filter{model.canSelect(it)}.flatMap{model.selectionGroup(it.packageName)}.toSet();val groups=model.pickerApps.filter{model.canPickGroup(it)}.map{it.packageName}
        // Before, any unpicked shared group made this add-only, so "전체 선택" could not be undone.
        val(picked,inspect)=PickerSelection.toggleWithGroups(covered,groups,model.picked);model=model.copy(picked=picked);if(inspect.isNotEmpty())pickGroups(inspect)}}
    private fun toggleHomePick(pkg:String) { if(model.busy)return;model=model.copy(homePicked=if(pkg in model.homePicked)model.homePicked-(model.entries.firstOrNull{it.identity.packageName==pkg}?.groupIdentities?.map{it.packageName}?.takeIf{it.isNotEmpty()}?:listOf(pkg)).toSet() else model.homePicked+(model.entries.firstOrNull{it.identity.packageName==pkg}?.groupIdentities?.map{it.packageName}?.takeIf{it.isNotEmpty()}?:listOf(pkg))) }
    private fun restoreSelected() = perform(model.homePicked.toList(),false)
    private fun toggleShowPick(pkg:String){if(model.busy)return;model=model.copy(showPicked=if(pkg in model.showPicked)model.showPicked-pkg else model.showPicked+pkg)}
    private fun toggleAllShow(pkgs:List<String>){if(model.busy)return;model=model.copy(showPicked=if(pkgs.all{it in model.showPicked})model.showPicked-pkgs.toSet() else model.showPicked+pkgs)}
    private fun showSelected() = perform(model.showPicked.toList(),hide=false,show=true)
    /** History ↻: re-reads location access times now (the tab itself re-reads at most once a minute). */
    private fun historyViewChanged(view:HistoryView){model=model.copy(historyView=view);if(view==HistoryView.REAPPLY)loadReapplyHistory()}
    private fun loadReapplyHistory(){
        if(reapplyHistoryJob?.isActive==true)return
        reapplyHistoryJob=lifecycleScope.launch {
            model=model.copy(reapplyHistoryLoading=true)
            try{
                val rows=withContext(Dispatchers.IO){ReapplyHistoryStore(applicationContext).entries()}
                val labels=(model.entries.map{it.identity.packageName to it.label}+model.discovered.map{it.packageName to it.label}).toMap()
                model=model.copy(reapplyHistory=rows.map{row->if(row.label==row.packageName)row.copy(label=labels[row.packageName]?:row.label) else row})
            }catch(_:Exception){model=model.copy(notice="재적용 기록을 읽지 못했어요. 다시 새로고침해 주세요.")}
            finally{model=model.copy(reapplyHistoryLoading=false)}
        }
    }
    private fun refreshHistory(){if(model.historyView==HistoryView.REAPPLY)loadReapplyHistory() else refresh(true,if(model.discovered.isEmpty())Check.FULL else Check.HISTORY)}
    /** Re-applies hidden apps that lost hiding (restart, the app's own change, an app update) once per visit, right after the
     *  check that runs when the user opens bluedotOFF. Only with automatic management (no manual wireless step) and the setting
     *  on; firmware updates, review/failed states and reinstalled apps are left to the user. Failures stay "재적용 필요". */
    private var autoReapplyTried=false
    private var autoReapplyArmed=false
    private fun maybeAutoReapply(){
        if(autoReapplyTried||!ForegroundState.active||model.busy||BatchRuntime.state.value.busy||model.gate!=Gate.READY)return
        if(!prefs.getBoolean("autoReapply",true)||!automaticReady())return
        val targets=model.reapplyTargetPackages;if(targets.isEmpty())return
        autoReapplyTried=true;perform(targets,hide=true,auto=true)
    }
    private fun backgroundWatchChanged(on:Boolean){
        BackgroundWatch.set(this,on);model=model.copy(backgroundWatch=on,watchAcknowledged=model.watchAcknowledged||on)
        if(!on){WatchClient.stop(applicationContext);model=model.copy(automaticReady=automaticReady(),connected=controls.wireless||WatchClient.available(this@MainActivity));return}
        WatchClient.startFromSwitch(applicationContext){started->runOnUiThread{if(!isDestroyed&&BackgroundWatch.enabled(this)){
            if(!started)model=model.copy(notice=if(!WatchClient.usbEnabled(this))"감시를 시작하려면 USB 디버깅을 켜주세요." else "감시를 시작하려면 Wi-Fi에 연결하고 연결 준비를 완료해주세요.")
            refresh(true)
        }}}
    }
    private fun autoReapplyChanged(on:Boolean){prefs.edit().putBoolean("autoReapply",on).apply();model=model.copy(autoReapply=on)}
    /** "앱 삭제 전 복원": restores every registered app, then tells whether bluedotOFF can be uninstalled now.
     *  Uninstalling removes the app's permission and its connection key by itself, so only the restore is needed. */
    /** Restores first - through a live watch helper when there is one, so Wi-Fi is not needed - and turns watching off afterwards.
     *  Restored entries leave the watch's targets as soon as they are journaled and work is exclusive, so watching cannot re-hide them. */
    private fun beforeUninstall(){if(model.busy)return
        if(model.entries.none{it.enabled&&it.state!=TargetState.MISSING_TARGET}){stopWatchForUninstall();model=model.copy(uninstallCheck=0);return}
        perform(model.entries.filter{it.enabled}.map{it.identity.packageName},hide=false,beforeUninstall=true)}
    private fun stopWatchForUninstall(){if(!BackgroundWatch.enabled(this))return
        BackgroundWatch.set(this,false);model=model.copy(backgroundWatch=false)
        lifecycleScope.launch{withContext(Dispatchers.IO){WatchClient.stopNow(applicationContext)};model=model.copy(automaticReady=automaticReady(),connected=controls.wireless||WatchClient.available(this@MainActivity))}}
    private fun openAppInfo()=startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.fromParts("package",packageName,null)))
    /** Device facts for a GitHub issue; no app list, location history or account data. */
    private val SUPPORT_EMAIL="yundeokhee92@gmail.com"
    private fun copyDiagnostics(){
        fun stored(key:String)=prefs.getString(key,null)?.takeIf{it.isNotBlank()}?:"not checked yet"
        val text=listOf("bluedotOFF diagnostics","App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})","Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})","Build: ${Build.DISPLAY}","One UI: ${stored("deviceOneUi").let{v->v.toIntOrNull()?.takeIf{it>=10000}?.let{"${it/10000}.${it/100%100} ($v)"}?:v}}","Country: ${stored("deviceCountry")}",
            "Compatibility check: ${when{model.gate==Gate.UNSUPPORTED->"not supported (stopped safely)";LocalAdbSession.compatibilityConfirmed->"passed";else->"not checked yet (connect once and copy again)"}}",
            "Automatic management: ${if(model.autoManagement)"on" else "off"}","App scope: ${model.applicationScope.name}",
            "Registered apps: ${model.visibleEntries.count{it.enabled}} (applied ${model.lastVerifiedAppliedCount}, reapply needed ${model.reapplyCount})").joinToString("\n")
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("bluedotOFF diagnostics",text))
        // Opens the user's mail app with the text filled in; nothing is sent until the user taps send there.
        val subject="bluedotOFF diagnostics ${BuildConfig.VERSION_NAME}"
        val mail=Intent(Intent.ACTION_SENDTO,android.net.Uri.parse("mailto:$SUPPORT_EMAIL?subject=${android.net.Uri.encode(subject)}&body=${android.net.Uri.encode(text)}"))
            .putExtra(Intent.EXTRA_EMAIL,arrayOf(SUPPORT_EMAIL)).putExtra(Intent.EXTRA_SUBJECT,subject).putExtra(Intent.EXTRA_TEXT,text)
        val opened=runCatching{startActivity(mail)}.isSuccess
        model=model.copy(notice=if(opened)"진단 정보를 복사하고 메일 앱을 열었어요. 내용을 확인한 뒤 보내 주세요. 설치된 앱 목록이나 위치 기록은 들어 있지 않아요."
            else "메일 앱을 열 수 없어서 진단 정보를 복사만 했어요. $SUPPORT_EMAIL 로 붙여넣어 보내 주세요.")
    }
    /** "전체 선택" on the restore page: all listed apps (with their shared-group members) on, or all off. */
    private fun toggleAllRestore(pkgs:List<String>){if(model.busy)return
        val all=pkgs.flatMap{p->model.entries.firstOrNull{it.identity.packageName==p}?.groupIdentities?.map{it.packageName}?.takeIf{it.isNotEmpty()}?:listOf(p)}.toSet()
        model=model.copy(homePicked=if(all.all{it in model.homePicked})model.homePicked-all else model.homePicked+all)}
    private fun addSelected() = perform(model.picked.toList(),true)
    private fun requestHide(pkg: String) {if(model.entries.any{it.identity.packageName==pkg&&it.groupIdentities.isNotEmpty()}||model.displayApps.any{it.packageName==pkg&&model.canInspectGroup(it)})inspectShared(pkg,true,true)else perform(listOf(pkg),hide=true)}
    private fun requestRestore(pkg: String) {if(model.entries.any{it.identity.packageName==pkg&&it.groupIdentities.isNotEmpty()})inspectShared(pkg,false)else perform(listOf(pkg),hide=false)}
    private fun batchHide() = perform(model.entries.filter { it.enabled && it.state !in listOf(TargetState.FAILED_ROLLBACK,TargetState.INACTIVE_INSTALL,TargetState.MISSING_TARGET,TargetState.SHARED_UID_BLOCKED,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW,TargetState.UNSUPPORTED_STATE) }.map { it.identity.packageName }, hide = true)
    private fun restoreAll() = perform(model.entries.filter { it.enabled }.map { it.identity.packageName }, hide = false)
    /** [show]: "표시 켜기" for apps that are OFF with no record (SafetyEngine.userShow); one app at a time, no shared groups. */
    /** [auto]: started by [maybeAutoReapply] when the user opened the app; shown as a small line instead of the full overlay. */
    private fun perform(requested: List<String>, hide: Boolean, show: Boolean = false, auto: Boolean = false, beforeUninstall: Boolean = false) {
        val (packages,needsCheck)=if(hide||show)requested to 0 else model.restorePlan(requested)
        if(packages.isEmpty()&&requested.isNotEmpty()&&!model.busy){model=model.copy(notice=if(needsCheck==0)"삭제된 앱은 복원할 필요가 없어요. 앱을 지우면 위치 표시 설정도 함께 사라져요." else "선택한 앱은 지금 복원할 수 없어요. 홈의 ‘등록한 앱’에서 앱을 하나씩 눌러 상태를 확인해주세요.");return}
        if(model.busy||BatchRuntime.state.value.busy||packages.isEmpty())return
        refreshJob?.cancel()
        if(!BuildConfig.LOCAL_WRITES_ENABLED){model=model.copy(notice="읽기 전용 검증 빌드예요. 설정은 바꾸지 않아요.");return}
        if(!model.canOperate||model.gate!=Gate.READY){model=model.copy(notice="연결 설정에서 기기를 연결한 뒤 다시 눌러 주세요.");return}
        refreshJob?.cancel();discoveryJob?.cancel();discoveryGeneration++
        StartupScanHandoff.cache.invalidate()
        val groups=if(show)emptyList() else (model.approvedGroups+model.entries.map{it.groupIdentities.map{p->p.packageName}}.filter{it.isNotEmpty()}).distinct()
        val units=packages.distinct().map{p->groups.firstOrNull{p in it}?:listOf(p)}.distinct()
        val targets=units.flatten().distinct()
        val groupMap=groups.flatMap{g->g.map{it to g}}.toMap()
        val app=applicationContext
        val journal=JournalStore(app)
        val reapplying=if(hide&&!show)model.entries.filter{it.enabled}.map{it.identity.packageName}.toSet() else emptySet()
        val runtimePrefs=app.getSharedPreferences("ui-state",MODE_PRIVATE)
        val started=BatchRuntime.start(targets.size,hide,show,auto,beforeUninstall) { report ->
            val results=mutableListOf<BatchResult>()
            val observed=linkedMapOf<String,Snapshot>()
            val runner=TargetBatch.Runner()
            var pipelineStopped=false
            var warning=""
            var failure=""
            var cursor=0
            val rememberResult:(BatchResult)->Unit={result->
                if(hide&&!show&&(auto||result.packageName in reapplying))ReapplyHistoryStore(app).record(result,if(auto)ReapplySource.APP_OPEN else ReapplySource.MANUAL)
            }
            try {
                WirelessWork.runBatch(app,true,onCleanup={if(it.isNotBlank())warning=it},hold=true) { session ->
                while(cursor<targets.size&&!runner.stopped&&!pipelineStopped) {
                    if(!ForegroundState.canStartTargets){failure="화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요.";break}
                    report(BatchProgress(cursor,targets.size,BatchPhase.CONNECTING))
                    session.run { active ->
                        val engine=SafetyEngine(active,journal,System::currentTimeMillis,{BootSession.current(app)},observe={phase,snapshot,wrote->
                            active.recordVerification(phase,snapshot,wrote)
                            if(phase=="settled_1500ms")observed[snapshot.identity.packageName]=snapshot
                        })
                        var shared=groupMap[targets[cursor]]
                        if(shared!=null){
                            // Consecutive shared groups reuse this helper (was one helper each: ~2 s start and close per group).
                            var groupsInHelper=0
                            while(shared!=null){
                                val group:List<String> =shared
                                val groupResults=try{
                                    val members=SharedGroupEngine(active,journal,System::currentTimeMillis,{BootSession.current(app)},observe={phase,s,wrote->active.recordVerification(phase,s,wrote);if(phase=="settled_1500ms")observed[s.identity.packageName]=s}).execute(group,hide){ForegroundState.canStartTargets}
                                    members.map{e->BatchResult(e.identity.packageName,e,if(e.state in setOf(TargetState.APPLIED,TargetState.NOT_APPLIED))"" else e.error.ifBlank{"현재 상태 확인 필요"})}
                                }catch(e:Exception){
                                    val saved=runCatching{journal.entries()}.getOrDefault(emptyList())
                                    group.map{p->BatchResult(p,saved.lastOrNull{it.identity.packageName==p},e.message?:"공유 앱 묶음을 확인해야 해요.")}
                                }
                                groupResults.forEach(rememberResult)
                                results.addAll(groupResults);cursor+=group.size;pipelineStopped=groupResults.any{!it.success}
                                if(pipelineStopped){failure=groupResults.first{!it.success}.error;group.forEach{observed.remove(it)}}
                                report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                                groupsInHelper++
                                shared=if(!pipelineStopped&&!runner.stopped&&groupsInHelper<8&&ForegroundState.canStartTargets&&cursor<targets.size)groupMap[targets[cursor]] else null
                            }
                        } else if(targets.size>1) {
                            val completedBefore=results.size
                            var completed=0
                            val chunk=targets.drop(cursor).takeWhile{it !in groupMap}.take(BatchWindow.MAX_TARGETS)
                            val onResult:(BatchResult)->Unit={result->
                                rememberResult(result)
                                if(!result.success)observed.remove(result.packageName)
                                completed++;report(BatchProgress(completedBefore+completed,targets.size,BatchPhase.CHECKING))
                            }
                            val window=when{
                                hide->engine.userHideWindow(chunk,canStart={ForegroundState.canStartTargets},elapsed=android.os.SystemClock::elapsedRealtime,onResult=onResult)
                                show->engine.userShowWindow(chunk,canStart={ForegroundState.canStartTargets},elapsed=android.os.SystemClock::elapsedRealtime,onResult=onResult)
                                else->engine.userRestoreWindow(chunk,canStart={ForegroundState.canStartTargets},elapsed=android.os.SystemClock::elapsedRealtime,onResult=onResult)
                            }
                            results.addAll(window.results);cursor+=window.started;pipelineStopped=window.stopped
                            android.util.Log.i("bluedotOFF","window: ${window.started} apps ${if(hide)"hide" else if(show)"show" else "restore"}")
                            if(window.reason.isNotBlank())failure=window.reason
                        } else {
                            val start=android.os.SystemClock.elapsedRealtime()
                            var count=0
                            while(cursor<targets.size&&!runner.stopped&&BatchWindow.next(count,android.os.SystemClock.elapsedRealtime()-start)) {
                                if(!ForegroundState.canStartTargets){failure="화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요.";break}
                                report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                                val pkg=targets[cursor]
                                val result=runner.run(pkg){if(show)engine.userShow(it){ForegroundState.canStartTargets} else engine.userOne(it,hide){ForegroundState.canStartTargets}}
                                rememberResult(result)
                                results.add(result);cursor++;count++
                                if(!result.success)observed.remove(pkg)
                                report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                            }
                        }
                        if(hide&&results.any{it.success})check(runtimePrefs.edit().putString("verifiedFirmware",active.compatibleFirmware().key).putInt("settlingSchema",2).commit())
                        report(BatchProgress(cursor,targets.size,BatchPhase.CLOSING))
                    }
                    // Cleanup failures remain separate from target success. Do not open another lease.
                    if(warning.isNotEmpty()||failure.isNotEmpty())break
                }
                }
            }catch(e:Exception){failure=e.message?:"현재 상태를 확인하지 못했어요."}
            val untouched=if(runner.stopped||pipelineStopped)"앞선 작업의 복원이나 연결을 먼저 확인해야 해서 바꾸지 않았어요." else "작업을 멈춰서 이 앱은 바꾸지 않았어요."
            while(cursor<targets.size){results.add(BatchResult(targets[cursor++],error=untouched))}
            val outcome=BatchOutcome(hide,results,journal.entries(),observed.values.toList(),warning,failure,show=show,auto=auto,beforeUninstall=beforeUninstall)
            if(!hide&&!show&&ScopePreferences(app).pending!=null&&!ScopePreferences(app).finishRestore(outcome))return@start outcome.copy(error=outcome.error.ifBlank{"일부 앱의 복원을 확인하지 못해 이전 적용 범위를 유지했어요. 설정에서 다시 확인해주세요."})
            outcome
        }
        if(started)skipNotice=if(needsCheck>0)"확인이 필요한 ${needsCheck}개 앱은 건너뛰었어요. 홈 목록에서 상태를 확인해주세요." else ""
        if(started)model=model.copy(busy=true,operationHide=hide,operationShow=show,autoReapplying=auto,progress=BatchProgress(0,targets.size),completion=null,restoreSkipped=needsCheck,loadingApps=false,notice="",wirelessWarning="",batchResults=emptyList())
    }
    /** Restore picks made before "연결 후 N개 복원": setup returns with FLAG_ACTIVITY_CLEAR_TOP, which recreates this
     *  activity, so the selection is carried over in memory and shown again. Nothing runs until the user taps restore. */
    private companion object { var pendingRestore:Set<String> = emptySet() }
    private fun openBackend(){pendingRestore=if(model.page==Page.RESTORE)model.homePicked else emptySet();checkOnResume=true;refreshJob?.cancel();discoveryJob?.cancel();invalidateScan();startActivity(Intent(this,LocalAdbSetupActivity::class.java))}
    private fun <T> localBatch(userAction:Boolean=true,forceWireless:Boolean=false,block:()->T):T {
        return WirelessWork.run(this,userAction,endpointPort,onCleanup={cleanupNotice=it},hold=true,forceWireless=forceWireless){active->
            backend=active
            engine=SafetyEngine(active,store,System::currentTimeMillis,{BootSession.current(this)},observe=active::recordVerification)
            block()
        }
    }
    private fun managementChanged(enabled:Boolean){
        // Turning automatic management off first closes the kept connection and turns off wireless debugging the app
        // turned on itself (still allowed while consent is on), so nothing it opened stays on afterwards.
        if(!enabled){lifecycleScope.launch{withContext(Dispatchers.IO){runCatching{WirelessWork.releaseNow(applicationContext)}};controls.setConsent(false);model=model.copy(autoManagement=false,automaticReady=false,connected=controls.wireless||WatchClient.available(this@MainActivity))};return}
        if(model.busy){model=model.copy(notice="작업이 끝난 뒤 자동 관리를 켜주세요.");return}
        val paired=getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(this).exists
        if(!paired||!controls.wireless){model=model.copy(notice="먼저 연결 준비를 완료해주세요.");openBackend();return}
        controls.setConsent(true);model=model.copy(busy=true)
        lifecycleScope.launch {
            try {withContext(Dispatchers.IO){localBatch(forceWireless=true){if(!controls.granted)(backend as LocalAdbBackend).grantSelfAutomaticPermission()}};model=model.copy(autoManagement=true,autoPermission=controls.granted,automaticReady=automaticReady(),connected=controls.wireless||WatchClient.available(this@MainActivity),notice=cleanupNotice)}
            catch(_:Exception){controls.setConsent(false);model=model.copy(autoManagement=false,automaticReady=false,notice="자동 관리를 켜지 못했어요. 직접 연결하는 방식은 계속 쓸 수 있어요.")}
            finally{model=model.copy(busy=false)}
        }
    }
    private fun removeManagementPermission(){
        controls.setConsent(false);model=model.copy(autoManagement=false,automaticReady=false)
        if(model.busy||!controls.wireless){model=model.copy(notice="자동 관리를 껐어요. 권한까지 지우려면 무선 디버깅으로 연결해주세요.");return}
        model=model.copy(busy=true)
        lifecycleScope.launch{try{withContext(Dispatchers.IO){localBatch(false,forceWireless=true){(backend as LocalAdbBackend).revokeSelfAutomaticPermission()}};model=model.copy(autoPermission=controls.granted,notice="자동 관리 권한을 지웠어요.")}catch(_:Exception){model=model.copy(notice="자동 관리는 껐지만 권한이 지워졌는지 확인하지 못했어요.")}finally{model=model.copy(busy=false)}}
    }
    private fun allowNotifications() {
        prefs.edit().putBoolean("notificationExplained",true).apply()
        if(notificationsEnabled()){model=model.copy(notificationOffer=false,notifications=reapplyNotificationsEnabled());return}
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED ||
            prefs.getBoolean("notificationRequested",false)&&!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        }else{prefs.edit().putBoolean("notificationRequested",true).apply();notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
    }
    /** Settings switch for reapply reminders. Turning it on also asks for the system notification permission if needed. */
    private fun reapplyNotificationChanged(on:Boolean){
        prefs.edit().putBoolean("reapplyNotify",on).apply();model=model.copy(reapplyNotify=on)
        if(on&&!notificationsEnabled())allowNotifications()
        else if(on&&!reapplyNotificationsEnabled())startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,ReapplyNotification.CHANNEL))
    }
    /** Opt-in update check on open: at most once a day, silent when offline or failing; a new version is shown once. */
    private fun updateOnOpen(){
        if(!UpdateCheck.enabled(this))return
        UpdateCheckJob.schedule(applicationContext)
        lifecycleScope.launch{
            withContext(Dispatchers.IO){runCatching{UpdateCheck.check(applicationContext)}}
            val release=UpdateCheck.pending(this@MainActivity)
            if(release!=null)UpdateCheck.markNotified(this@MainActivity,release.tag)
            model=model.copy(latestVersion=UpdateCheck.latest(this@MainActivity),updateCard=release?.tag?:model.updateCard)
        }
    }
    /** Settings switch and the one-time question (both default off). */
    private fun updateCheckChanged(on:Boolean){
        UpdateCheck.set(this,on)
        model=model.copy(updateCheck=on,updateOffer=false,updateStatus="",updateCard=if(on)model.updateCard else null)
        if(on)updateOnOpen()
    }
    /** Settings "지금 확인": the user asked, so the result (including a failure) is shown. */
    private fun checkUpdateNow(){
        if(model.updateChecking||!UpdateCheck.enabled(this))return
        model=model.copy(updateChecking=true,updateStatus="")
        lifecycleScope.launch{
            val release=withContext(Dispatchers.IO){runCatching{UpdateCheck.check(applicationContext,force=true)}.getOrNull()}
            val status=when{
                release==null->"확인하지 못했어요. 인터넷 연결을 확인한 뒤 다시 시도해주세요."
                UpdatePolicy.newer(release.tag,UpdateCheck.current())->"새 버전 ${release.tag}이 있어요."
                else->"최신 버전을 쓰고 있어요."
            }
            if(release!=null&&UpdatePolicy.newer(release.tag,UpdateCheck.current()))UpdateCheck.markNotified(this@MainActivity,release.tag)
            model=model.copy(updateChecking=false,updateStatus=status,latestVersion=UpdateCheck.latest(this@MainActivity))
        }
    }
    /** The US-model notice is informational: once confirmed it stays hidden (README keeps the details). */
    private fun dismissUsNotice() { prefs.edit().putBoolean("usNoticeDismissed",true).apply(); model=model.copy(usModel=false) }
    private fun dismissNotificationOffer() { prefs.edit().putBoolean("notificationExplained",true).apply(); model=model.copy(notificationOffer=false) }
    /** Picker: reads and validates each shared group in one connection, then selects every valid group as a unit.
     * Groups that do not pass are left out of the picker; the write path validates every group again before changing it. */
    private fun pickGroups(pkgs:List<String>){
        if(model.busy||model.loadingApps||!model.canOperate||pkgs.isEmpty())return
        model=model.copy(loadingApps=true)
        lifecycleScope.launch{
            try{
                val checked=withContext(Dispatchers.IO){localBatch(true){
                    val seen=mutableSetOf<String>()
                    pkgs.mapNotNull{pkg->if(pkg in seen)null else runCatching{
                        val members=backend.read(pkg).peers.sorted().map{backend.read(it)}
                        seen+=members.map{it.identity.packageName}
                        Triple(pkg,members,runCatching{SharedGroupPolicy.validate(members)}.exceptionOrNull()?.message.orEmpty().ifBlank{null})
                    }.getOrElse{Triple(pkg,emptyList<Snapshot>(),it.message?:"공유 앱 묶음을 확인하지 못했어요.")}}
                }}
                // Large or system-owned groups (e.g. 디바이스 찾기 = Android system, 93 apps) are confirmed first,
                // whether they were tapped one by one or reached through "전체 선택".
                fun big(g:List<Snapshot>)=g.size>=BIG_GROUP||g.any{it.identity.uid%100000<10000}
                val valid=checked.filter{it.third==null&&!big(it.second)}.map{it.second}
                val large=checked.filter{it.third==null&&big(it.second)}
                val names=valid.map{g->g.map{it.identity.packageName}.sorted()}
                val failed=checked.filter{it.third!=null}
                val rejected=failed.flatMap{f->f.second.map{it.identity.packageName}+f.first}.toSet()
                model=model.copy(approvedGroups=(model.approvedGroups+names).distinct(),
                    candidates=model.candidates.filterNot{c->names.flatten().contains(c.identity.packageName)}+valid.flatten(),
                    picked=model.picked+names.flatten(),rejectedGroups=model.rejectedGroups+rejected,
                    bigGroups=large.map{(pkg,members,_)->BigGroup(model.discovered.firstOrNull{it.packageName==pkg}?.label?:pkg,members.map{it.identity.packageName}.sorted(),members.any{it.identity.uid%100000<10000})},
                    // Which groups were left out and why stays on screen until confirmed (a snackbar was too quick).
                    rejectedGroupList=failed.filter{it.third!=SharedGroupPolicy.NO_LOCATION}.map{(pkg,members,reason)->RejectedGroup(model.discovered.firstOrNull{it.packageName==pkg}?.label?:pkg,members.size.coerceAtLeast(1),UiText.error(reason!!))},notice="")
                if(failed.isNotEmpty())android.util.Log.i("bluedotOFF","groups not selected: ${failed.size} (${failed.map{it.third}.distinct().joinToString(" | ")})")
            }catch(_:Exception){model=model.copy(notice="공유 앱 묶음을 확인하지 못했어요.")}
            finally{model=model.copy(loadingApps=false,connected=controls.wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady());filterPicker()}
        }
    }
    private fun acceptBigGroups(){val groups=model.bigGroups.map{it.names};model=model.copy(bigGroups=emptyList(),approvedGroups=(model.approvedGroups+groups).distinct(),picked=model.picked+groups.flatten());filterPicker()}
    private fun inspectShared(pkg:String,hide:Boolean=true,direct:Boolean=false){
        if(model.busy||!model.canOperate)return
        val revision=++sharedGeneration
        model=model.copy(sharedOffer=SharedOffer(emptyList(),hide,loading=true))
        lifecycleScope.launch{try{
            val members=withContext(Dispatchers.IO){localBatch(true){backend.read(pkg).peers.sorted().map{backend.read(it)}}}
            val error=runCatching{SharedGroupPolicy.validate(members)}.exceptionOrNull()?.message.orEmpty()
            if(revision==sharedGeneration)model=model.copy(sharedOffer=SharedOffer(members,hide,error=error,direct=direct),connected=controls.wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady(),wirelessWarning=cleanupNotice)
        }catch(e:Exception){if(revision==sharedGeneration)model=model.copy(sharedOffer=SharedOffer(emptyList(),hide,error=e.message?:"공유 앱 묶음을 확인하지 못했어요."))}}
    }
    private fun acceptShared(){val offer=model.sharedOffer?:return;if(offer.loading||offer.error.isNotBlank())return;val names=offer.members.map{it.identity.packageName}.sorted();model=model.copy(sharedOffer=null,approvedGroups=(model.approvedGroups+listOf(names)).distinct(),candidates=model.candidates.filterNot{it.identity.packageName in names}+offer.members);if(offer.hide&&!offer.direct){model=model.copy(picked=model.picked+names);filterPicker()}else perform(names,offer.hide)}
    private fun chooseScope(){
        if(model.busy)return
        ScopeDialogs.choose(this,scopes.pending?:scopes.requested?:scopes.current,tint=model.blueRemaining){previewScope(it)}
    }
    private fun previewScope(scope:ApplicationScope){
        if(model.busy)return
        if(scope.level>=scopes.current.level||model.entries.none{it.enabled}){
            ScopeDialogs.confirm(this,scope,tint=model.blueRemaining){
                if(!model.busy){if(scopes.select(scope))scopeSelected()else model=model.copy(notice="적용 범위를 저장하지 못했어요.")}
            }
            return
        }
        var excluded=emptyList<Snapshot>()
        val tint=model.blueRemaining
        ScopeDialogs.lowering(this,scope,tint,load={complete->
            lifecycleScope.launch {
                model=model.copy(busy=true,scopePreview=true)
                try {
                    excluded=withContext(Dispatchers.IO){localBatch(true){
                        val entries=store.entries().filter{it.enabled&&it.state!=TargetState.MISSING_TARGET}
                        val snapshots=entries.map{e->backend.read(e.identity.packageName).also{s->
                            if(!e.identity.sameInstall(s.identity))throw OwnershipChangedException()
                            if(e.groupIdentities.isNotEmpty()&&s.peers.sorted()!=e.groupIdentities.map{it.packageName})throw OwnershipChangedException()
                        }}
                        val rejected=snapshots.filterNot{ScopePolicy.includes(it,scope)}.map{it.identity.uid}.toSet()
                        snapshots.filter{it.identity.uid in rejected}
                    }}
                    val names=excluded.map{it.identity.packageName}.toSet()
                    if(store.entries().filter{it.identity.packageName in names}.any{e->e.groupIdentities.any{it.packageName !in names}})throw OwnershipChangedException()
                    complete(excluded.map{it.label to it.identity.packageName},"")
                }catch(_:Exception){complete(null,"등록 앱의 복원 범위를 확인하지 못했어요. 현재 범위를 그대로 둘게요. 기기 연결을 확인하고 다시 시도해주세요.")}
                finally{model=model.copy(busy=false,scopePreview=false,connected=controls.wireless||WatchClient.available(this@MainActivity),automaticReady=automaticReady())}
            }
        }){
            if(!model.busy){
                if(excluded.isNotEmpty()){
                    val targets=excluded.map{it.identity.packageName}.toSet()
                    if(scopes.beginRestore(scope,targets)){model=model.copy(scopePending=true);perform(targets.toList(),false)}
                    else model=model.copy(notice="적용 범위를 저장하지 못했어요.")
                }else if(scopes.select(scope))scopeSelected()else model=model.copy(notice="적용 범위를 저장하지 못했어요.")
            }
        }
    }
    private fun scopeSelected(){
        model=model.copy(applicationScope=scopes.current,scopePending=false,picked=emptySet(),approvedGroups=emptyList(),candidates=emptyList(),discovered=emptyList(),pickerRows=null,filter=AppFilter.ALL,notice="적용 범위를 변경했어요. 앱 추가에서 원하는 앱을 선택해주세요.")
        invalidateScan();refresh(true)
    }
    private fun clearNotice() { model=model.copy(notice="") }
}
/** When returning to the app re-reads registered apps. Only a check of all registered apps resets the ten-minute timer,
 *  and an expired timer wins over a partial check, so an app that changes often (e.g. Toss) cannot keep postponing the
 *  other apps' recheck (GPT review 2026-10-11). */
internal class RecheckClock(private val periodMs:Long){
    private var lastFull=0L
    val started get()=lastFull!=0L
    /** null: nothing to re-read; empty: all registered apps; otherwise only these changed apps. */
    fun plan(now:Long,touched:Set<String>):Set<String>?=when{now-lastFull>=periodMs->emptySet();touched.isNotEmpty()->touched;else->null}
    fun fullChecked(now:Long){lastFull=now}
}
