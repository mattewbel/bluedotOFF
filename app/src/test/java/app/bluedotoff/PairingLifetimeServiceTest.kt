package app.bluedotoff

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Looper
import app.bluedotoff.adb.PairingLifetimeService
import app.bluedotoff.adb.SetupSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class PairingLifetimeServiceTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @After fun clean(){SetupSession.current()?.close();PairingLifetimeService.stop(app);while(ForegroundState.active)ForegroundState.stopped();shadowOf(Looper.getMainLooper()).idle()}
    private fun start():Pair<org.robolectric.android.controller.ServiceController<PairingLifetimeService>,Intent> {
        ForegroundState.started();SetupSession.acquire(app)
        assertTrue(PairingLifetimeService.ensure(app))
        val intent=shadowOf(app).nextStartedService
        val controller=Robolectric.buildService(PairingLifetimeService::class.java).create()
        assertEquals(Service.START_NOT_STICKY,controller.get().onStartCommand(intent,0,1))
        return controller to intent
    }
    @Test fun backgroundCannotStartAndNullRestartDoesNotResumeLease(){
        SetupSession.acquire(app);assertFalse(PairingLifetimeService.ensure(app));assertNull(shadowOf(app).nextStartedService)
        val controller=Robolectric.buildService(PairingLifetimeService::class.java).create()
        assertEquals(Service.START_NOT_STICKY,controller.get().onStartCommand(null,0,1))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf);controller.destroy()
    }
    @Test fun duplicateCommandCannotExtendDeadlineAndTimeoutStopsLease(){
        val (controller,intent)=start();val service=controller.get()
        shadowOf(Looper.getMainLooper()).idleFor(100000,TimeUnit.MILLISECONDS)
        service.onStartCommand(intent,0,2)
        shadowOf(Looper.getMainLooper()).idleFor(50001,TimeUnit.MILLISECONDS)
        assertTrue(shadowOf(service).isStoppedBySelf);assertNull(shadowOf(service).lastForegroundNotification)
        controller.destroy()
    }
    @Test fun cancellationInvalidatesQueuedStart(){
        ForegroundState.started();SetupSession.acquire(app);assertTrue(PairingLifetimeService.ensure(app))
        val queued=shadowOf(app).nextStartedService;PairingLifetimeService.stop(app)
        val controller=Robolectric.buildService(PairingLifetimeService::class.java).create()
        controller.get().onStartCommand(queued,0,1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf);assertNull(shadowOf(controller.get()).lastForegroundNotification)
        controller.destroy()
    }
}
