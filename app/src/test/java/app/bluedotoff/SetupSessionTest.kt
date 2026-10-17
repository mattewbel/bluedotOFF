package app.bluedotoff

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

internal class SetupQueue:AbstractExecutorService(){
    val tasks=java.util.ArrayDeque<Runnable>();private var stopped=false
    override fun execute(task:Runnable){tasks.add(task)}
    fun next(){tasks.removeFirst().run()}
    override fun shutdown(){stopped=true}
    override fun shutdownNow():MutableList<Runnable>{stopped=true;return mutableListOf()}
    override fun isShutdown()=stopped
    override fun isTerminated()=stopped
    override fun awaitTermination(timeout:Long,unit:TimeUnit)=stopped
}
internal class FakeSetupRuntime:SetupRuntime {
    override var batteryChoiceRequired=false
    override var batteryChoiceResolved=true
    override fun skipBatteryChoice(){batteryChoiceResolved=true}
    override var finalCheckResolved=true
    var initialChecks=0;var fullScans=0
    override fun completeFinalCheck(){finalCheckResolved=true}
    override fun verifyConnection(port:Int,started:()->Unit,current:()->Boolean){initialChecks++;check(current());verify(port,started)}
    override val localNetworkPermissionRequired=true
    override var managementChoiceResolved=true
    var managementCalls=0;var skippedManagement=0;var managementFailure=false
    override fun skipManagement(){skippedManagement++;managementChoiceResolved=true}
    override fun enableManagement(port:Int){managementCalls++;if(managementFailure)throw java.io.IOException();managementChoiceResolved=true;intentionalExit=true;wireless=false}
    override var wifiConnected=true;override var intentionalExit=false;var exitAfterVerify=false
    override var developer=true;override var wireless=true;override var paired=false
    override var supported=true;override var notifications=true;override var broad=true;override var busy=false
    var pairEndpoint:LocalAdbDiscovery.Endpoint?=null
    var connectEndpoint:LocalAdbDiscovery.Endpoint?=null
    var connectCalls=0;var pairCalls=0;var connectFailure=false;var pairFailure=false;var cancelled=0;var closed=0
    var codeRef:ByteArray?=null;var chosen=false
    var alive=false;var aliveAllowed=true
    override fun keepPairingAlive(required:Boolean):Boolean{alive=required&&aliveAllowed;return !required||aliveAllowed}
    var changed:(()->Unit)?=null
    override fun discover(changed:()->Unit):SetupDiscoveryAccess {this.changed=changed;return object:SetupDiscoveryAccess{
        override val error:Int?=null
        override fun candidates(type:String,guid:String?)=listOfNotNull(if(type==LocalAdbDiscovery.PAIR)pairEndpoint else connectEndpoint)
        override fun choose(type:String){chosen=true}
        override fun close(){closed++}
    }}
    override fun pair(port:Int,code:ByteArray,current:()->Boolean){pairCalls++;codeRef=code;check(current());if(pairFailure)throw java.io.IOException();paired=true}
    override fun verify(port:Int,started:()->Unit){connectCalls++;started();if(connectFailure)throw java.io.IOException();if(exitAfterVerify){intentionalExit=true;wireless=false;changed?.invoke()}}
    var scanHook:((()->Boolean)->Unit)?=null
    var progressHook:(((ScanProgress)->Unit)->Unit)?=null
    override fun verifyScanProgress(port:Int,started:()->Unit,current:()->Boolean,progress:(ScanProgress)->Unit){
        fullScans++;verifyScan(port,started,current);progressHook?.invoke(progress)
        if(!current())throw java.util.concurrent.CancellationException()
    }
    override fun verifyScan(port:Int,started:()->Unit,current:()->Boolean){
        if(!current())throw java.util.concurrent.CancellationException()
        verify(port,started);scanHook?.invoke(current)
        if(!current())throw java.util.concurrent.CancellationException()
    }

    override fun cancelPairing(){cancelled++}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class SetupSessionTest {
    @Test fun batteryDenialStaysOptionalAndReturningDoesNotRepeatVerifiedScan(){
        runtime.finalCheckResolved=false;runtime.batteryChoiceRequired=true;runtime.batteryChoiceResolved=false
        context.getSharedPreferences("application-scope",0).edit().clear().commit()
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.BATTERY_OPTIMIZATION,stage())
        session.batterySettingsReturned()
        assertEquals(SetupStage.BATTERY_OPTIMIZATION,stage());assertTrue(queue.tasks.isEmpty());assertEquals(1,runtime.initialChecks)
        session.skipBatteryChoice();assertEquals(SetupStage.APPLICATION_SCOPE,stage())
        session.chooseScope(ApplicationScope.ALL);queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.READY,stage());assertEquals(1,runtime.fullScans)
    }
    @Test fun batteryApprovalIsReadFromRuntimeAndRadioLossStillInvalidatesVerification(){
        runtime.finalCheckResolved=false;runtime.batteryChoiceRequired=true;runtime.batteryChoiceResolved=false
        context.getSharedPreferences("application-scope",0).edit().clear().commit()
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.BATTERY_OPTIMIZATION,stage())
        runtime.batteryChoiceResolved=true;session.batterySettingsReturned()
        assertEquals(SetupStage.APPLICATION_SCOPE,stage());assertTrue(queue.tasks.isEmpty())
        runtime.wifiConnected=false;session.batterySettingsReturned()
        assertEquals(SetupStage.WIFI_NOT_CONNECTED,stage());assertFalse(session.facts.verified)
    }
    @Test fun freshSetupChecksConnectionThenScansOnceAfterManagementAndScope(){
        runtime.finalCheckResolved=false;runtime.managementChoiceResolved=false
        context.getSharedPreferences("application-scope",0).edit().clear().commit()
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,stage());assertEquals(1,runtime.initialChecks);assertEquals(0,runtime.fullScans)
        session.skipManagement();assertEquals(SetupStage.APPLICATION_SCOPE,stage())
        session.chooseScope(ApplicationScope.ALL);assertEquals(SetupStage.FINAL_CHECK,stage())
        assertEquals(0,runtime.fullScans);queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.READY,stage());assertEquals(1,runtime.fullScans);assertTrue(runtime.finalCheckResolved)
    }
    @Test fun finalScanAfterAutomaticManagementSurvivesItsOwnRadioChanges(){
        runtime.finalCheckResolved=false;runtime.managementChoiceResolved=false
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        session.enableManagement();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.FINAL_CHECK,stage());assertFalse(runtime.wireless)
        runtime.scanHook={current->runtime.wireless=true;session.refresh();assertTrue(current());runtime.wireless=false;session.refresh();assertTrue(current())}
        queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.READY,stage());assertEquals(1,runtime.fullScans)
    }
    @Test fun failedFinalScanCannotPersistReadyOrLoseTheChoice(){
        runtime.finalCheckResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        runtime.connectFailure=true;queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(runtime.finalCheckResolved);assertEquals(SetupStage.CONNECTION_RECOVERY,stage())
        assertEquals(ApplicationScope.ALL,ScopePreferences(context).current)
    }
    @Test fun closedFinalCheckCannotPublishCompletion(){
        runtime.finalCheckResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.FINAL_CHECK,stage());session.close();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(runtime.finalCheckResolved)
    }
    @Test fun scanProgressPublishesActualCountsWhileVerifyingAndCannotFinishTheStage(){
        val percentages=mutableListOf<Int>()
        session.listener={if(stage()==SetupStage.VERIFYING)session.scanProgress?.let{percentages+=it.percent}}
        runtime.progressHook={report->report(ScanProgress(389,778));report(ScanProgress(778,778))}
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertTrue(50 in percentages);assertTrue(99 in percentages)
        assertEquals(SetupStage.READY,stage());assertEquals(1,runtime.connectCalls)
    }
    @Test fun notificationRequestMustWaitForItsResultEvenIfGrantChangesFirst(){
        runtime.notifications=false;runtime.developer=false
        session.refresh();session.notificationsAsked();runtime.notifications=true;session.refresh()
        assertEquals(SetupStage.NOTIFICATION_PERMISSION,stage())
        session.notificationsResult();assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,stage())
    }
    @Test fun denyingOrDismissingNotificationsCannotSilentlyResolveTheStep(){
        runtime.notifications=false;runtime.developer=false
        session.notificationsAsked();session.notificationsResult()
        assertEquals(SetupStage.NOTIFICATION_PERMISSION,stage());assertEquals(0,runtime.connectCalls)
        session.skipNotifications();assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,stage())
    }
    @Test fun connectionLossCancelsQueuedManagementAndDoesNotLeaveAnEndlessSpinner(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();session.enableManagement()
        runtime.wireless=false;session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,runtime.managementCalls);assertFalse(session.facts.managing)
        assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,stage())
    }
    @Test fun closingBeforeQueuedManagementRunsCannotEnableIt(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();session.enableManagement();session.close()
        queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(0,runtime.managementCalls)
    }
    @Test fun freshChoicePersistsWithoutConsentAndDataClearRequiresItAgain(){
        val ui=context.getSharedPreferences("ui-state",0);ui.edit().clear().commit()
        val controls=WirelessControls(context);controls.setConsent(false)
        val androidRuntime=AndroidSetupRuntime(context);androidRuntime.initializeOnboarding()
        assertFalse(androidRuntime.managementChoiceResolved);assertFalse(controls.consent)
        androidRuntime.skipManagement();androidRuntime.initializeOnboarding();assertTrue(androidRuntime.managementChoiceResolved)
        ui.edit().clear().commit();androidRuntime.initializeOnboarding();assertFalse(androidRuntime.managementChoiceResolved)
        assertFalse(controls.consent)
    }
    @Test fun batteryChoicePersistsExplicitSkipAndDoesNotReopenForCompletedUsers(){
        val ui=context.getSharedPreferences("ui-state",0);ui.edit().clear().commit()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName,false)
        val r=AndroidSetupRuntime(context);r.initializeOnboarding()
        assertFalse(r.batteryChoiceResolved)
        r.skipBatteryChoice();assertTrue(AndroidSetupRuntime(context).batteryChoiceResolved)
        assertFalse(BatteryOptimization.unrestricted(context)?:true)
        ui.edit().clear().putBoolean("finalCheckResolved",true).commit()
        assertTrue(AndroidSetupRuntime(context).batteryChoiceResolved)
        ui.edit().clear().commit();r.initializeOnboarding();assertFalse(r.batteryChoiceResolved)
    }
    @Test fun offToOnInSettingsNeedsNoActivityResumeForCodeNotification(){
        runtime.wireless=false;session.refresh();assertFalse(runtime.alive)
        session.prepareSettingsHandoff();assertTrue(runtime.alive)
        repeat(4){session.refresh()};assertTrue(runtime.alive)
        runtime.wireless=true;val first=readyCode()
        assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage());assertTrue(runtime.alive)
        runtime.pairEndpoint=null;session.refresh();assertEquals(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,stage())
        val next=readyCode();assertTrue(next>first)
        session.submit("123456",first);assertTrue(queue.tasks.isEmpty())
        session.submit("123456",next);assertEquals(1,queue.tasks.size)
    }
    @Test fun handoffCannotBypassNearbyDenialOrRestartAfterTimeout(){
        runtime.wireless=false;runtime.broad=false;session.refresh()
        session.prepareSettingsHandoff();assertFalse(runtime.alive)
        session.skipNearby();session.prepareSettingsHandoff();assertFalse(runtime.alive);assertTrue(session.manualInput)
        session.pairingLifetimeExpired();session.prepareSettingsHandoff();assertFalse(runtime.alive)
    }
    @Test fun initialVerificationOffersManagementWithoutEnablingIt(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,stage());repeat(5){session.refresh()}
        assertEquals(0,runtime.managementCalls);assertTrue(queue.tasks.isEmpty())
        session.skipManagement();assertEquals(SetupStage.READY,stage());assertEquals(0,runtime.managementCalls)
    }
    @Test fun explicitManagementChoiceIsSingleFlightAndSurvivesResumeWhileWorking(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        session.enableManagement();session.enableManagement();assertEquals(1,queue.tasks.size)
        assertEquals(SetupStage.ENABLING_MANAGEMENT,stage());session.resume();assertTrue(session.facts.verified)
        queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,runtime.managementCalls);assertEquals(SetupStage.READY,stage());assertFalse(runtime.wireless)
    }
    @Test fun managementFailureRemainsOptionalAndVisible(){
        runtime.managementChoiceResolved=false;runtime.managementFailure=true;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();session.enableManagement()
        queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,stage());assertTrue(session.managementError.isNotBlank())
        assertEquals(1,runtime.managementCalls);repeat(4){session.refresh()};assertTrue(queue.tasks.isEmpty())
        session.skipManagement();assertEquals(SetupStage.READY,stage())
    }
    private fun seedScan() {
        val fw=Firmware("samsung",37,"90000","fp","id","fold","proof")
        val cache=StartupScanHandoff.cache
        assertTrue(cache.publish(cache.revision(),PreparedStartupScan(fw,"boot",emptyList(),emptyList(),DiscoveryResult(emptyList(),true,ScanMetrics()))))
    }
    @Test fun readinessAndRecommendationWaitForWholeScan(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        runtime.scanHook={current->shadowOf(Looper.getMainLooper()).idle();assertTrue(current());assertEquals(SetupStage.VERIFYING,stage());assertFalse(session.facts.verified);assertEquals(0,runtime.managementCalls)}
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,stage())
    }
    @Test fun closingBeforeQueuedVerificationDoesNotBeginAReadScan(){
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();session.close();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,runtime.connectCalls)
    }
    @Test fun failedWholeScanCannotDeclareReady(){
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000);runtime.scanHook={throw java.io.IOException("scan failed")}
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertFalse(session.facts.verified);assertNotEquals(SetupStage.READY,stage())
        assertNull(StartupScanHandoff.cache.take("fp","boot",emptyList()))
    }
    @Test fun completedNavigationPreservesOneShotReceiptEvenOnSecondClose(){
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000);runtime.scanHook={seedScan()}
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.READY,stage())
        session.close(keepScan=true);session.close()
        assertNotNull(StartupScanHandoff.cache.take("fp","boot",emptyList()));assertNull(StartupScanHandoff.cache.take("fp","boot",emptyList()))
    }
    @Test fun cancelledSetupDiscardsPreparedReceipt(){
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000);runtime.scanHook={seedScan()}
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();session.close()
        assertNull(StartupScanHandoff.cache.take("fp","boot",emptyList()))
    }
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var runtime:FakeSetupRuntime
    private lateinit var queue:SetupQueue
    private lateinit var session:SetupSession
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    @Before fun create(){ScopePreferences(context).select(ApplicationScope.GROUPS);context.getSharedPreferences("ui-state",0).edit().putBoolean("welcomed",true).commit();runtime=FakeSetupRuntime();queue=SetupQueue();session=SetupSession(context,runtime,queue);session.attach{}}
    @After fun close(){session.close();shadowOf(Looper.getMainLooper()).idle()}
    private fun stage()=SetupFlow.stage(session.facts)
    private fun readyCode():Long{runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000);session.refresh();return shadowOf(shadowOf(manager).allNotifications.single().actions.single().actionIntent).savedIntent.getLongExtra("generation",-1)}
    @Test fun advertisementAndLossReplaceAndRemoveCodeAction(){assertEquals(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,stage());readyCode();assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage());runtime.pairEndpoint=null;session.refresh();assertEquals(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,stage());assertTrue(shadowOf(manager).allNotifications.single().actions.isNullOrEmpty())}
    @Test fun pairingLifetimeCoversLossAndEndsOnSuccessOrCancel(){
        assertTrue(runtime.alive);val token=readyCode();runtime.pairEndpoint=null;session.refresh();assertTrue(runtime.alive)
        readyCode();runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("own-connect",42000);session.submit("123456");assertTrue(runtime.alive)
        queue.next();shadowOf(Looper.getMainLooper()).idle();assertFalse(runtime.alive)
        session.close();assertFalse(runtime.alive)
    }
    @Test fun pairingLifetimeTimeoutCancelsObservationAndRejectsStaleReply(){
        val old=readyCode();session.pairingLifetimeExpired()
        assertEquals(SetupStage.SESSION_EXPIRED,stage());assertFalse(runtime.alive);assertTrue(shadowOf(manager).allNotifications.isEmpty())
        session.submit("123456",old);assertTrue(queue.tasks.isEmpty());assertTrue(runtime.closed>0)
    }
    @Test fun deniedLifetimeUsesVisibleManualInputAndStopsBackgroundLease(){
        runtime.aliveAllowed=false;session.refresh();session.refresh()
        assertTrue(session.manualInput);assertFalse(runtime.alive)
    }
    @Test fun staleGenerationAndDuplicateReplyNeverStartSecondPairing(){val old=readyCode();runtime.pairEndpoint=null;session.refresh();val current=readyCode();session.submit("123456",old);assertTrue(queue.tasks.isEmpty());session.submit("123456",current);session.submit("123456",current);assertEquals(1,queue.tasks.size)}
    @Test fun malformedInputKeepsLeaseAndShowsPlainValidation(){val token=readyCode();session.submit("abc",token);assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage());assertTrue(session.inputError);assertTrue(queue.tasks.isEmpty());session.submit(" 123456 ",token);assertEquals(1,queue.tasks.size)}
    @Test fun wrongCodeClearsBytesAndOffersNewCode(){val token=readyCode();runtime.pairFailure=true;session.submit("123456",token);queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.PAIRING_RETRY,stage());assertTrue(runtime.codeRef!!.all{it==0.toByte()});assertTrue(shadowOf(manager).allNotifications.isEmpty());session.beginPairing();assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage())}
    @Test fun successHasNoSuccessNoticeAndContinuesToFreshVerification(){val token=readyCode();runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("own-connect",42000);session.submit("123456",token);queue.next();shadowOf(Looper.getMainLooper()).idle();assertTrue(shadowOf(manager).allNotifications.isEmpty());assertEquals(1,queue.tasks.size);queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.READY,stage());assertTrue(runtime.codeRef!!.all{it==0.toByte()});assertEquals(1,runtime.connectCalls)}
    @Test fun savedIdentityRetriesAtMostTwiceThenRequiresUserAction(){runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000);runtime.connectFailure=true;session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idleFor(1100,TimeUnit.MILLISECONDS);queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.CONNECTION_RECOVERY,stage());repeat(10){session.refresh()};assertEquals(2,runtime.connectCalls);assertTrue(queue.tasks.isEmpty())}
    @Test fun wirelessOffInvalidatesLateConnectionResult(){runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000);session.refresh();runtime.wireless=false;session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,stage());assertFalse(session.facts.verified)}
    @Test fun permissionDenialUsesInputWithoutNotification(){runtime.notifications=false;session.skipNotifications();runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000);session.refresh();assertTrue(session.manualInput);assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage());assertTrue(shadowOf(manager).allNotifications.isEmpty());session.submit("123456");assertEquals(1,queue.tasks.size)}
    @Test fun sessionTimeoutStopsObservationAndDiscardsOldReplies(){val token=readyCode();shadowOf(Looper.getMainLooper()).idleFor(300001,TimeUnit.MILLISECONDS);assertEquals(SetupStage.SESSION_EXPIRED,stage());assertTrue(shadowOf(manager).allNotifications.isEmpty());session.submit("123456",token);assertTrue(queue.tasks.isEmpty());assertTrue(runtime.closed>0)}
    @Test fun detachAndReattachForRotationPreserveReplyGeneration(){val token=readyCode();val callback:()->Unit={};session.attach(callback);session.detach(callback);session.attach{};session.submit("123456",token);assertEquals(1,queue.tasks.size)}
    @Test fun realSettingsOverrideSessionUiOnResume(){runtime.developer=false;session.resume();assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,stage());runtime.developer=true;runtime.wireless=false;session.resume();assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,stage())}
    @Test fun lateOwnAdvertisementRecoversDiscoveryWithoutOpeningPicker(){runtime.paired=true;session.refresh();shadowOf(Looper.getMainLooper()).idleFor(16000,TimeUnit.MILLISECONDS);assertEquals(SetupStage.DISCOVERY_RECOVERY,stage());runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42001);session.refresh();assertEquals(1,queue.tasks.size);queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.READY,stage());assertFalse(runtime.chosen)}
    @Test fun repeatedSuccessfulResumeStartsFreshBoundedVerification(){runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000);session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle();repeat(3){session.resume();assertEquals(1,queue.tasks.size);queue.next();shadowOf(Looper.getMainLooper()).idle();assertEquals(SetupStage.READY,stage())};assertEquals(4,runtime.connectCalls)}
    @Test fun intentionalOffAfterVerifiedWorkKeepsReadyReceipt(){
        runtime.paired=true;runtime.exitAfterVerify=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        session.refresh();queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.READY,stage());assertTrue(session.facts.verified);assertFalse(session.facts.wireless)
    }
    @Test fun sameServiceNameAndPortInNewEpochRejectsPreviousReply(){
        runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000,1,1);session.refresh()
        val old=shadowOf(shadowOf(manager).allNotifications.single().actions.single().actionIntent).savedIntent.getLongExtra("generation",-1)
        runtime.pairEndpoint=null;session.refresh();runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000,1,2);session.refresh()
        val current=shadowOf(shadowOf(manager).allNotifications.single().actions.single().actionIntent).savedIntent.getLongExtra("generation",-1)
        assertTrue(current>old);session.submit("123456",old);assertTrue(queue.tasks.isEmpty());session.submit("123456",current);assertEquals(1,queue.tasks.size)
    }
    @Test fun wifiLossPreventsPairingAndLateVerification(){
        val old=readyCode();runtime.wifiConnected=false;session.refresh();session.submit("123456",old)
        assertEquals(SetupStage.WIFI_NOT_CONNECTED,stage());assertTrue(queue.tasks.isEmpty())
    }
}
