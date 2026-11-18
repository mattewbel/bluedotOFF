package app.bluedotoff

import android.content.pm.PackageManager
import app.bluedotoff.adb.WatchInstallCheck
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=android.app.Application::class)
class WatchInstallCheckTest {
    private var pending:Runnable?=null
    private var recovered=0
    private fun monitor(validate:()->Unit)=WatchInstallCheck.Monitor(validate,{task,delay->
        assertNull(pending);assertEquals(1250L,delay);pending=task;true
    },{recovered++},{throw it})
    @Test fun monitorRepeatsWholeCheckOnlyAfterALookupException() {
        var firstRead = 0
        var lastRead = 0
        val monitor=monitor {
            WatchInstallCheck.lookup { firstRead++ }
            WatchInstallCheck.lookup { if (++lastRead == 1) error("service unavailable") }
        }
        assertFalse(monitor.check())
        assertFalse(monitor.check())
        assertEquals(1, firstRead)
        pending!!.run()
        assertEquals(1,recovered)
        assertEquals(2, firstRead)
        assertEquals(2, lastRead)
    }

    @Test fun repeatFailureAndChangedIdentityAreTerminal() {
        var reads = 0
        val unavailable=monitor { WatchInstallCheck.lookup { reads++; error("service unavailable") } }
        assertFalse(unavailable.check())
        assertThrows(WatchInstallCheck.LookupUnavailable::class.java) { pending!!.run() }
        assertEquals(2, reads)
        reads = 0;pending=null
        val changed=monitor {
                WatchInstallCheck.lookup { if (++reads == 1) error("service unavailable") }
                check(false) { "different installation" }
        }
        assertFalse(changed.check())
        assertThrows(IllegalStateException::class.java) { pending!!.run() }
        assertEquals(0,recovered)
        assertEquals(2, reads)
    }

    @Test fun aRejectedOrThrowingSchedulerStopsInsteadOfWaitingForever() {
        for(throws in listOf(false,true)){
            var failures=0
            val monitor=WatchInstallCheck.Monitor(
                { WatchInstallCheck.lookup { error("temporarily unavailable") } },
                { _,_ -> if(throws)error("scheduler unavailable") else false },
                { fail("not recovered") }, { failures++ })
            assertFalse(monitor.check())
            assertEquals(1,failures)
        }
    }

    @Test fun securityMissingAndMismatchNeverGetASecondChance() {
        for (failure in listOf(SecurityException(), PackageManager.NameNotFoundException(), IllegalStateException(),
            IllegalArgumentException(), NullPointerException(), RuntimeException(android.os.DeadObjectException()),
            RuntimeException(SecurityException()))) {
            var checks = 0
            val thrown = assertThrows(Exception::class.java) {
                monitor {
                    checks++
                    if (failure is IllegalStateException) throw failure
                    WatchInstallCheck.lookup { throw failure }
                }.check()
            }
            assertSame(failure, thrown)
            assertEquals(1, checks)
            assertNull(pending)
        }
    }
}
