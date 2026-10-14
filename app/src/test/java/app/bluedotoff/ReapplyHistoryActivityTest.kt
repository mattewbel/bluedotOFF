package app.bluedotoff

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class ReapplyHistoryActivityTest {
    @Test fun recreatingTheReapplyTabReloadsItsSavedRowsWithoutAConnection(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir,"reapply-history.jsonl").delete()
        File(context.filesDir,"test-log.txt").delete()
        val entry=Entry(Identity("test.map",0,12345,listOf("signer"),1,1,1),"지도",256,true,TargetState.APPLIED,1,"firmware","boot",true)
        ReapplyHistoryStore(context).record(BatchResult("test.map",entry),ReapplySource.BACKGROUND,1000)
        val saved=Bundle().apply{putString("page","HISTORY");putString("historyView","REAPPLY")}
        val controller=Robolectric.buildActivity(MainActivity::class.java).create(saved)
        val getter=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        try {
            val until=System.nanoTime()+5_000_000_000L
            var model=getter.invoke(controller.get()) as UiModel
            while(model.reapplyHistoryLoading&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);model=getter.invoke(controller.get()) as UiModel}
            assertEquals(Page.HISTORY,model.page);assertEquals(HistoryView.REAPPLY,model.historyView)
            assertEquals("test.map",model.reapplyHistory.single().packageName)
            assertFalse(model.reapplyHistoryLoading)
        }finally{controller.destroy()}
    }
}
