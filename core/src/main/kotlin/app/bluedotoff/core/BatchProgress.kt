package app.bluedotoff.core

enum class BatchPhase { CONNECTING, CHECKING, CLOSING, FINISHED }
data class BatchProgress(val completed:Int, val total:Int, val phase:BatchPhase=BatchPhase.CONNECTING) {
    init { require(total>0 && completed in 0..total) }
    // 100% is reserved for the result, durable journal and connection cleanup.
    val percent get()=if(phase==BatchPhase.FINISHED)100 else ((completed.toLong()*100/total).toInt()).coerceAtMost(99)
}

/** Time/count budget applies between targets. Never interrupt an atomic SafetyEngine call. */
object BatchWindow {
    // 48 per helper process (was 12): fewer helper starts. Measured 2026-10-11: ~0.8-1 s per start.
    const val MAX_TARGETS=48
    const val MAX_WORK_MS=55000L
    fun next(count:Int,elapsed:Long)=count<MAX_TARGETS && (count==0 || elapsed<MAX_WORK_MS)
}

/** Count reflects attempted targets; success is published only after durable final verification. */
data class HideWindowOutcome(val results:List<BatchResult>,val started:Int,val stopped:Boolean,val reason:String="") {
    companion object {
        // Targets waiting for their 500 ms / 1500 ms checks at once (was 4). Every target still gets all three checks.
        const val MAX_IN_FLIGHT=12
    }
}
