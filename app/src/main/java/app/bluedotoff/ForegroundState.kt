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

/** Timing counters for speed measurements (no package names). Reset per helper session, logged at its end. */
object Perf {
    private val n=java.util.concurrent.ConcurrentHashMap<String,LongArray>()
    fun add(key:String,startNanos:Long){n.computeIfAbsent(key){LongArray(2)}.let{synchronized(it){it[0]++;it[1]+=System.nanoTime()-startNanos}}}
    fun reset()=n.clear()
    inline fun <T> time(key:String,block:()->T):T{val t=System.nanoTime();try{return block()}finally{add(key,t)}}
    fun summary()=n.entries.sortedBy{it.key}.joinToString(" "){(k,v)->"$k=${v[0]}x/${v[1]/1_000_000}ms"}
}

/** A foreground screen observes a durable journal event without any privileged write. */
object LifecycleEvents {
    @Volatile var listener:(()->Unit)?=null
    fun changed(){android.os.Handler(android.os.Looper.getMainLooper()).post{listener?.invoke()}}
}
