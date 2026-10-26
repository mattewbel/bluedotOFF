package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import app.bluedotoff.adb.*
import app.bluedotoff.core.ApplicationScope
import app.bluedotoff.core.SetupFlow
import app.bluedotoff.core.SetupStage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PcWifiSetupTest {
    private val c get()=RuntimeEnvironment.getApplication()
    @After fun closeSession(){SetupSession.current()?.close()}
    @Test fun openingWirelessSetupDoesNotDiscardTheWorkingPcChoice(){
        val c=RuntimeEnvironment.getApplication()
        PcStart.choose(c,true)
        val prefs=c.getSharedPreferences("ui-state",0)
        prefs.edit().putBoolean("pcStartWatchOn",true).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            MainActivity::class.java.getDeclaredMethod("wifiSetup").apply{isAccessible=true}.invoke(controller.get())
            val intent=shadowOf(controller.get()).nextStartedActivity
            assertEquals(LocalAdbSetupActivity::class.java.name,intent.component!!.className)
            assertTrue(intent.getBooleanExtra(LocalAdbSetupActivity.WIRELESS_SETUP,false))
            assertTrue("Opening setup is not a successful switch to Wi-Fi",PcStart.chosen(c))
            assertTrue("The existing watch ownership must survive a cancelled setup",prefs.getBoolean("pcStartWatchOn",false))
        }finally{controller.destroy()}
    }
    @Test fun cancelledAndRecreatedWirelessSetupKeepsThePcRestartAdvice(){
        PcStart.choose(c,true)
        c.getSharedPreferences("ui-state",0).edit().putBoolean("pcStartWatchOn",true).commit()
        val intent=Intent(c,LocalAdbSetupActivity::class.java).putExtra(LocalAdbSetupActivity.WIRELESS_SETUP,true)
        val controller=Robolectric.buildActivity(LocalAdbSetupActivity::class.java,intent).setup()
        try{
            assertFalse(SetupSession.current()!!.facts.pcStart)
            assertTrue(PcStart.chosen(c))
            controller.recreate()
            assertFalse(SetupSession.current()!!.facts.pcStart)
            controller.get().onBackPressedDispatcher.onBackPressed()
            assertTrue(controller.get().isFinishing)
            assertTrue(PcStart.chosen(c))
            assertTrue(c.getSharedPreferences("ui-state",0).getBoolean("pcStartWatchOn",false))
            assertTrue(WatchNotification.advice(true,false,PcStart.chosen(c)).contains("PC"))
        }finally{controller.pause().stop().destroy()}
        // A new process has only the saved PC choice, without the abandoned wireless session.
        assertTrue(AndroidSetupRuntime(c).pcStart)
    }
    @Test fun onlyTheSuccessfulWirelessSetupClearsThePcChoice()=verifyWirelessCompletion(false)
    @Test fun successfulWirelessCleanupMayTurnWirelessOffBeforeCommittingTheChoice()=verifyWirelessCompletion(true)
    private fun verifyWirelessCompletion(exitAfterVerify:Boolean){
        PcStart.choose(c,true)
        val prefs=c.getSharedPreferences("ui-state",0)
        prefs.edit().putBoolean("welcomed",true).putBoolean("pcStartWatchOn",true).commit()
        ScopePreferences(c).select(ApplicationScope.NORMAL)
        val runtime=FakeSetupRuntime().apply{paired=true;this.exitAfterVerify=exitAfterVerify;connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)}
        val queue=SetupQueue()
        val session=SetupSession(c,runtime,queue)
        SetupSession::class.java.getDeclaredField("active").apply{isAccessible=true}.set(null,session)
        val intent=Intent(c,LocalAdbSetupActivity::class.java).putExtra(LocalAdbSetupActivity.WIRELESS_SETUP,true)
        val controller=Robolectric.buildActivity(LocalAdbSetupActivity::class.java,intent).setup()
        try{
            assertTrue(PcStart.chosen(c))
            queue.next();shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SetupStage.READY,SetupFlow.stage(session.facts))
            assertEquals(!exitAfterVerify,session.facts.wireless)
            assertTrue(controller.get().isFinishing)
            assertFalse(PcStart.chosen(c))
            assertFalse(prefs.contains("pcStartWatchOn"))
            assertEquals(1,runtime.connectCalls)
            assertNull(prefs.getString("setupNotice",null))
        }finally{controller.pause().stop().destroy();session.close()}
    }
    @Test fun explicitPcChoiceInWirelessSetupRemainsPcAfterReopening(){
        PcStart.choose(c,true)
        val runtime=AndroidSetupRuntime(c,true)
        assertFalse(runtime.pcStart)
        assertTrue(PcStart.chosen(c))
        runtime.choosePcStart(true)
        assertTrue(runtime.pcStart)
        assertTrue(AndroidSetupRuntime(c).pcStart)
    }
    @Test fun choosingPcAgainSurvivesRestorationWithTheOriginalWirelessIntent(){
        PcStart.choose(c,true)
        val intent=Intent(c,LocalAdbSetupActivity::class.java).putExtra(LocalAdbSetupActivity.WIRELESS_SETUP,true)
        var controller=Robolectric.buildActivity(LocalAdbSetupActivity::class.java,Intent(intent)).setup()
        try{
            LocalAdbSetupActivity::class.java.getDeclaredMethod("startPc").apply{isAccessible=true}.invoke(controller.get())
            assertFalse(controller.get().intent.hasExtra(LocalAdbSetupActivity.WIRELESS_SETUP))
            assertTrue(SetupSession.current()!!.facts.pcStart)
            val saved=Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            SetupSession.current()?.close()
            controller=Robolectric.buildActivity(LocalAdbSetupActivity::class.java,Intent(intent)).create(saved).start().resume()
            assertTrue(SetupSession.current()!!.facts.pcStart)
            assertTrue(PcStart.chosen(c))
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun failedWirelessVerificationKeepsThePcChoiceAndWatch(){
        PcStart.choose(c,true)
        val prefs=c.getSharedPreferences("ui-state",0)
        prefs.edit().putBoolean("welcomed",true).putBoolean("pcStartWatchOn",true).putBoolean("backgroundWatch",true).commit()
        ScopePreferences(c).select(ApplicationScope.NORMAL)
        val runtime=FakeSetupRuntime().apply{paired=true;connectFailure=true;connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)}
        val queue=SetupQueue()
        val session=SetupSession(c,runtime,queue)
        SetupSession::class.java.getDeclaredField("active").apply{isAccessible=true}.set(null,session)
        val intent=Intent(c,LocalAdbSetupActivity::class.java).putExtra(LocalAdbSetupActivity.WIRELESS_SETUP,true)
        val controller=Robolectric.buildActivity(LocalAdbSetupActivity::class.java,intent).setup()
        try{
            queue.next();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(session.facts.verified)
            assertFalse(controller.get().isFinishing)
            assertTrue(PcStart.chosen(c))
            assertTrue(prefs.getBoolean("pcStartWatchOn",false))
            assertTrue(BackgroundWatch.enabled(c))
        }finally{controller.pause().stop().destroy();session.close()}
    }
    @Test fun wirelessRetryReplacesAnOldPcSessionButSurvivesRecreation(){
        PcStart.choose(c,true)
        val old=SetupSession.acquire(c)
        old.refresh();assertTrue(old.facts.pcStart)
        val wireless=SetupSession.acquire(c,wirelessSetup=true)
        wireless.refresh()
        assertNotSame(old,wireless)
        assertFalse(wireless.facts.pcStart)
        assertSame(wireless,SetupSession.acquire(c,wirelessSetup=true))
        assertTrue(PcStart.chosen(c))
    }
}
