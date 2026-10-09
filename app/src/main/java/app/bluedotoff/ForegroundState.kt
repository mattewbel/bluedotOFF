package app.bluedotoff

import java.util.concurrent.atomic.AtomicInteger

/** Counts visible Activities only; a pairing service never counts as user foreground use. */
object ForegroundState {
    private val activities=AtomicInteger()
    private val focused=AtomicInteger()
    val canStartTargets get()=active&&focused.get()>0
    fun resumed(){focused.incrementAndGet()}
    fun paused(){focused.updateAndGet{(it-1).coerceAtLeast(0)}}
    val active get()=activities.get()>0
    fun started(){activities.incrementAndGet()}
    fun stopped(){activities.updateAndGet{(it-1).coerceAtLeast(0)}}
}

/** A foreground screen observes a durable journal event without any privileged write. */
object LifecycleEvents {
    @Volatile var listener:(()->Unit)?=null
    fun changed(){android.os.Handler(android.os.Looper.getMainLooper()).post{listener?.invoke()}}
}
