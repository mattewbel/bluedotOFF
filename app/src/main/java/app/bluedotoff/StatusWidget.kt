package app.bluedotoff

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.bluedotoff.adb.WatchClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Home-screen widget (1.3.0): one 4x1 bar with only what matters (owner 2026-10-29): the watch, the hidden apps or the
 *  apps that need the person, and when the registered apps were last checked. A tap opens bluedotOFF the way a
 *  notification does; the refresh icon (only while the watch runs) asks the watch to check every registered app now, the
 *  same check it runs after a change event. It is redrawn when something already happens (watch event, watch
 *  start/stop, a finished check, leaving the app, resize, time zone or language change), never on a timer. The printed
 *  time is a real check of the registered apps (Home's full check or the watch's), never the redraw time, and "감시 중"
 *  is the live connection at the redraw (GPT review 2026-10-29). Dates are absolute ("Oct 28, 21:40"), so a date change
 *  cannot make a line wrong ("today" would be). */
class StatusWidgetReceiver:AppWidgetProvider(){
    // Every other broadcast here only redraws: launcher update or resize, time zone, clock and language (they change how
    // the printed times and words read). goAsync keeps the process until the redraw is done.
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action in REDRAW){val pending=goAsync();StatusWidget.update(context){pending.finish()}} else super.onReceive(context,intent)
    }
    private companion object{val REDRAW=setOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE,AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_LOCALE_CHANGED)}
}
/** The refresh icon's target. Not exported: only the widget's own PendingIntent reaches it, so no other app can start a check. */
class WidgetRefreshReceiver:android.content.BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent)=StatusWidget.refresh(context)
}

object StatusWidget {
    /** Everything the widget prints: stored state plus whether this process holds the watch (no device read). */
    data class State(val watching:Boolean,val checkedAt:Long,val applied:Int,val reapply:Int,val check:Int,val refreshing:Boolean=false)

    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var refreshing=false
    private fun prefs(c:Context)=c.getSharedPreferences("widget",Context.MODE_PRIVATE)
    /** Cheap when no widget is placed: nothing is read. Off the main thread; a failure only leaves the old picture. */
    fun update(context:Context,done:()->Unit={}){
        val c=context.applicationContext
        worker.execute{try{runCatching{
            val manager=AppWidgetManager.getInstance(c)
            val ids=manager.getAppWidgetIds(ComponentName(c,StatusWidgetReceiver::class.java))
            if(ids.isNotEmpty()){val s=state(c);ids.forEach{manager.updateAppWidget(it,views(c,s,heightDp(manager.getAppWidgetOptions(it))))}}
        }.onFailure{android.util.Log.w("bluedotOFF","Widget could not be updated")}}finally{done()}}
    }
    /** The refresh icon: the watch checks every registered app (re-hiding what lost it, as after a change event). The time
     *  moves only when every app was checked; without the watch it only redraws (the icon is not shown then). */
    fun refresh(context:Context){
        val c=context.applicationContext
        if(refreshing)return
        if(!WatchClient.available(c)){update(c);return}
        refreshing=true;update(c)
        WatchClient.checkNow(c){checked->
            if(checked)prefs(c).edit().putLong("checkedAt",System.currentTimeMillis()).apply()
            refreshing=false;update(c)
        }
    }
    /** The smallest height the launcher gives this widget, in dp; 0 when it has not said. */
    private fun heightDp(o:Bundle):Int{
        @Suppress("DEPRECATION") val sizes=if(Build.VERSION.SDK_INT>=31)o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null
        return sizes?.minOfOrNull{it.height.toInt()}?:o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0)
    }
    fun state(c:Context,watching:Boolean=WatchClient.available(c)):State{
        val model=UiModel(entries=JournalStore(c).entries())
        val checkedAt=maxOf(c.getSharedPreferences("ui-state",0).getLong("lastCheckedAt",0L),prefs(c).getLong("checkedAt",0L))
        return State(watching,checkedAt,model.lastVerifiedAppliedCount,model.reapplyCount,model.otherCheckCount,refreshing)
    }
    fun time(c:Context,at:Long):String{
        val locale=c.resources.configuration.locales[0]
        return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"Mdjmm"),locale).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    }
    private fun open(c:Context,reapply:Boolean,check:Boolean):PendingIntent{
        val intent=Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The notification route: it only navigates, and the setup screens stay in front until setup is done.
        // "check": the apps to check yourself are listed on Home, not on the reapply page.
        if(reapply)intent.setAction("app.bluedotoff.REAPPLY").apply{if(check)putExtra("check",true)} else intent.setAction(Intent.ACTION_MAIN)
        return PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    /** [heightDp]: what the launcher gives (0 = unknown). When two lines do not fit (a large font on a one-row widget)
     *  the second line is left out and the first says the watch state itself (GPT review rc3). */
    fun views(c:Context,s:State,heightDp:Int=0):RemoteViews{
        fun t(value:String)=UiText.text(c,value)
        val v=RemoteViews(c.packageName,R.layout.widget_status)
        val attention=s.watching&&(s.reapply>0||s.check>0)
        v.setOnClickPendingIntent(R.id.widget_root,open(c,attention,s.reapply==0))
        v.setImageViewResource(R.id.widget_dot,when{!s.watching->R.drawable.widget_dot_off;attention->R.drawable.widget_dot_attention;else->R.drawable.widget_dot})
        val fs=c.resources.configuration.fontScale
        fun line(sp:Int)=sp*fs*1.5f // one line of text, with room for Korean and other fallback fonts (about 1.47 sp in tests)
        val two=heightDp<=0||heightDp-8>=line(16)+2+line(13) // padding 4 + 4, status padding 2
        v.setTextViewText(R.id.widget_title,t(when{
            !s.watching->"감시 꺼짐"
            s.reapply>0->"다시 적용할 앱 ${s.reapply}개"
            s.check>0->"직접 확인할 앱 ${s.check}개"
            two->"${s.applied}개 앱 숨김 유지"
            else->"감시 중"}))
        v.setViewVisibility(R.id.widget_status,if(two)View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.widget_status,t(when{
            !s.watching->"눌러서 bluedotOFF 열기"
            s.refreshing->"확인 중…"
            s.checkedAt<=0L->"감시 중"
            else->"감시 중 · ${time(c,s.checkedAt)}"}))
        v.setViewVisibility(R.id.widget_refresh,if(s.watching)View.VISIBLE else View.GONE)
        if(s.watching){
            v.setContentDescription(R.id.widget_refresh,t("지금 확인"))
            v.setOnClickPendingIntent(R.id.widget_refresh,PendingIntent.getBroadcast(c,1,Intent(c,WidgetRefreshReceiver::class.java),PendingIntent.FLAG_IMMUTABLE))
        }
        return v
    }
}
