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
    private var wifiObserver:AutoCloseable?=null
    private val controls by lazy {WirelessControls(this)}
    private val scopes by lazy {ScopePreferences(this)}
    private val packageChanges=object:android.content.BroadcastReceiver(){override fun onReceive(c:android.content.Context,i:Intent){invalidateScan();if(!model.busy)model=model.copy(discovered=model.discovered.map{it.copy(check=CandidateCheck.NEEDS_CHECK)})}}
    private fun invalidateScan(){StartupScanHandoff.cache.invalidate();startupScanAdopted=false;packageEpoch++;scanStamp=null;model=model.copy(pickerRows=null,rejectedGroups=emptySet())}
    private fun cacheValid()=scanStamp?.valid(Build.FINGERPRINT,packageEpoch,android.os.SystemClock.elapsedRealtime())==true
    private fun automaticReady()=controls.consent&&controls.granted&&Settings.Global.getInt(contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1&&WifiConnection.connected(this)&&getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(this).exists
    private fun refreshFromUser(){invalidateScan();refresh(true)}
    private val prefs by lazy { getSharedPreferences("ui-state", MODE_PRIVATE) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model = model.copy(notifications = it, notificationOffer = false) }
    private val journalListener:()->Unit = { if(ForegroundState.active&&!model.busy)refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = JournalStore(this)
        model=model.copy(applicationScope=scopes.current,scopePending=scopes.pending!=null||scopes.requested!=null,scopeResolved=scopes.resolved)
        prefs.getString("setupNotice",null)?.let{model=model.copy(notice=it);prefs.edit().remove("setupNotice").apply()}
        LifecycleEvents.listener=journalListener
        lifecycleScope.launch {
            BatchRuntime.state.collect { state ->
                if(state.id==0L)return@collect
                if(state.busy){model=model.copy(busy=true,operationHide=state.hide,progress=state.progress,loadingApps=false,notice="")}
                else state.outcome?.let { outcome ->
                    val succeeded=outcome.results.filter{it.success}.map{it.packageName}.toSet()
                    val first=handledBatchId!=state.id;handledBatchId=state.id
                    val scopeChanged=model.applicationScope!=scopes.current
                    model=model.copy(busy=false,progress=null,completion=outcome,applicationScope=scopes.current,scopePending=scopes.pending!=null||scopes.requested!=null,
                        page=if(first&&outcome.hide&&model.page==Page.PICK&&outcome.results.isNotEmpty()&&outcome.results.all{it.success})Page.HOME else model.page,
                        entries=outcome.entries.ifEmpty{model.entries},
                        candidates=model.candidates.filterNot{s->outcome.snapshots.any{it.identity.packageName==s.identity.packageName}}+outcome.snapshots,
                        freshPackages=outcome.snapshots.map{it.identity.packageName}.toSet(),
                        connected=controls.wireless,automaticReady=automaticReady(),
                        picked=model.picked-succeeded,homePicked=model.homePicked-succeeded,
                        batchResults=outcome.results,wirelessWarning=outcome.cleanupWarning,
                        notice=if(first){(if(outcome.error.isBlank()&&outcome.results.all{it.success})"" else outcome.error.ifBlank{outcome.summary()}).ifBlank{skipNotice}.also{skipNotice=""}}else model.notice,
                        successToken=if(first&&outcome.hide&&succeeded.isNotEmpty())model.successToken+1 else model.successToken)
                    // Target results update display hints; they do not change package inventory.
                    // Keep the existing 5-minute/package-event expiry and fresh write preflights.
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
            model = model.copy(page = Page.valueOf(savedInstanceState.getString("page") ?: "HOME"), selected = savedInstanceState.getString("selected"), picked=savedInstanceState.getStringArrayList("picked")?.toSet()?:emptySet(),query=savedInstanceState.getString("query")?:"",filter=AppFilter.valueOf(savedInstanceState.getString("filter")?:"RECENT"),homePicked=savedInstanceState.getStringArrayList("homePicked")?.toSet()?:emptySet(),editing=savedInstanceState.getBoolean("editing"),historyView=HistoryView.valueOf(savedInstanceState.getString("historyView")?:"APPS"))
        }
        discovery=LocalAdbDiscovery(this){
            val guid=getSharedPreferences("local-adb",0).getString("pairedGuid",null)
            val current=discovery.candidates(LocalAdbDiscovery.CONNECT,guid).singleOrNull()?.port
            if(current!=endpointPort){
                endpointPort=current;model=model.copy(connected=false,freshPackages=emptySet())
                if(!model.busy){
                    if(startupScanAdopted&&cacheValid())model=model.copy(connected=controls.wireless,automaticReady=automaticReady())
                    else refresh()
                }
            }
        }
        discovery.start()
        val packageFilter=android.content.IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_REPLACED);addDataScheme("package")}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packageChanges,packageFilter,android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(packageChanges,packageFilter)
        wifiObserver=WifiConnection.observe(this){runOnUiThread{model=model.copy(automaticReady=automaticReady());if(ForegroundState.active&&!model.busy)refresh()}}
        navigateNotification(intent)
        setContent { BlueDotApp(model, ::navigate, ::select, ::requestHide, ::requestRestore, ::batchHide, ::restoreAll, ::openBackend, ::openBackend, ::openBackend, ::refreshFromUser, ::allowNotifications, ::dismissNotificationOffer, ::begin, ::loadApps, ::clearNotice, ::togglePick, ::addSelected, ::changeQuery, ::changeFilter, { model=model.copy(editing=!model.editing) }, ::toggleHomePick, ::restoreSelected, ::contentShown, pickerRevision, { model=model.copy(historyView=it) },pickerViewChanged={model=model.copy(pickerView=it,pickerRows=null);filterPicker()},wirelessExit={if(!model.busy)WirelessDebuggingExit.open(this)},toggleAll=::toggleAll,managementChanged=::managementChanged,removeManagementPermission=::removeManagementPermission,sharedInspect={pickGroups(listOf(it))},sharedAccept=::acceptShared,sharedDismiss={sharedGeneration++;model=model.copy(sharedOffer=null)},scopeChange=::chooseScope,reapplyNeeded={perform(it,true)},notificationChanged=::reapplyNotificationChanged) }
    }
    override fun onStart() { super.onStart(); ForegroundState.started() }
    override fun onStop() { interactionOpened=0L;ForegroundState.stopped(); super.onStop() }
    override fun onPause() { ForegroundState.paused();super.onPause() }
    override fun onResume() { super.onResume();ForegroundState.resumed();model=model.copy(notifications=reapplyNotificationsEnabled()); if (::store.isInitialized) refresh(true) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); navigateNotification(intent); refresh() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("page",model.page.name); out.putString("selected",model.selected); out.putStringArrayList("picked",ArrayList(model.picked));out.putString("query",model.query);out.putString("filter",model.filter.name);out.putString("historyView",model.historyView.name);out.putStringArrayList("homePicked",ArrayList(model.homePicked));out.putBoolean("editing",model.editing);super.onSaveInstanceState(out) }
    override fun onDestroy() { if(LifecycleEvents.listener===journalListener)LifecycleEvents.listener=null;discovery.close();wifiObserver?.close();unregisterReceiver(packageChanges); super.onDestroy() }
    private fun navigateNotification(intent: Intent?) {
        if (intent?.action != "app.bluedotoff.REAPPLY") return
        // An external Intent only navigates. No automatic setter is reachable here.
        val target = intent.getStringExtra("target")
        model = model.copy(page = if (target == null) Page.REAPPLY else Page.DETAIL, selected = target)
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
        }) { "복원 기록을 저장하지 못해 변경을 차단했습니다." }
        val current=store.entries()
        return current
    }
    private fun refresh(userAction:Boolean=false) {
        if((!userAction&&SetupSession.current()!=null)||model.busy||BatchRuntime.state.value.busy||refreshJob?.isActive==true)return
        refreshJob=lifecycleScope.launch {
            try {
                prefs.getString("setupNotice",null)?.let{cleanupNotice=it;prefs.edit().remove("setupNotice").apply()}
                val entries=withContext(Dispatchers.IO){markLocalEvents()}
                model=model.copy(applicationScope=scopes.current,scopePending=scopes.pending!=null||scopes.requested!=null,scopeResolved=scopes.resolved,entries=entries,usModel=prefs.getBoolean("usModel",false),reapplyNotify=prefs.getBoolean("reapplyNotify",true),notifications=reapplyNotificationsEnabled(),automaticReady=automaticReady(),autoManagement=controls.consent,autoPermission=controls.granted)
                if(!prefs.getBoolean("welcomed",false)){model=model.copy(gate=Gate.WELCOME);return@launch}
                if(!DeviceSupport.basic()){model=model.copy(gate=Gate.UNSUPPORTED,compatibility="Samsung One UI 9 이상에서 지원 여부를 확인할 수 있어요.");return@launch}
                val dev=Settings.Global.getInt(contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1
                val wireless=controls.wireless
                val paired=getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(this@MainActivity).exists
                if(!dev||!WifiConnection.connected(this@MainActivity)||!paired||(!wireless&&!model.automaticReady)){
                    model=model.copy(gate=if(entries.any{it.enabled}||model.automaticReady)Gate.READY else Gate.NOT_RUNNING,connected=false,freshPackages=emptySet(),compatibility=when{!dev->"먼저 개발자 옵션을 켜주세요.";!WifiConnection.connected(this@MainActivity)->"먼저 Wi-Fi에 연결해주세요.";!paired->"이 기기를 한 번 페어링해주세요.";model.automaticReady->"필요한 작업 때 다시 연결합니다.";else->"무선 디버깅을 켜주세요."})
                    return@launch
                }
                if(!scopes.resolved){openBackend();return@launch}
                val prepared=withContext(Dispatchers.IO){StartupScanHandoff.cache.take(Build.FINGERPRINT,BootSession.current(this@MainActivity),entries)}
                if(prepared!=null){
                    startupScanAdopted=true
                    val scan=prepared.discovery
                    scanStamp=ScanStamp(Build.FINGERPRINT,packageEpoch,android.os.SystemClock.elapsedRealtime())
                    model=model.copy(gate=Gate.READY,entries=entries,candidates=prepared.snapshots,discovered=scan.apps,
                        historyAvailable=scan.historyAvailable,metrics=scan.metrics,loadingApps=false,
                        connected=wireless,automaticReady=automaticReady(),freshPackages=emptySet(),compatibility="앱 확인 완료",
                        notice=cleanupNotice,notificationOffer=!notificationsEnabled()&&!prefs.getBoolean("notificationExplained",false))
                    filterPicker()
                    if(model.page==Page.DETAIL&&model.selected!=null&&prepared.snapshots.none{it.identity.packageName==model.selected})select(model.selected!!)
                    return@launch
                }
                if(!wireless&&!userAction){
                    model=model.copy(gate=Gate.READY,connected=false,freshPackages=emptySet(),compatibility="필요한 작업 때 다시 연결합니다.")
                    return@launch
                }
                startupScanAdopted=false
                val reuseInventory=cacheValid()&&model.discovered.isNotEmpty()
                model=model.copy(gate=Gate.CHECKING,scanProgress=null,checkingRegistered=reuseInventory,loadingApps=!reuseInventory,compatibility="앱 확인 중…")
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
                                runOnUiThread{if(coroutine.isActive&&model.gate==Gate.CHECKING&&epoch==packageEpoch)model=model.copy(scanProgress=progress)}
                            }
                        }
                    }
                    localBatch(userAction){
                    val firmware=backend.verify();val boot=BootSession.current(this@MainActivity)
                    if(cacheValid()&&model.discovered.isNotEmpty()){
                        val registered=store.entries()
                        reportScan(0,registered.size)
                        val current=registered.mapIndexed{index,e->
                            val snapshot=try{backend.read(e.identity.packageName)}catch(_:PackageManager.NameNotFoundException){null}
                            reportScan(index+1,registered.size)
                            e.identity.packageName to snapshot
                        }.toMap()
                        snapshots.addAll(current.values.filterNotNull());check(store.transform{e->Reconcile.read(e,current[e.identity.packageName],firmware.key,boot,current)})
                        // The connection is open anyway: refresh the display-only location history (about a second)
                        // so apps used since the last full check appear. Inventory and its 5-minute expiry are unchanged.
                        refreshedHistory=runCatching{backend.refreshHistory(knownApps){coroutine.ensureActive()}}.getOrNull()
                        null
                    }else{
                        val prepared=StartupScan.read({firmware},store::entries,
                            {capture->backend.discover(checkCancelled={coroutine.ensureActive()},onSnapshot=capture,onProgress=reportScan)},
                            {pkg->try{backend.read(pkg)}catch(_:PackageManager.NameNotFoundException){null}},
                            {targets,fw->check(store.transform{e->Reconcile.read(e,targets[e.identity.packageName],fw.key,boot,targets)});store.entries()},
                            boot,{coroutine.isActive})
                        snapshots.addAll(prepared.snapshots)
                        prepared.discovery
                    }
                }}
                if(scan!=null){scanStamp=ScanStamp(Build.FINGERPRINT,epoch,android.os.SystemClock.elapsedRealtime());model=model.copy(discovered=scan.apps,historyAvailable=scan.historyAvailable,metrics=scan.metrics)}
                else refreshedHistory?.let{if(epoch==packageEpoch&&model.discovered===knownApps)model=model.copy(discovered=it,historyAvailable=true)}
                model=model.copy(gate=Gate.READY,scanProgress=null,entries=store.entries(),candidates=snapshots,loadingApps=false,connected=controls.wireless,automaticReady=automaticReady(),freshPackages=if(controls.wireless)snapshots.map{it.identity.packageName}.toSet() else emptySet(),compatibility="앱 확인 완료",notice=cleanupNotice,notificationOffer=!notificationsEnabled()&&!prefs.getBoolean("notificationExplained",false))
                filterPicker()
                if(model.page==Page.DETAIL&&model.selected!=null&&snapshots.none{it.identity.packageName==model.selected})select(model.selected!!)
            }catch(_:CancellationException){}catch(error:Exception){android.util.Log.w("bluedotOFF","refresh failed: ${error.javaClass.simpleName}: ${error.message?.take(160)}");model=model.copy(gate=if(model.entries.any{it.enabled})Gate.READY else Gate.NOT_RUNNING,loadingApps=false,connected=false,freshPackages=emptySet(),compatibility="현재 기기의 변경 작업을 확인할 수 없어요. 연결 준비에서 다시 확인해주세요.",notice=cleanupNotice)}
        }
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
    private fun navigate(page: Page) { if(page!=model.page)interactionOpened=0L;if(page==Page.PICK){pickerOpened=android.os.SystemClock.elapsedRealtime();scanFrameRecorded=false};model=model.copy(page=page); if(page in listOf(Page.PICK,Page.HISTORY)&&(!cacheValid()||model.discovered.isEmpty()))refresh(true) }
    private fun select(pkg:String) {
        model=model.copy(page=Page.DETAIL,selected=pkg,loadingDetail=true)
        lifecycleScope.launch {
            try {
                val start=android.os.SystemClock.elapsedRealtime()
                val s=withContext(Dispatchers.IO){localBatch{backend.read(pkg)}}
                val ms=android.os.SystemClock.elapsedRealtime()-start
                if(model.selected==pkg)model=model.copy(candidates=model.candidates.filterNot{it.identity.packageName==pkg}+s,loadingDetail=false,freshPackages=model.freshPackages+pkg,metrics=model.metrics.copy(targetPreflightMs=ms))
                withContext(Dispatchers.IO){backend.recordUiMetric("target_detail_ms",ms)}
            }catch(_:Exception){if(model.selected==pkg)model=model.copy(loadingDetail=false,notice="앱 상태를 확인하지 못했습니다.")}
        }
    }
    private fun togglePick(pkg:String) {if(model.busy)return;val group=model.selectionGroup(pkg);if(pkg in model.picked){model=model.copy(picked=model.picked-group.toSet());return};if(model.displayApps.none{it.packageName==pkg&&model.canSelect(it)})return;model=model.copy(picked=model.picked+group)}
    private fun toggleAll(){if(!model.busy&&!model.loadingApps){val covered=model.pickerApps.filter{model.canSelect(it)}.flatMap{model.selectionGroup(it.packageName)}.toSet();val groups=model.pickerApps.filter{model.canPickGroup(it)}.map{it.packageName}
        if(groups.isEmpty()){if(covered.isNotEmpty())model=model.copy(picked=if(covered.all{it in model.picked})model.picked-covered else model.picked+covered)}else{model=model.copy(picked=model.picked+covered);pickGroups(groups)}}}
    private fun toggleHomePick(pkg:String) { if(model.busy)return;model=model.copy(homePicked=if(pkg in model.homePicked)model.homePicked-(model.entries.firstOrNull{it.identity.packageName==pkg}?.groupIdentities?.map{it.packageName}?.takeIf{it.isNotEmpty()}?:listOf(pkg)).toSet() else model.homePicked+(model.entries.firstOrNull{it.identity.packageName==pkg}?.groupIdentities?.map{it.packageName}?.takeIf{it.isNotEmpty()}?:listOf(pkg))) }
    private fun restoreSelected() = perform(model.homePicked.toList(),false)
    private fun addSelected() = perform(model.picked.toList(),true)
    private fun requestHide(pkg: String) {if(model.entries.any{it.identity.packageName==pkg&&it.groupIdentities.isNotEmpty()}||model.displayApps.any{it.packageName==pkg&&model.canInspectGroup(it)})inspectShared(pkg,true,true)else perform(listOf(pkg),hide=true)}
    private fun requestRestore(pkg: String) {if(model.entries.any{it.identity.packageName==pkg&&it.groupIdentities.isNotEmpty()})inspectShared(pkg,false)else perform(listOf(pkg),hide=false)}
    private fun batchHide() = perform(model.entries.filter { it.enabled && it.state !in listOf(TargetState.FAILED_ROLLBACK,TargetState.INACTIVE_INSTALL,TargetState.MISSING_TARGET,TargetState.SHARED_UID_BLOCKED,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW,TargetState.UNSUPPORTED_STATE) }.map { it.identity.packageName }, hide = true)
    private fun restoreAll() = perform(model.entries.filter { it.enabled }.map { it.identity.packageName }, hide = false)
    private fun perform(requested: List<String>, hide: Boolean) {
        val (packages,needsCheck)=if(hide)requested to 0 else model.restorePlan(requested)
        if(packages.isEmpty()&&requested.isNotEmpty()&&!model.busy){model=model.copy(notice=if(needsCheck==0)"삭제된 앱은 복원할 필요가 없어요. 앱을 지우면 위치 표시 설정도 함께 사라져요." else "선택한 앱은 지금 복원할 수 없어요. 앱을 하나씩 열어 상태를 확인해주세요.");return}
        if(model.busy||BatchRuntime.state.value.busy||packages.isEmpty())return
        if(!BuildConfig.LOCAL_WRITES_ENABLED){model=model.copy(notice="읽기 전용 검증 빌드입니다. 대상 설정은 변경하지 않습니다.");return}
        if(!model.canOperate||model.gate!=Gate.READY){model=model.copy(notice="연결 설정에서 기기를 연결한 뒤 다시 눌러 주세요.");return}
        refreshJob?.cancel();discoveryJob?.cancel();discoveryGeneration++
        StartupScanHandoff.cache.invalidate()
        val groups=(model.approvedGroups+model.entries.map{it.groupIdentities.map{p->p.packageName}}.filter{it.isNotEmpty()}).distinct()
        val units=packages.distinct().map{p->groups.firstOrNull{p in it}?:listOf(p)}.distinct()
        val targets=units.flatten().distinct()
        val groupMap=groups.flatMap{g->g.map{it to g}}.toMap()
        val app=applicationContext
        val journal=JournalStore(app)
        val runtimePrefs=app.getSharedPreferences("ui-state",MODE_PRIVATE)
        val started=BatchRuntime.start(targets.size,hide) { report ->
            val results=mutableListOf<BatchResult>()
            val observed=linkedMapOf<String,Snapshot>()
            val runner=TargetBatch.Runner()
            var pipelineStopped=false
            var warning=""
            var failure=""
            var cursor=0
            try {
                WirelessWork.runBatch(app,true,onCleanup={if(it.isNotBlank())warning=it}) { session ->
                while(cursor<targets.size&&!runner.stopped&&!pipelineStopped) {
                    if(!ForegroundState.canStartTargets){failure="화면을 떠나 남은 앱은 변경하지 않았습니다. 완료된 적용과 등록 기록은 유지됩니다.";break}
                    report(BatchProgress(cursor,targets.size,BatchPhase.CONNECTING))
                    session.run { active ->
                        val engine=SafetyEngine(active,journal,System::currentTimeMillis,{BootSession.current(app)},observe={phase,snapshot,wrote->
                            active.recordVerification(phase,snapshot,wrote)
                            if(phase=="settled_1500ms")observed[snapshot.identity.packageName]=snapshot
                        })
                        val shared=groupMap[targets[cursor]]
                        if(shared!=null){
                            val groupResults=try{
                                val members=SharedGroupEngine(active,journal,System::currentTimeMillis,{BootSession.current(app)},observe={phase,s,wrote->active.recordVerification(phase,s,wrote);if(phase=="settled_1500ms")observed[s.identity.packageName]=s}).execute(shared,hide){ForegroundState.canStartTargets}
                                members.map{e->BatchResult(e.identity.packageName,e,if(e.state in setOf(TargetState.APPLIED,TargetState.NOT_APPLIED))"" else e.error.ifBlank{"현재 상태 확인 필요"})}
                            }catch(e:Exception){
                                val saved=runCatching{journal.entries()}.getOrDefault(emptyList())
                                shared.map{p->BatchResult(p,saved.lastOrNull{it.identity.packageName==p},e.message?:"공유 앱 묶음 확인이 필요합니다.")}
                            }
                            results.addAll(groupResults);cursor+=shared.size;pipelineStopped=groupResults.any{!it.success}
                            if(pipelineStopped){failure=groupResults.first{!it.success}.error;shared.forEach{observed.remove(it)}}
                            report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                        } else if(targets.size>1) {
                            val completedBefore=results.size
                            var completed=0
                            val window=if(hide)engine.userHideWindow(targets.drop(cursor).takeWhile{it !in groupMap}.take(BatchWindow.MAX_TARGETS),
                                canStart={ForegroundState.canStartTargets},elapsed=android.os.SystemClock::elapsedRealtime,onResult={result->
                                    if(!result.success)observed.remove(result.packageName)
                                    completed++;report(BatchProgress(completedBefore+completed,targets.size,BatchPhase.CHECKING))
                                }) else engine.userRestoreWindow(targets.drop(cursor).takeWhile{it !in groupMap}.take(BatchWindow.MAX_TARGETS),
                                canStart={ForegroundState.canStartTargets},elapsed=android.os.SystemClock::elapsedRealtime,onResult={result->
                                    if(!result.success)observed.remove(result.packageName)
                                    completed++
                                    report(BatchProgress(completedBefore+completed,targets.size,BatchPhase.CHECKING))
                                })
                            results.addAll(window.results);cursor+=window.started;pipelineStopped=window.stopped
                            if(window.reason.isNotBlank())failure=window.reason
                        } else {
                            val start=android.os.SystemClock.elapsedRealtime()
                            var count=0
                            while(cursor<targets.size&&!runner.stopped&&BatchWindow.next(count,android.os.SystemClock.elapsedRealtime()-start)) {
                                if(!ForegroundState.canStartTargets){failure="화면을 떠나 남은 앱은 변경하지 않았습니다. 완료된 적용과 등록 기록은 유지됩니다.";break}
                                report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                                val pkg=targets[cursor]
                                val result=runner.run(pkg){engine.userOne(it,hide){ForegroundState.canStartTargets}}
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
            }catch(e:Exception){failure=e.message?:"현재 상태를 확인하지 못했습니다."}
            val untouched=if(runner.stopped||pipelineStopped)"앞선 작업의 복원 또는 연결 확인이 필요해 변경하지 않았습니다." else "작업을 중단해 이 앱은 변경하지 않았습니다."
            while(cursor<targets.size){results.add(BatchResult(targets[cursor++],error=untouched))}
            val outcome=BatchOutcome(hide,results,journal.entries(),observed.values.toList(),warning,failure)
            if(!hide&&ScopePreferences(app).pending!=null&&!ScopePreferences(app).finishRestore(outcome))return@start outcome.copy(error=outcome.error.ifBlank{"일부 앱의 복원을 확인하지 못해 이전 적용 범위를 유지했어요. 설정에서 다시 확인해주세요."})
            outcome
        }
        if(started)skipNotice=if(needsCheck>0)"확인이 필요한 ${needsCheck}개 앱은 건너뛰었어요. 홈 목록에서 상태를 확인해주세요." else ""
        if(started)model=model.copy(busy=true,operationHide=hide,progress=BatchProgress(0,targets.size),completion=null,loadingApps=false,notice="",wirelessWarning="",batchResults=emptyList())
    }
    private fun openBackend(){refreshJob?.cancel();discoveryJob?.cancel();invalidateScan();startActivity(Intent(this,LocalAdbSetupActivity::class.java))}
    private fun <T> localBatch(userAction:Boolean=true,block:()->T):T {
        return WirelessWork.run(this,userAction,endpointPort,onCleanup={cleanupNotice=it}){active->
            backend=active
            engine=SafetyEngine(active,store,System::currentTimeMillis,{BootSession.current(this)},observe=active::recordVerification)
            block()
        }
    }
    private fun managementChanged(enabled:Boolean){
        if(!enabled){controls.setConsent(false);model=model.copy(autoManagement=false,automaticReady=false);return}
        if(model.busy){model=model.copy(notice="작업이 끝난 뒤 자동 관리를 켜주세요.");return}
        val paired=getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(this).exists
        if(!paired||!controls.wireless){model=model.copy(notice="먼저 연결 준비를 완료해주세요.");openBackend();return}
        controls.setConsent(true);model=model.copy(busy=true)
        lifecycleScope.launch {
            try {withContext(Dispatchers.IO){localBatch{if(!controls.granted)(backend as LocalAdbBackend).grantSelfAutomaticPermission()}};model=model.copy(autoManagement=true,autoPermission=controls.granted,automaticReady=automaticReady(),connected=controls.wireless,notice=cleanupNotice)}
            catch(_:Exception){controls.setConsent(false);model=model.copy(autoManagement=false,automaticReady=false,notice="자동 관리를 켜지 못했습니다. 수동 연결은 계속 사용할 수 있어요.")}
            finally{model=model.copy(busy=false)}
        }
    }
    private fun removeManagementPermission(){
        controls.setConsent(false);model=model.copy(autoManagement=false,automaticReady=false)
        if(model.busy||!controls.wireless){model=model.copy(notice="자동 관리는 꺼졌습니다. 권한을 제거하려면 무선 디버깅으로 연결해주세요.");return}
        model=model.copy(busy=true)
        lifecycleScope.launch{try{withContext(Dispatchers.IO){localBatch(false){(backend as LocalAdbBackend).revokeSelfAutomaticPermission()}};model=model.copy(autoPermission=controls.granted,notice="자동 관리 권한을 제거했습니다.")}catch(_:Exception){model=model.copy(notice="자동 관리는 꺼졌지만 권한 제거를 확인하지 못했습니다.")}finally{model=model.copy(busy=false)}}
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
                    }.getOrElse{Triple(pkg,emptyList<Snapshot>(),it.message?:"공유 앱 묶음을 확인하지 못했습니다.")}}
                }}
                val valid=checked.filter{it.third==null}.map{it.second}
                val names=valid.map{g->g.map{it.identity.packageName}.sorted()}
                val failed=checked.filter{it.third!=null}
                val rejected=failed.flatMap{f->f.second.map{it.identity.packageName}+f.first}.toSet()
                model=model.copy(approvedGroups=(model.approvedGroups+names).distinct(),
                    candidates=model.candidates.filterNot{c->names.flatten().contains(c.identity.packageName)}+valid.flatten(),
                    picked=model.picked+names.flatten(),rejectedGroups=model.rejectedGroups+rejected,
                    notice=when{failed.isEmpty()->"";checked.size==1->UiText.error(failed.single().third!!);else->"조건이 맞지 않는 ${failed.size}개 묶음은 선택하지 않았어요."})
            }catch(_:Exception){model=model.copy(notice="공유 앱 묶음을 확인하지 못했습니다.")}
            finally{model=model.copy(loadingApps=false,connected=controls.wireless,automaticReady=automaticReady());filterPicker()}
        }
    }
    private fun inspectShared(pkg:String,hide:Boolean=true,direct:Boolean=false){
        if(model.busy||!model.canOperate)return
        val revision=++sharedGeneration
        model=model.copy(sharedOffer=SharedOffer(emptyList(),hide,loading=true))
        lifecycleScope.launch{try{
            val members=withContext(Dispatchers.IO){localBatch(true){backend.read(pkg).peers.sorted().map{backend.read(it)}}}
            val error=runCatching{SharedGroupPolicy.validate(members)}.exceptionOrNull()?.message.orEmpty()
            if(revision==sharedGeneration)model=model.copy(sharedOffer=SharedOffer(members,hide,error=error,direct=direct),connected=controls.wireless,automaticReady=automaticReady(),wirelessWarning=cleanupNotice)
        }catch(e:Exception){if(revision==sharedGeneration)model=model.copy(sharedOffer=SharedOffer(emptyList(),hide,error=e.message?:"공유 앱 묶음을 확인하지 못했습니다."))}}
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
                if(!model.busy){if(scopes.select(scope))scopeSelected()else model=model.copy(notice="적용 범위를 저장하지 못했습니다.")}
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
                }catch(_:Exception){complete(null,"등록 앱의 복원 범위를 확인하지 못했어요. 현재 범위를 유지합니다. 기기 연결을 확인하고 다시 시도해주세요.")}
                finally{model=model.copy(busy=false,scopePreview=false,connected=controls.wireless,automaticReady=automaticReady())}
            }
        }){
            if(!model.busy){
                if(excluded.isNotEmpty()){
                    val targets=excluded.map{it.identity.packageName}.toSet()
                    if(scopes.beginRestore(scope,targets)){model=model.copy(scopePending=true);perform(targets.toList(),false)}
                    else model=model.copy(notice="적용 범위를 저장하지 못했습니다.")
                }else if(scopes.select(scope))scopeSelected()else model=model.copy(notice="적용 범위를 저장하지 못했습니다.")
            }
        }
    }
    private fun scopeSelected(){
        model=model.copy(applicationScope=scopes.current,scopePending=false,picked=emptySet(),approvedGroups=emptyList(),candidates=emptyList(),discovered=emptyList(),pickerRows=null,filter=AppFilter.ALL,notice="적용 범위를 변경했어요. 앱 추가에서 원하는 앱을 선택해주세요.")
        invalidateScan();refresh(true)
    }
    private fun clearNotice() { model=model.copy(notice="") }
}
