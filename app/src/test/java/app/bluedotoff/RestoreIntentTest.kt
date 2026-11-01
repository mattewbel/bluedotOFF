package app.bluedotoff

import android.app.Application
import android.os.Looper
import android.os.Bundle
import app.bluedotoff.adb.LocalAdbSession
import org.robolectric.Shadows.shadowOf
import app.bluedotoff.core.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class RestoreIntentTest {
    @Before fun clearPreviousTestBatch(){
        val state=BatchRuntime::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(BatchRuntime) as kotlinx.coroutines.flow.MutableStateFlow<BatchUiState>
        state.value=BatchUiState()
    }
    private val fw=Firmware("samsung",37,"90000","fixture","b","m","abi")
    private val id=Identity("test.retry",0,10123,listOf("cert"),100,1,100)
    private val base=Snapshot(id,"Retry",listOf(id.packageName),true,true,true,false,0x300,0x300,0,0,0,false)
    private val failed=Entry(id,"Retry",256,true,TargetState.FAILED_SAFE,200,fw.key,"boot",false,invariantDigest=base.invariantDigest(),retryHide=true)
    // This Settings action is available even while Home has no usable connection yet.
    private fun restore(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(a)
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as UiModel
    private fun setModel(a:MainActivity,m:UiModel)=MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}.invoke(a,m)

    private fun await(done:()->Boolean){
        val until=System.nanoTime()+5_000_000_000L
        while(!done()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue("Restore request did not finish",done())
    }

    @Test fun failedCancellationSaveMustNotPublishACancelledScreenState(){
        val c=RuntimeEnvironment.getApplication()
        val store=JournalStore(c)
        assertTrue(store.commit(failed))
        val ctl=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val blocked=File(c.noBackupFilesDir,"original-bit-journal.json.new")
        try{
            assertTrue(store.commit(failed))
            setModel(ctl.get(),model(ctl.get()).copy(entries=listOf(failed)))
            assertTrue("Fixture directory: $blocked exists=${blocked.exists()} parent=${blocked.parentFile?.exists()}",blocked.mkdir())
            restore(ctl.get())
            await{!model(ctl.get()).busy}
            assertTrue("Fixture: cancellation could not be saved",store.entries().single().retryHide)
            assertEquals("UI must not claim cancellation that was not stored",store.entries().single().retryHide,model(ctl.get()).entries.single().retryHide)
            assertTrue(model(ctl.get()).notice.contains("저장"))
            assertFalse(store.entries().single().restoreRequested)
        }finally{blocked.delete();ctl.pause().stop().destroy()}
    }

    @Test fun aWatchReadAlreadyInProgressMustRespectARestoreCancellation(){
        val c=RuntimeEnvironment.getApplication()
        val store=JournalStore(c)
        assertTrue(store.commit(failed))
        val ctl=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val readStarted=CountDownLatch(1);val resumeRead=CountDownLatch(1)
        val problem=AtomicReference<Throwable?>()
        val writes=mutableListOf<Int>()
        var snapshot=base
        var first=true
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String):Snapshot{
                if(first){first=false;readStarted.countDown();check(resumeRead.await(10,TimeUnit.SECONDS))}
                return snapshot
            }
            override fun writeFineBit(packageName:String,originalBit:Int){writes+=originalBit;snapshot=snapshot.copy(fineFlags=(snapshot.fineFlags and 256.inv()) or originalBit)}
        }
        val worker=Thread{try{WatchEngine(port,store,{400},{"boot"},wait={}).reapply(setOf(id.packageName),{!BatchRuntime.state.value.busy},{},{})}catch(t:Throwable){problem.set(t)}}
        try{
            assertTrue(store.commit(failed))
            setModel(ctl.get(),model(ctl.get()).copy(entries=listOf(failed)))
            worker.start();assertTrue("Watch never read: error=${problem.get()} entries=${store.entries()}",readStarted.await(5,TimeUnit.SECONDS))
            restore(ctl.get())
            assertTrue("In-flight safety work must finish off the UI thread",model(ctl.get()).busy)
            resumeRead.countDown();worker.join(10_000)
            assertFalse(worker.isAlive);problem.get()?.let{throw it}
            await{!model(ctl.get()).busy}
            assertTrue(store.entries().single().restoreRequested)
            assertFalse(WatchPolicy.eligible(store.entries().single()))
            val completedWrites=writes.toList()
            WatchEngine(port,store,{500},{"boot"},wait={}).reapply(setOf(id.packageName),{true},{},{})
            assertEquals("No more automatic writes after the restore intent is saved",completedWrites,writes)
        }finally{resumeRead.countDown();worker.join(10_000);ctl.pause().stop().destroy()}
    }
    @Test fun screenRecreationDoesNotDiscardTheRestorePreparation(){
        val c=RuntimeEnvironment.getApplication();val store=JournalStore(c)
        var ctl=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertTrue(store.commit(failed));setModel(ctl.get(),model(ctl.get()).copy(entries=listOf(failed)))
        val held=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=Thread{LocalAdbSession.exclusive{held.countDown();check(release.await(10,TimeUnit.SECONDS))}}
        worker.start();assertTrue(held.await(5,TimeUnit.SECONDS))
        try{
            restore(ctl.get());assertTrue(BatchRuntime.state.value.busy)
            val saved=Bundle();ctl.pause().saveInstanceState(saved).stop().destroy()
            ctl=Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
            release.countDown();worker.join(10_000)
            await{!BatchRuntime.state.value.busy&&!model(ctl.get()).busy}
            assertTrue(store.entries().single().restoreRequested)
            assertNotNull(model(ctl.get()).completion)
            assertTrue(model(ctl.get()).notice.contains("연결"))
        }finally{release.countDown();worker.join(10_000);ctl.pause().stop().destroy()}
    }
}
