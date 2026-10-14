package app.bluedotoff

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReapplyHistoryStoreTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun result(pkg:String,error:String="")=BatchResult(pkg,Entry(Identity(pkg,0,12345,listOf("signer"),1,1,1),"앱",256,true,TargetState.APPLIED,1,"firmware","boot",true),error)
    @Before fun clearHistory(){listOf("reapply-history.jsonl","test-log.txt").forEach{File(context.filesDir,it).delete()}}
    @Test fun successFailureAndSourceSurviveAStoreRecreation(){
        val history=ReapplyHistoryStore(context)
        history.record(result("test.one"),ReapplySource.BACKGROUND,1000)
        history.record(result("test.two",error="failed"),ReapplySource.APP_OPEN,2000)
        val read=ReapplyHistoryStore(context).entries()
        assertEquals(listOf("test.two","test.one"),read.map{it.packageName})
        assertFalse(read[0].success);assertTrue(read[1].success)
        assertEquals(ReapplySource.APP_OPEN,read[0].source)
    }
    @Test fun recentHistoryIsBounded(){
        val history=ReapplyHistoryStore(context)
        repeat(ReapplyHistoryStore.MAX_ITEMS+3){history.record(result("test.app$it"),ReapplySource.MANUAL,it+1L)}
        val read=history.entries()
        assertEquals(ReapplyHistoryStore.MAX_ITEMS,read.size);assertEquals("test.app202",read.first().packageName)
    }
    @Test fun importsOnlyAutomaticResultsAndDoesNotImportAgain(){
        File(context.filesDir,"test-log.txt").writeText("2026-10-14 08:00:00 | result | pkg=test.map mode=watch ok=true\n2026-10-14 09:00:00 | result | pkg=test.map mode=auto ok=false error=FAILED_SAFE\n2026-10-14 10:00:00 | result | pkg=test.map mode=restore ok=true\n")
        val history=ReapplyHistoryStore(context)
        assertEquals(2,history.entries().size)
        File(context.filesDir,"test-log.txt").appendText("2026-10-14 11:00:00 | result | pkg=test.map mode=watch ok=true\n")
        assertEquals(2,history.entries().size)
    }
    @Test fun malformedRowsDoNotInventSuccessOrBlockNewRecords(){
        File(context.filesDir,"reapply-history.jsonl").writeText("broken\n{\"success\":true}\n")
        val history=ReapplyHistoryStore(context)
        assertTrue(history.entries().isEmpty())
        history.record(BatchResult("test.map",error="no connection"),ReapplySource.BACKGROUND,1000)
        assertFalse(history.entries().single().success)
    }
    @Test fun anUnwritableDisplayFileDoesNotEscapeIntoTheSafetyEngine(){
        File(context.filesDir,"reapply-history.jsonl").mkdirs()
        try{ReapplyHistoryStore(context).record(BatchResult("test.map"),ReapplySource.BACKGROUND,1000)}
        finally{File(context.filesDir,"reapply-history.jsonl").delete()}
    }
    @Test fun aPartialLastLineDoesNotSwallowTheNextVerifiedResult(){
        File(context.filesDir,"reapply-history.jsonl").writeText("{\"id\":\"interrupted")
        val history=ReapplyHistoryStore(context)
        history.record(result("test.map"),ReapplySource.BACKGROUND,1000)
        assertTrue(history.entries().single().success)
    }
}
