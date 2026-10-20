package app.bluedotoff

import android.content.Context
import app.bluedotoff.adb.WirelessWork
import app.bluedotoff.core.*

/** One apply/restore batch, shared by the screen (MainActivity.perform) and the after-restart job (R-1, 1.2.0).
 *  [canStart]: the screen's "still visible" rule or the job's "not stopped"; [background]: no screen holds this batch,
 *  so it never turns wireless debugging on or keeps it (the job uses the running watch's connection). */
internal object BatchPipeline {
    fun run(app:Context,targets:List<String>,groupMap:Map<String,List<String>>,hide:Boolean,show:Boolean,auto:Boolean,beforeUninstall:Boolean,
        reapplying:Set<String>,autoSource:ReapplySource,canStart:()->Boolean,background:Boolean,report:(BatchProgress)->Unit):BatchOutcome {
        val journal=JournalStore(app)
        val runtimePrefs=app.getSharedPreferences("ui-state",Context.MODE_PRIVATE)
        val results=mutableListOf<BatchResult>()
        val observed=linkedMapOf<String,Snapshot>()
        val runner=TargetBatch.Runner()
        var pipelineStopped=false
        var warning=""
        var failure=""
        var cursor=0
        val rememberResult:(BatchResult)->Unit={result->
            if(hide&&!show&&(auto||result.packageName in reapplying))ReapplyHistoryStore(app).record(result,if(auto)autoSource else ReapplySource.MANUAL)
        }
        try {
            WirelessWork.runBatch(app,!background,onCleanup={if(it.isNotBlank())warning=it},hold=!background,background=background) { session ->
            while(cursor<targets.size&&!runner.stopped&&!pipelineStopped) {
                if(!canStart()){failure="화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요.";break}
                report(BatchProgress(cursor,targets.size,BatchPhase.CONNECTING))
                session.run { active ->
                    val engine=SafetyEngine(active,journal,System::currentTimeMillis,{BootSession.current(app)},observe={phase,snapshot,wrote->
                        active.recordVerification(phase,snapshot,wrote)
                        if(phase=="settled_1500ms")observed[snapshot.identity.packageName]=snapshot
                    })
                    if(targets[cursor] in groupMap){
                        // Up to 8 consecutive shared groups in this helper (was one helper each: ~2 s start and close per group),
                        // their checks overlapped (1.1.4).
                        val groups=mutableListOf<List<String>>();var at=cursor
                        while(groups.size<8&&at<targets.size){val g=groupMap[targets[at]]?:break;groups+=g;at+=g.size}
                        SharedGroupEngine(active,journal,System::currentTimeMillis,{BootSession.current(app)},observe={phase,s,wrote->active.recordVerification(phase,s,wrote);if(phase=="settled_1500ms")observed[s.identity.packageName]=s})
                            .executeWindow(groups,hide,canStart){group,outcome->
                                val groupResults=outcome.fold({members->members.map{e->BatchResult.of(e.identity.packageName,e)}},{e->
                                    val saved=runCatching{journal.entries()}.getOrDefault(emptyList())
                                    group.map{p->BatchResult(p,saved.lastOrNull{it.identity.packageName==p},e.message?:"공유 앱 묶음을 확인해야 해요.")}
                                })
                                groupResults.forEach(rememberResult)
                                results.addAll(groupResults);cursor+=group.size
                                if(groupResults.any{!it.success}){if(!pipelineStopped)failure=groupResults.first{!it.success}.error;pipelineStopped=true;group.forEach{observed.remove(it)}}
                                report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                            }
                    } else if(targets.size>1) {
                        val completedBefore=results.size
                        var completed=0
                        val chunk=targets.drop(cursor).takeWhile{it !in groupMap}.take(BatchWindow.MAX_TARGETS)
                        val onResult:(BatchResult)->Unit={result->
                            rememberResult(result)
                            if(!result.success)observed.remove(result.packageName)
                            completed++;report(BatchProgress(completedBefore+completed,targets.size,BatchPhase.CHECKING))
                        }
                        val window=when{
                            hide->engine.userHideWindow(chunk,canStart=canStart,elapsed=android.os.SystemClock::elapsedRealtime,onResult=onResult)
                            show->engine.userShowWindow(chunk,canStart=canStart,elapsed=android.os.SystemClock::elapsedRealtime,onResult=onResult)
                            else->engine.userRestoreWindow(chunk,canStart=canStart,elapsed=android.os.SystemClock::elapsedRealtime,onResult=onResult)
                        }
                        results.addAll(window.results);cursor+=window.started;pipelineStopped=window.stopped
                        android.util.Log.i("bluedotOFF","window: ${window.started} apps ${if(hide)"hide" else if(show)"show" else "restore"}")
                        if(window.reason.isNotBlank())failure=window.reason
                    } else {
                        val start=android.os.SystemClock.elapsedRealtime()
                        var count=0
                        while(cursor<targets.size&&!runner.stopped&&BatchWindow.next(count,android.os.SystemClock.elapsedRealtime()-start)) {
                            if(!canStart()){failure="화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요.";break}
                            report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                            val pkg=targets[cursor]
                            val result=runner.run(pkg){if(show)engine.userShow(it,canStart) else engine.userOne(it,hide,canStart)}
                            rememberResult(result)
                            results.add(result);cursor++;count++
                            if(!result.success)observed.remove(pkg)
                            report(BatchProgress(cursor,targets.size,BatchPhase.CHECKING))
                        }
                    }
                    if(hide&&results.any{it.success})check(runtimePrefs.edit().putString("verifiedFirmware",active.compatibleFirmware().key).putInt("settlingSchema",2).commit())
                    report(BatchProgress(cursor,targets.size,BatchPhase.CLOSING))
                }
                // Cleanup failures remain separate from target success. Do not open another lease.
                if(warning.isNotEmpty()||failure.isNotEmpty())break
            }
            }
        }catch(e:Exception){failure=e.message?:"현재 상태를 확인하지 못했어요."}
        val untouched=if(runner.stopped||pipelineStopped)"앞선 작업의 복원이나 연결을 먼저 확인해야 해서 바꾸지 않았어요." else "작업을 멈춰서 이 앱은 바꾸지 않았어요."
        while(cursor<targets.size){results.add(BatchResult(targets[cursor++],error=untouched))}
        val outcome=BatchOutcome(hide,results,journal.entries(),observed.values.toList(),warning,failure,show=show,auto=auto,beforeUninstall=beforeUninstall)
        if(!hide&&!show&&ScopePreferences(app).pending!=null&&!ScopePreferences(app).finishRestore(outcome))return outcome.copy(error=outcome.error.ifBlank{"일부 앱의 복원을 확인하지 못해 이전 적용 범위를 유지했어요. 설정에서 다시 확인해주세요."})
        return outcome
    }
}
