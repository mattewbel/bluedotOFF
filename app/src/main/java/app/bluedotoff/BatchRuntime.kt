package app.bluedotoff

import app.bluedotoff.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatchOutcome(val hide:Boolean,val results:List<BatchResult>,val entries:List<Entry>,
    val snapshots:List<Snapshot>,val cleanupWarning:String="",val error:String="",val completedAt:Long=0,val show:Boolean=false)
data class BatchUiState(val id:Long=0,val progress:BatchProgress?=null,val outcome:BatchOutcome?=null,val hide:Boolean=true,val show:Boolean=false) {
    val busy get()=progress!=null&&progress.phase!=BatchPhase.FINISHED
}

/** In-memory ownership survives Activity recreation; no boot receiver, retry or background daemon. */
object BatchRuntime {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(BatchUiState())
    val state=mutable.asStateFlow()
    @Synchronized fun start(total:Int,hide:Boolean=true,show:Boolean=false,work:suspend ((BatchProgress)->Unit)->BatchOutcome):Boolean {
        if(mutable.value.busy)return false
        val id=mutable.value.id+1
        mutable.value=BatchUiState(id,BatchProgress(0,total),hide=hide,show=show)
        scope.launch {
            val outcome=try {work {progress->mutable.value=BatchUiState(id,progress,hide=hide,show=show)}}
                catch(_:Exception){BatchOutcome(hide,emptyList(),emptyList(),emptyList(),error="작업 결과를 확인하지 못했어요. 등록 기록은 그대로예요.",show=show)}
            mutable.value=BatchUiState(id,BatchProgress(total,total,BatchPhase.FINISHED),outcome.copy(completedAt=System.nanoTime()/1_000_000),hide,show)
        }
        return true
    }
}

fun BatchOutcome.summary():String {
    val success=results.count{it.success}
    val failed=results.size-success
    return if(failed==0&&error.isBlank()) {
        if(hide)"${success}개 앱에 bluedotOFF 적용 완료" else if(show)"${success}개 앱의 위치 표시를 켰어요." else "${success}개 앱을 원래 표시 상태로 복원했어요."
    } else {
        if(hide)"bluedotOFF 적용 ${success}개 · 확인 필요 ${failed}개" else if(show)"표시 켜기 ${success}개 · 확인 필요 ${failed}개" else "원래 상태 복원 ${success}개 · 확인 필요 ${failed}개"
    }
}
