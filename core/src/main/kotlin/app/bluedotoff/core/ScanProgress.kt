package app.bluedotoff.core

/** Actual completed app checks. Final journal/connection validation still follows. */
data class ScanProgress(val completed:Int,val total:Int) {
    init {require(total>0&&completed in 0..total)}
    val percent get()=(completed.toLong()*100/total).toInt().coerceAtMost(99)
    val finishing get()=completed==total
}
