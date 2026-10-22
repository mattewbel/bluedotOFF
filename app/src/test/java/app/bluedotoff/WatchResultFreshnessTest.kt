package app.bluedotoff

import android.app.Application
import android.os.Build
import android.os.Looper
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Codex review 2026-10-22: a watch result saved after an earlier "shown" read must win over that read in Detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchResultFreshnessTest {
    @Test fun aNewWatchResultMustNotLeaveAnOlderOnSnapshotMarkedFresh(){
        val c=RuntimeEnvironment.getApplication()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val getter=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        val setter=MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}
        val activity=controller.get()
        fun model()=getter.invoke(activity) as UiModel
        fun await(condition:()->Boolean){
            val until=System.nanoTime()+5_000_000_000L
            while(!condition()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertTrue("Activity callback did not finish",condition())
        }
        try{
            val id=Identity("demo.watch",0,10792,listOf("demo"),1,1,1)
            val old=Entry(id,"Demo",256,true,TargetState.NEEDS_REAPPLY_POLICY,1,Build.FINGERPRINT,BootSession.current(c),true)
            val on=Snapshot(id,"Demo",listOf(id.packageName),true,true,true,false,0x300,0x300,0,4,4,false)
            setter.invoke(activity,UiModel(gate=Gate.READY,page=Page.DETAIL,entries=listOf(old),candidates=listOf(on),selected=id.packageName,freshPackages=setOf(id.packageName)))
            // Same durable completion and event as WatchClient.signal, without a privileged write.
            assertTrue(JournalStore(c).commit(old.copy(state=TargetState.APPLIED,lastVerifiedTime=2,settlingVerified=true)))
            android.provider.Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
            ForegroundState.started();ForegroundState.resumed();LifecycleEvents.changed()
            await{model().entries.singleOrNull()?.lastVerifiedTime==2L}
            assertEquals(TargetState.APPLIED,model().entries.single().state)
            assertTrue(model().connected)
            assertEquals(0x100,model().candidates.single().bit)
            assertFalse("A verified newer OFF result left the old ON read marked fresh, so Detail reports reapply needed",id.packageName in model().freshPackages)
        }finally{ForegroundState.paused();ForegroundState.stopped();controller.destroy()}
    }
}
