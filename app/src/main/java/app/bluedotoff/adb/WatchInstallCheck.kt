package app.bluedotoff.adb

import android.os.DeadObjectException
import android.os.RemoteException

internal object WatchInstallCheck {
    class LookupUnavailable(cause: RuntimeException) : Exception(cause)

    fun <T> lookup(read: () -> T): T = try {
        read()
    } catch (denied: SecurityException) {
        throw denied
    } catch (unavailable: RuntimeException) {
        // The framework wraps a dead system service. Its event subscriptions may already be gone.
        if (unavailable.javaClass != RuntimeException::class.java && unavailable.javaClass != IllegalStateException::class.java) {
            throw unavailable
        }
        val cause = unavailable.cause
        if (cause != null && (cause !is RemoteException || cause is DeadObjectException || cause.cause != null)) {
            throw unavailable
        }
        throw LookupUnavailable(unavailable)
    }

    // Only background monitoring can wait. Startup and each Binder request still validate from scratch.
    class Monitor(
        private val validate: () -> Unit,
        private val schedule: (Runnable, Long) -> Boolean,
        private val recovered: () -> Unit,
        private val failed: (Exception) -> Unit
    ) {
        private var waiting = false

        @Synchronized fun check(): Boolean {
            if (waiting) return false
            try {
                validate()
                return true
            } catch (e: LookupUnavailable) {
                waiting = true
                val posted = try {
                    schedule(Runnable(::recheck), 1_250L)
                } catch (error: RuntimeException) {
                    failed(error)
                    return false
                }
                if (!posted) failed(e)
            } catch (e: Exception) {
                failed(e)
            }
            return false
        }

        private fun recheck() {
            synchronized(this) {
                try {
                    validate()
                } catch (e: Exception) {
                    failed(e)
                    return
                }
                waiting = false
            }
            recovered()
        }
    }
}
