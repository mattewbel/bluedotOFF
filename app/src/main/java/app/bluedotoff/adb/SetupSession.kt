package app.bluedotoff.adb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.bluedotoff.core.*
import java.util.concurrent.Executors

/** Bounded process-local session. A short pairing-only service keeps callbacks alive in Settings. */
class SetupSession internal constructor(private val context:Context,private val runtime:SetupRuntime=AndroidSetupRuntime(context),private val worker:java.util.concurrent.ExecutorService=Executors.newSingleThreadExecutor()) {
    companion object {
        private var active:SetupSession?=null
        fun acquire(context:Context,wirelessSetup:Boolean=false):SetupSession {
            if(wirelessSetup&&active?.runtime?.pcStart==true)active?.close()
            return active?:SetupSession(context.applicationContext,AndroidSetupRuntime(context.applicationContext,wirelessSetup)).also{active=it}
        }
        fun current()=active
    }
    private val main=Handler(Looper.getMainLooper())
    private val lease=PairingLease(SystemClock::elapsedRealtime)
    private val prefs=context.getSharedPreferences("local-adb",0)
    private val ui=context.getSharedPreferences("ui-state",0)
    private var discovery:SetupDiscoveryAccess?=null
    private var wifiObserver:AutoCloseable?=null
    private var lastWifi:Boolean?=null
    private var deadline=0L
    private var token=0L
    private var endpoint:LocalAdbDiscovery.Endpoint?=null
    private var connecting=false
    private var verifying=false
    private var pairing=false
    private var forcePairing=false
    private var verified=false
    private var verifiedPort:Int?=null
    private var finalizing=false
    private var managing=false
    private var settingsHandoff=false
    var managementError="";private set
    private var error:SetupStage?=null
    private var attempts=0
    private var lastWireless=false
    private var lastHelper:Boolean?=null
    @Volatile private var closed=false
    private var operationEpoch=0L
    private var manual=false
    private var notices=PairNotice.NONE
    private var notifiedToken=-1L
    private var invalidCode=false
    private var held:LocalAdbPolicy.PairReply?=null
    private var notificationRequestPending=false
    var diagnosticResult="";private set
    var scanProgress:ScanProgress?=null;private set
    val sessionId=java.util.UUID.randomUUID().toString()
    var listener:(()->Unit)?=null
    var facts=SetupFacts();private set
    init {runtime.initializeOnboarding()}
    val notifications get()=runtime.notifications
    val broad get()=runtime.broad
    val requiresNearbyPermission get()=runtime.localNetworkPermissionRequired
    val pairingNeeded get()=!runtime.paired||forcePairing
    val nearbyRequested get()=prefs.getBoolean("nearbyAsked",false)
    val needsNearbyBeforeSettings get()=pairingNeeded&&runtime.localNetworkPermissionRequired&&!broad&&!nearbyRequested
    val manualInput get()=manual||!notifications
    val needsPort get()=discovery?.candidates(LocalAdbDiscovery.PAIR)?.size!=1
    val inputError get()=invalidCode
    // PC start waits for a command on the PC without a deadline; the tick is what notices the helper arriving.
    private val tick=object:Runnable {override fun run(){if(closed)return;if(SystemClock.elapsedRealtime()>=deadline){if(runtime.pcStart)deadline=SystemClock.elapsedRealtime()+300000 else{expire();return}};refresh();main.postDelayed(this,500)}}
    fun attach(callback:()->Unit){listener=callback;if(discovery==null&&error!=SetupStage.SESSION_EXPIRED&&!closed)observe();refresh()}
    fun detach(callback:()->Unit){if(listener===callback)listener=null}
    fun resume(){if(closed||managing||finalizing)return;runtime.foregroundUse();if(verified)attempts=0;verified=false;verifiedPort=null;if(discovery==null&&error!=SetupStage.SESSION_EXPIRED)observe();refresh()}
    private fun observe(){
        app.bluedotoff.StartupScanHandoff.cache.invalidate()
        operationEpoch++
        // Work of the old epoch can no longer report back: clear its in-flight marks so a new check can start (Codex R1).
        connecting=false;verifying=false;finalizing=false;scanProgress=null
        PairingNotifications.prepare(context)
        if(wifiObserver==null)wifiObserver=runtime.observeWifi{main.post{if(!closed)refresh()}}
        deadline=SystemClock.elapsedRealtime()+300000
        discovery?.close()
        val observedEpoch=operationEpoch
        discovery=runtime.discover{main.post{if(!closed&&operationEpoch==observedEpoch&&discovery!=null)refresh()}}
        main.removeCallbacks(tick);main.postDelayed(tick,500)
    }
    fun permissionsChanged(){error=null;verified=false;attempts=0;observe();refresh()}
    fun welcome(){ui.edit().putBoolean("welcomed",true).apply();refresh()}
    fun notificationsAsked(){notificationRequestPending=true;refresh()}
    fun notificationsResult(){
        notificationRequestPending=false
        prefs.edit().putBoolean("notificationAsked",notifications).apply()
        ui.edit().putBoolean("notificationExplained",notifications).apply()
        refresh()
    }
    fun skipNotifications(){notificationRequestPending=false;prefs.edit().putBoolean("notificationAsked",true).apply();ui.edit().putBoolean("notificationExplained",true).apply();refresh()}
    fun nearbyAsked(){prefs.edit().putBoolean("nearbyAsked",true).apply()}
    /** Arm while visible, before the user enables wireless debugging in Settings. */
    fun prepareSettingsHandoff(){
        if(closed||error!=null||!pairingNeeded||!runtime.developer||!runtime.wifiConnected||manualInput||(!broad&&!nearbyRequested))return
        settingsHandoff=true
        if(!runtime.keepPairingAlive(true)){settingsHandoff=false;manual=true}
        refresh()
    }
    fun chooseScope(scope:ApplicationScope){
        if(SetupFlow.stage(facts)!=SetupStage.APPLICATION_SCOPE)return
        check(app.bluedotoff.ScopePreferences(context).selectInitial(scope)){"적용 범위를 저장하지 못했어요."}
        app.bluedotoff.StartupScanHandoff.cache.invalidate();refresh()
    }
    fun skipManagement(){if(managing||!verified)return;runtime.skipManagement();managementError="";refresh()}
    fun skipBatteryChoice(){if(closed||SetupFlow.stage(facts)!=SetupStage.BATTERY_OPTIMIZATION)return;runtime.skipBatteryChoice();refresh()}
    /** Battery settings cannot grant ADB access. Re-read facts without discarding the
     * verified connection; refresh still invalidates it if Wi-Fi/debugging changed. */
    fun batterySettingsReturned(){if(!closed)refresh()}
    fun enableManagement(){
        val port=verifiedPort?:return
        if(closed||managing||!verified||runtime.busy||SetupFlow.stage(facts)!=SetupStage.AUTOMATIC_MANAGEMENT)return
        managing=true;managementError="";refresh()
        val epoch=operationEpoch
        worker.execute{
            if(closed||epoch!=operationEpoch||!verified)return@execute
            try {runtime.enableManagement(port);main.post{if(!closed&&operationEpoch==epoch){managing=false;refresh()}}}
            catch(_:Exception){main.post{if(!closed&&operationEpoch==epoch){managing=false;managementError="자동 관리를 켜지 못했어요. 직접 연결하는 방식은 계속 쓸 수 있어요.";refresh()}}}
        }
    }
    fun skipNearby(){nearbyAsked();permissionsChanged();manual=true;refresh()}
    fun beginPairing(){
        error=null;forcePairing=true;verified=false;attempts=0;invalidCode=false;cancelInput();observe();refresh()
    }
    fun openManual(){manual=true;refresh()}
    /** "Wi-Fi가 없나요?" -> "PC로 시작" (and back). The wireless pairing state is left as it is. */
    fun choosePcStart(on:Boolean){if(closed)return;runtime.choosePcStart(on);error=null;verified=false;verifiedPort=null;attempts=0;cancelInput();refresh()}
    fun chooseConnection(){error=null;verified=false;attempts=0;discovery?.choose(LocalAdbDiscovery.CONNECT);refresh()}
    fun retryConnection(){error=null;verified=false;attempts=0;if(discovery==null)observe();refresh()}
    fun debugReadOnly(interrupt:Boolean){
        check(app.bluedotoff.BuildConfig.DEBUG)
        if(discovery==null)observe()
        val port=discovery?.candidates(LocalAdbDiscovery.CONNECT,prefs.getString("pairedGuid",null))?.singleOrNull()?.port?:return
        if(pairing||connecting||verifying||runtime.busy)return
        verified=false;verifying=true;error=null;diagnosticResult="읽기 전용 검사 중…";refresh()
        val epoch=operationEpoch
        worker.execute{try{runtime.debugReadOnly(port,interrupt);main.post{if(!closed&&epoch==operationEpoch){verifying=false;verified=true;diagnosticResult=if(interrupt)"INTERRUPTION_PASS · 재연결·전체 읽기 확인 · 대상 write 0회" else "READ_ONLY_PASS · 전체 읽기 확인 · 대상 write 0회";refresh()}}}catch(_:Exception){main.post{if(!closed&&epoch==operationEpoch){verifying=false;verified=false;diagnosticResult="진단 실패 · 연결 준비에서 다시 확인해주세요.";error=SetupStage.CONNECTION_RECOVERY;refresh()}}}}
    }
    fun debugBatchReadOnly(){
        check(app.bluedotoff.BuildConfig.DEBUG)
        if(pairing||connecting||verifying||runtime.busy)return
        verifying=true;error=null;diagnosticResult="읽기 전용 검사 중…";refresh()
        val epoch=operationEpoch
        worker.execute{try{runtime.debugBatchReadOnly();main.post{if(!closed&&epoch==operationEpoch){verifying=false;diagnosticResult="BATCH_READ_ONLY_PASS · 7개 구간 확인 · 대상 write 0회";refresh()}}}catch(_:Exception){main.post{if(!closed&&epoch==operationEpoch){verifying=false;diagnosticResult="진단 실패 · 연결 준비에서 다시 확인해주세요.";error=SetupStage.CONNECTION_RECOVERY;refresh()}}}}
    }
    private fun cancelInput(){held?.close();held=null;lease.cancel();token=-1;endpoint=null;invalidCode=false;PairingNotifications.cancel(context);notices=PairNotice.NONE;notifiedToken=-1}
    internal fun pairingLifetimeExpired(){if(!closed)expire()}
    private fun expire(){app.bluedotoff.StartupScanHandoff.cache.invalidate();runtime.keepPairingAlive(false);wifiObserver?.close();wifiObserver=null;operationEpoch++;cancelInput();runtime.cancelPairing();discovery?.close();discovery=null;pairing=false;connecting=false;verifying=false;verified=false;error=SetupStage.SESSION_EXPIRED;refresh()}
    fun close(keepScan:Boolean=false){if(closed)return;if(!keepScan)app.bluedotoff.StartupScanHandoff.cache.invalidate();runtime.keepPairingAlive(false);wifiObserver?.close();wifiObserver=null;closed=true;operationEpoch++;cancelInput();runtime.cancelPairing();main.removeCallbacksAndMessages(null);discovery?.close();discovery=null;listener=null;worker.shutdown();if(active===this)active=null}
    fun refresh(){
        if(closed)return
        val wifi=runtime.wifiConnected
        val pc=runtime.pcStart
        val helper=pc&&runtime.pcHelper
        // PC start does not use Wi-Fi: a Wi-Fi change must not restart discovery and drop the running helper check (Codex R1).
        if(lastWifi!=wifi){val prior=lastWifi;lastWifi=wifi;if(!pc&&prior==false&&wifi&&discovery!=null)observe()}
        // A newly started helper (the command run again) gets fresh attempts, without an old failure (Codex R4).
        if(lastHelper!=helper){val prior=lastHelper;lastHelper=helper;if(helper&&prior==false){error=null;attempts=0}}
        val dev=runtime.developer
        val wireless=runtime.wireless
        val paired=runtime.paired
        if(lastWireless!=wireless){lastWireless=wireless;if(!pc&&!finalizing&&(!runtime.intentionalExit||wireless)){verified=false;verifiedPort=null;attempts=0;error=null;if(!wireless){app.bluedotoff.StartupScanHandoff.cache.invalidate();settingsHandoff=false;operationEpoch++;cancelInput();runtime.cancelPairing();pairing=false;connecting=false;verifying=false}}}
        // PC start depends on the helper only; Wi-Fi and wireless debugging do not matter there.
        if(if(pc)!dev||!helper else !dev||!wifi||(!wireless&&!runtime.intentionalExit)){app.bluedotoff.StartupScanHandoff.cache.invalidate();verified=false;verifiedPort=null;managing=false;cancelInput()}
        // A late, resolved own-device endpoint resolves discovery failure only.
        // Authentication failure still requires the user's bounded retry action.
        if(error==SetupStage.DISCOVERY_RECOVERY&&paired&&!forcePairing&&attempts<2&&
            discovery?.candidates(LocalAdbDiscovery.CONNECT,prefs.getString("pairedGuid",null))?.size==1)error=null
        val eligible=dev&&runtime.wifiConnected&&wireless&&(!paired||forcePairing)&&!pairing&&!connecting&&!verifying&&error==null
        val found=if(eligible)discovery?.candidates(LocalAdbDiscovery.PAIR)?.singleOrNull() else null
        // The code prompt (and its lease) starts with wireless debugging, before the pairing port is announced (owner
        // 2026-10-21); finding the port keeps it, losing the port (code dialog closed) replaces it.
        if(!pairing){
            if(if(eligible)endpoint!=null&&found!=endpoint else endpoint!=null||lease.current(token))cancelInput()
            endpoint=found
            if(eligible&&!lease.current(token))token=lease.begin()
        }
        facts=SetupFacts(
            welcomed=ui.getBoolean("welcomed",false),notificationResolved=!notificationRequestPending&&(notifications||prefs.getBoolean("notificationAsked",false)||ui.getBoolean("notificationExplained",false)),
            developer=dev,wireless=wireless,supported=runtime.supported,wifiConnected=runtime.wifiConnected,
            paired=paired,nearbyResolved=broad||prefs.getBoolean("nearbyAsked",false),sessionStarted=discovery!=null,
            pairAdvertisement=found!=null,pairing=pairing,connecting=connecting,verifying=verifying,verified=verified,forcePairing=forcePairing,error=error,completedOffline=runtime.intentionalExit&&!wireless,
            managementChoiceResolved=runtime.managementChoiceResolved,managing=managing,applicationScopeResolved=app.bluedotoff.ScopePreferences(context).resolved,finalCheckResolved=runtime.finalCheckResolved,
            autoBlocked=runtime.autoBlocked,batteryChoiceRequired=runtime.batteryChoiceRequired,batteryChoiceResolved=runtime.batteryChoiceResolved,
            pcStart=pc,pcHelper=helper
        )
        val stage=SetupFlow.stage(facts)
        if(!dev||!wifi||!pairingNeeded||error!=null)settingsHandoff=false
        val pairingLifetime=notifications&&!manual&&(settingsHandoff||stage in setOf(SetupStage.GUIDE_PAIRING_CODE_PAGE,SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,SetupStage.PAIRING_NOTIFICATION_READY,SetupStage.PAIRING))
        if(!runtime.keepPairingAlive(pairingLifetime)&&pairingLifetime)manual=true
        val desired=PairNoticePolicy.desired(stage,notifications)
        if(desired!=notices||(desired==PairNotice.CODE&&notifiedToken!=token)){
            when(desired){PairNotice.NONE->PairingNotifications.cancel(context);PairNotice.CODE->PairingNotifications.code(context,token,sessionId,invalidCode)}
            notices=desired;notifiedToken=token
        }
        listener?.invoke()
        if(stage==SetupStage.PAIRING_NOTIFICATION_READY&&held!=null){val reply=held!!;held=null;pair(reply,token,null)}
        if(stage==SetupStage.FINAL_CHECK&&!finalizing&&!runtime.busy)finishSetup()
        // PC start: the same checks as the wireless path, through the attached helper (WirelessWork uses it; no port).
        if(pc&&helper&&stage==SetupStage.VERIFYING&&!connecting&&!verifying&&!verified&&!runtime.busy&&attempts<2)connect(0)
        if(stage==SetupStage.CONNECTING&&!connecting&&!verifying&&!runtime.busy){
            val candidates=discovery?.candidates(LocalAdbDiscovery.CONNECT,prefs.getString("pairedGuid",null)).orEmpty()
            if(candidates.size==1&&attempts<2)connect(candidates.single().port)
            else if(candidates.isEmpty()&&deadline-SystemClock.elapsedRealtime()<285000){error=SetupStage.DISCOVERY_RECOVERY;refresh()}
        }
    }
    fun submit(input:CharSequence,generation:Long?=null,manualPort:Int?=null){
        refresh()
        if(closed||pairing||connecting||verifying||generation!=null&&(generation!=token||!lease.current(generation)))return
        val trimmed=input.trim()
        val reply=LocalAdbPolicy.reply(trimmed)
        if(reply==null){invalidCode=true;listener?.invoke();if(notices==PairNotice.CODE)PairingNotifications.code(context,token,sessionId,true);return}
        // Sent from the notification before the phone announced its pairing port: kept until refresh finds it.
        if(generation!=null&&endpoint==null&&reply.port==null){held?.close();held=reply;return}
        pair(reply,generation,manualPort)
    }
    private fun pair(reply:LocalAdbPolicy.PairReply,generation:Long?,manualPort:Int?){
        val selected=endpoint?.port?:manualPort?:reply.port
        if(closed||pairing||connecting||verifying||!facts.developer||!facts.wireless||SystemClock.elapsedRealtime()>=deadline||generation!=null&&generation!=token){reply.close();return}
        if(selected==null||selected !in 1..65535){reply.close();manual=true;invalidCode=true;listener?.invoke();return}
        if(generation==null&&endpoint==null){token=lease.begin()}
        val expected=token
        if(runtime.busy||!lease.take(expected,reply.code)){reply.close();return}
        pairing=true;error=null;invalidCode=false;verified=false;PairingNotifications.cancel(context);notices=PairNotice.NONE;refresh()
        worker.execute{
            try{
                runtime.pair(selected,reply.code){!closed&&lease.current(expected)}
                main.post{if(!closed&&lease.current(expected)){pairing=false;cancelInput();forcePairing=false;error=null;attempts=0;observe();refresh()}}
            }catch(_:Exception){reply.close();main.post{if(!closed&&lease.current(expected)){pairing=false;cancelInput();error=SetupStage.PAIRING_RETRY;refresh()}}}
            finally{reply.close()}
        }
    }
    private fun finishSetup(){
        val port=verifiedPort?:return
        finalizing=true;scanProgress=null
        val epoch=operationEpoch
        worker.execute{
            try{
                runtime.verifyScanProgress(port,{}, {!closed&&operationEpoch==epoch}){progress->
                    main.post{if(!closed&&operationEpoch==epoch){scanProgress=progress;listener?.invoke()}}
                }
                main.post{if(!closed&&operationEpoch==epoch){runtime.completeFinalCheck();finalizing=false;refresh()}}
            }catch(_:Exception){main.post{if(!closed&&operationEpoch==epoch){finalizing=false;verified=false;error=SetupStage.CONNECTION_RECOVERY;refresh()}}}
        }
    }
    private fun connect(port:Int){
        app.bluedotoff.StartupScanHandoff.cache.invalidate()
        attempts++;connecting=true;verified=false;scanProgress=null;refresh()
        val epoch=operationEpoch
        worker.execute{
            try{
                var reportedPercent=-1
                val initialOnly=!runtime.finalCheckResolved
                val started:()->Unit={
                    main.post{if(!closed&&operationEpoch==epoch){connecting=false;verifying=true;refresh()}}
                }
                val current={!closed&&operationEpoch==epoch}
                if(initialOnly)runtime.verifyConnection(port,started,current)
                else runtime.verifyScanProgress(port,{
                    main.post{if(!closed&&operationEpoch==epoch){connecting=false;verifying=true;refresh()}}
                },{!closed&&operationEpoch==epoch}){progress->
                    if(progress.percent!=reportedPercent||progress.finishing){
                        reportedPercent=progress.percent
                        main.post{if(!closed&&operationEpoch==epoch&&verifying){scanProgress=progress;listener?.invoke()}}
                    }
                }
                main.post{if(!closed&&operationEpoch==epoch){connecting=false;verifying=false;verified=true;verifiedPort=port;error=null;settingsHandoff=false;runtime.keepPairingAlive(false);if(!runtime.pcStart)main.removeCallbacks(tick);PairingNotifications.cancel(context);notices=PairNotice.NONE;facts=facts.copy(connecting=false,verifying=false,verified=true,wireless=runtime.wireless,completedOffline=runtime.intentionalExit&&!runtime.wireless,managementChoiceResolved=runtime.managementChoiceResolved);discovery?.close();discovery=null;refresh()}}
            }catch(e:Exception){android.util.Log.w("bluedotOFF","setup check failed: ${e.javaClass.simpleName} ${e.message}");main.post{if(!closed&&operationEpoch==epoch){connecting=false;verifying=false;verified=false;error=if(e is HelperOperationRejectedException&&e.reason in setOf("COMPATIBILITY_FAILED","SHELL_IDENTITY_INVALID"))SetupStage.UNSUPPORTED else if(attempts>=2)SetupStage.CONNECTION_RECOVERY else null
                if(error==null)main.postDelayed({if(!closed)refresh()},1000)else refresh()}}}
        }
    }
}
