package app.bluedotoff

import app.bluedotoff.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatchRuntimeTest {
    @Test fun oneOwnerKeepsProgressUntilCleanupCompletesAndDoesNotOverwriteSuccess(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val before=BatchRuntime.state.value.id
        assertTrue(BatchRuntime.start(70){update->
            update(BatchProgress(70,70,BatchPhase.CLOSING));entered.countDown()
            assertTrue(release.await(3,TimeUnit.SECONDS))
            BatchOutcome(true,emptyList(),emptyList(),emptyList(),cleanupWarning="manual OFF needed")
        })
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        val observer=BatchRuntime.state.value
        assertTrue(observer.busy);assertEquals(99,observer.progress!!.percent)
        assertFalse(BatchRuntime.start(1){error("second owner cannot run")})
        release.countDown()
        val final=runBlocking{withTimeout(3000){BatchRuntime.state.first{it.id>before&&!it.busy}}}
        assertEquals(100,final.progress!!.percent)
        assertEquals("manual OFF needed",final.outcome!!.cleanupWarning)
        assertEquals("",final.outcome!!.error)
    }
}
