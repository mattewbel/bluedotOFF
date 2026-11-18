package app.bluedotoff

import android.app.Application
import android.net.*
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[RefusedWirelessSettings::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AutoBlockerHomeTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val activityType=MainActivity::class.java
    private val sdk=Build.VERSION.SDK_INT
    private val maker=Build.MANUFACTURER
    private val abis=Build.SUPPORTED_ABIS
    private val fixtureKey get()=File(app.noBackupFilesDir,"local-adb-identity.enc")
    private val entry get()=Entry(Identity("fixture.maps",0,10689,listOf("fixture"),1,1,1),"Maps",256,true,
        TargetState.APPLIED,1,Build.FINGERPRINT+"|fixture",BootSession.current(app),true,settlingVerified=true)
    private fun call(a:MainActivity,name:String):Any?=activityType.getDeclaredMethod(name).apply{isAccessible=true}.invoke(a)
    private fun model(a:MainActivity)=call(a,"getModel") as UiModel
    private fun seed(a:MainActivity,m:UiModel){
        activityType.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}.invoke(a,m)
    }
    @Before fun start(){
        Settings.Global.putInt(app.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
        Settings.Global.putInt(app.contentResolver,"adb_wifi_enabled",0)
        WirelessControls(app).setConsent(true);shadowOf(app).grantPermissions(WirelessControls.PERMISSION)
        app.getSharedPreferences("ui-state",0).edit().putBoolean("welcomed",true).commit()
        app.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","test").commit()
        fixtureKey.writeText("test existence marker")
        ScopePreferences(app).select(ApplicationScope.ALL)
        val cm=app.getSystemService(ConnectivityManager::class.java)
        val n=checkNotNull(cm.activeNetwork);val caps=NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(cm).setNetworkCapabilities(n,caps);shadowOf(cm).setLinkProperties(n,LinkProperties().apply{interfaceName="wlan0"})
        ForegroundState.started();ForegroundState.resumed()
        RefusedWirelessSettings.enableAttempts=0;RefusedWirelessSettings.rejectWrite=false;RefusedWirelessSettings.missingRead=false;RefusedWirelessSettings.acceptEnable=false
        StartupScanHandoff.cache.invalidate()
    }
    @After fun stop(){
        ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",sdk)
        ReflectionHelpers.setStaticField(Build::class.java,"MANUFACTURER",maker)
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",abis)
        ForegroundState.paused();ForegroundState.stopped();fixtureKey.delete()
        AutoBlocker.suspected=false;StartupScanHandoff.cache.invalidate()
    }
    private fun checkRegistered(a:MainActivity,seconds:Long=6){
        ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",37)
        ReflectionHelpers.setStaticField(Build::class.java,"MANUFACTURER","samsung")
        ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("arm64-v8a"))
        call(a,"recheck")
        val until=System.nanoTime()+seconds*1_000_000_000L
        while(!model(a).lastCheckFailed&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue(model(a).lastCheckFailed)
    }
    @Test fun theRefusedHomeAttemptShowsItsReasonWithoutOpeningSettingsOrRetrying(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val a=controller.get()
            val pending=entry.copy(state=TargetState.NEEDS_REAPPLY_UPDATE)
            assertTrue(JournalStore(app).commit(pending))
            seed(a,UiModel(gate=Gate.READY,entries=listOf(pending),lastCheckedAt=123))
            activityType.getDeclaredField("autoReapplyArmed").apply{isAccessible=true}.setBoolean(a,true)
            checkRegistered(a)
            assertEquals(AutoBlocker.MESSAGE,model(a).compatibility)
            assertFalse(model(a).notice.contains("잠시"));assertFalse(model(a).blockerReminder)
            assertNull(shadowOf(a).nextStartedActivity)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activityType.getDeclaredField("autoReapplyTried").apply{isAccessible=true}.getBoolean(a))
            assertEquals(1,RefusedWirelessSettings.enableAttempts)
            assertEquals(123L,model(a).lastCheckedAt);assertEquals(256,JournalStore(app).entries().single().originalBit)
        }finally{controller.destroy()}
    }
    @Test fun anOldSuspicionNeverOverridesANewFailedWrite(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            AutoBlocker.suspected=true;RefusedWirelessSettings.rejectWrite=true
            val a=controller.get();seed(a,UiModel(gate=Gate.READY,automaticReady=true))
            checkRegistered(a)
            assertEquals(AutoBlocker.CONNECTION_HINT,model(a).compatibility);assertFalse(AutoBlocker.suspected)
        }finally{controller.destroy()}
    }
    @Test fun reopeningAfterARefusalChecksAgainEvenWithinTheUsualTenMinuteWindow(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val a=controller.get();assertTrue(JournalStore(app).commit(entry))
            seed(a,UiModel(gate=Gate.READY,entries=listOf(entry),lastCheckedAt=123))
            val clock=activityType.getDeclaredField("clock").apply{isAccessible=true}.get(a) as RecheckClock
            clock.fullChecked(android.os.SystemClock.elapsedRealtime())
            checkRegistered(a);assertEquals(AutoBlocker.MESSAGE,model(a).compatibility)
            // The user went to Samsung settings independently. The earlier refusal is now stale.
            RefusedWirelessSettings.rejectWrite=true
            call(a,"resumeCheck")
            val until=System.nanoTime()+4_000_000_000L
            while(model(a).compatibility==AutoBlocker.MESSAGE&&System.nanoTime()<until){
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)
            }
            assertEquals(2,RefusedWirelessSettings.enableAttempts)
            assertNotEquals(AutoBlocker.MESSAGE,model(a).compatibility)
        }finally{controller.destroy()}
    }
    @Test fun anUnreadableReadbackIsNotEvidenceThatTheSwitchStayedOff(){
        RefusedWirelessSettings.missingRead=true
        assertEquals(WirelessControls.EnableResult.FAILED,WirelessControls(app).requestWirelessDebugging(false))
        assertFalse(AutoBlocker.suspected)
    }
    @Test fun aSwitchThatTurnsOnButOffersNoAdbServiceGetsNeutralConnectionHelp(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            RefusedWirelessSettings.acceptEnable=true
            val a=controller.get();assertTrue(JournalStore(app).commit(entry))
            // This SDK 34 test runtime has no Android 17 NSD builder. Model zero advertised services.
            shadowOf(a).removeSystemService(android.content.Context.NSD_SERVICE)
            assertNull(a.getSystemService(android.net.nsd.NsdManager::class.java))
            seed(a,UiModel(gate=Gate.READY,entries=listOf(entry),lastCheckedAt=123))
            checkRegistered(a,25)
            val messages=org.robolectric.shadows.ShadowLog.getLogsForTag("bluedotOFF").map{it.msg}
            assertEquals(messages.joinToString(),AutoBlocker.CONNECTION_HINT,model(a).compatibility)
            assertFalse(AutoBlocker.suspected);assertEquals(123L,model(a).lastCheckedAt)
            assertEquals(1,RefusedWirelessSettings.enableAttempts)
            assertNull(AutoBlocker.connectionGuide(LocalAdbStageException(LocalAdbState.ADB_AUTHENTICATION_FAILED)))
            assertNull(AutoBlocker.connectionGuide(SecurityException()))
        }finally{controller.destroy()}
    }
    @Test fun theSetupHandoffIsConsumedOnceAndAnExternalExtraDoesNotArmIt(){
        val intent=android.content.Intent(app,MainActivity::class.java).putExtra(AutoBlocker.SETUP_VISIT,true)
        val controller=Robolectric.buildActivity(MainActivity::class.java,intent).create()
        try{
            val a=controller.get();val ready=UiModel(gate=Gate.READY,entries=listOf(entry))
            seed(a,ready);call(a,"takeSetupSecurityVisit");call(a,"securityCheckCompleted")
            assertFalse(model(a).blockerReminder)
            val prefs=app.getSharedPreferences("ui-state",0)
            prefs.edit().putBoolean(AutoBlocker.SETUP_VISIT,true).commit()
            call(a,"takeSetupSecurityVisit")
            assertFalse(prefs.contains(AutoBlocker.SETUP_VISIT));assertFalse(model(a).blockerReminder)
            call(a,"securityCheckCompleted");assertTrue(model(a).blockerReminder)
            call(a,"clearNotice");call(a,"takeSetupSecurityVisit");call(a,"securityCheckCompleted")
            assertFalse(model(a).blockerReminder)
        }finally{controller.destroy()}
    }
    @Test fun onlyAnExplicitSettingsVisitAndSuccessfulCheckCanOfferProtectionAgain(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val a=controller.get();val ready=UiModel(gate=Gate.READY,entries=listOf(entry))
            seed(a,ready);call(a,"securityCheckCompleted");assertFalse(model(a).blockerReminder)
            call(a,"openSecuritySettings")
            assertNotNull(shadowOf(a).nextStartedActivity);assertFalse(model(a).blockerReminder)
            val saved=Bundle();controller.saveInstanceState(saved)
            assertEquals("NEEDS_CHECK",saved.getString("securityVisit"))
            assertTrue(saved.getBoolean("securityReturnPending"))
            activityType.getDeclaredField("checkOnResume").apply{isAccessible=true}.setBoolean(a,false)
            val alreadyReturned=Bundle();controller.saveInstanceState(alreadyReturned)
            assertFalse(alreadyReturned.getBoolean("securityReturnPending"))
            seed(a,ready.copy(entries=listOf(entry.copy(state=TargetState.NEEDS_REAPPLY_UPDATE))))
            call(a,"securityCheckCompleted");assertFalse(model(a).blockerReminder)
            seed(a,ready.copy(wirelessWarning="cleanup"));call(a,"finishSecurityVisit");assertFalse(model(a).blockerReminder)
            seed(a,ready);call(a,"finishSecurityVisit");assertTrue(model(a).blockerReminder)
            call(a,"clearNotice");call(a,"finishSecurityVisit");assertFalse(model(a).blockerReminder)
            call(a,"openSecuritySettings");BackgroundWatch.set(app,true)
            call(a,"securityCheckCompleted");assertFalse(model(a).blockerReminder)
        }finally{BackgroundWatch.set(app,false);controller.destroy()}
    }
}
