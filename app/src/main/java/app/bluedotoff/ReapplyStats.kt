package app.bluedotoff

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ReapplySummary(val since:Long,val count:Int,val topLabel:String?,val days:Long)

/** "What bluedotOFF did" (1.3.0, owner + GPT 2026-10-28). Display only, like [ReapplyHistoryStore]: it never controls a
 *  permission write. The history keeps the last 200 rows, so it cannot give a total; the counter starts when this
 *  version first runs and the screen names that date. Only facts the app confirmed: a successful automatic reapply that wrote the hide again (a check that
 *  found it still hidden, e.g. after an app update that kept it, is not one). */
object ReapplyStats {
    val MILESTONES=listOf(100,200,365,500,730,1000)
    private fun prefs(c:Context)=c.getSharedPreferences("reapply-stats",Context.MODE_PRIVATE)
    /** The counter's start; the first call stores it and later calls keep it. */
    fun since(c:Context):Long{
        val p=prefs(c);val stored=p.getLong("since",0L)
        if(stored>0L)return stored
        val now=System.currentTimeMillis();p.edit().putLong("since",now).commit();return now
    }
    fun count(c:Context)=prefs(c).getInt("count",0)
    /** Called by [ReapplyHistoryStore.record] under its lock, so watch and app results never race. */
    internal fun add(c:Context){since(c);val p=prefs(c);p.edit().putInt("count",p.getInt("count",0)+1).commit()}
    fun counts(source:ReapplySource,success:Boolean,rewrote:Boolean)=success&&rewrote&&source!=ReapplySource.MANUAL
    /** Calendar days with bluedotOFF, the install day being day 1 (Android's install time, so a reinstall starts again). */
    fun days(c:Context,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):Long{
        val installed=runCatching{c.packageManager.getPackageInfo(c.packageName,0).firstInstallTime}.getOrDefault(now)
        return ChronoUnit.DAYS.between(Instant.ofEpochMilli(installed).atZone(zone).toLocalDate(),Instant.ofEpochMilli(now).atZone(zone).toLocalDate()).coerceAtLeast(0)+1
    }
    /** The app that the recent automatic rows name most often (the newest wins a tie), or null without such rows. */
    fun top(rows:List<ReapplyHistoryItem>):String?=rows.filter{counts(it.source,it.success,it.rewrote)}.groupBy{it.packageName}.values
        .maxWithOrNull(compareBy<List<ReapplyHistoryItem>>{it.size}.thenBy{group->group.maxOf{it.at}})?.maxByOrNull{it.at}?.label
    fun summary(c:Context,rows:List<ReapplyHistoryItem>)=ReapplySummary(since(c),count(c),top(rows),days(c))
    /** The highest day milestone reached and not shown yet; marks it shown (Home says it once). */
    fun nextMilestone(c:Context,days:Long):Int?{
        val reached=MILESTONES.lastOrNull{days>=it}?:return null
        val p=prefs(c)
        if(p.getInt("milestoneShown",0)>=reached)return null
        p.edit().putInt("milestoneShown",reached).apply()
        return reached
    }
}
