package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** 1.3.0 home-screen widget, a 4x1 bar (owner 2026-10-29): greyed out without the watch, its time is the last real
 *  check, the refresh icon asks the watch to check, and a large font keeps the watch state on one line. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],application=Application::class,qualifiers="ko-rKR")
class StatusWidgetTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun entry(pkg:String,label:String,state:TargetState=TargetState.APPLIED)=
        Entry(Identity(pkg,0,10050,listOf("fixture"),1,1,1),label,256,true,state,1,"fp|key","count:1",true,invariantDigest="fixture",settlingVerified=true)
    private fun state(watching:Boolean)=StatusWidget.state(context,watching)
    private fun checkedAt(at:Long)=context.getSharedPreferences("ui-state",0).edit().putLong("lastCheckedAt",at).commit()
    private fun render(s:StatusWidget.State,heightDp:Int=0):View=StatusWidget.views(context,s,heightDp).apply(context,FrameLayout(context))
    /** The text of a view that is shown, i.e. it and every parent are visible. */
    private fun View.text(id:Int):String?{
        val view=findViewById<TextView>(id);var v:View?=view
        while(v!=null&&v!==this){if(v.visibility!=View.VISIBLE)return null;v=v.parent as? View}
        return view.text.toString()
    }
    private fun View.shown(id:Int)=findViewById<View>(id).visibility==View.VISIBLE
    @Before fun journal(){
        assertTrue(JournalStore(context).commit(entry("demo.map","Map")))
        assertTrue(JournalStore(context).commit(entry("demo.chat","Chat")))
        checkedAt(1_000_000L)
    }

    @Test fun withoutTheWatchItIsGreyAndOnlyOpensTheApp(){
        val v=render(state(false))
        assertEquals("감시 꺼짐",v.text(R.id.widget_title))
        assertEquals("눌러서 bluedotOFF 열기",v.text(R.id.widget_status))
        assertFalse(v.shown(R.id.widget_refresh))
    }
    @Test fun watchingItSaysHowManyStayHiddenAndWhenTheyWereChecked(){
        val v=render(state(true))
        assertEquals("2개 앱 숨김 유지",v.text(R.id.widget_title))
        assertEquals("감시 중 · ${StatusWidget.time(context,1_000_000L)}",v.text(R.id.widget_status))
        assertTrue(v.shown(R.id.widget_refresh))
        assertEquals("지금 확인",v.findViewById<View>(R.id.widget_refresh).contentDescription)
        assertEquals("확인 중…",render(state(true).copy(refreshing=true)).text(R.id.widget_status))
    }
    @Test fun theWatchIsTheLiveConnectionNotAStoredFlag(){
        // No watch in this process: a stored "running" flag from an earlier run is not enough.
        context.getSharedPreferences("ui-state",0).edit().putBoolean("watchRunning",true).commit()
        BackgroundWatch.set(context,true)
        assertFalse(StatusWidget.state(context).watching)
    }
    @Test fun theTimeIsTheLastRealCheckNotTheRedraw(){
        assertEquals(1_000_000L,state(true).checkedAt)
        // The watch's own check of every registered app (the refresh icon) counts when it is newer.
        context.getSharedPreferences("widget",0).edit().putLong("checkedAt",2_000_000L).commit()
        assertEquals(2_000_000L,state(true).checkedAt)
        // Before any check, no time is claimed.
        context.getSharedPreferences("ui-state",0).edit().remove("lastCheckedAt").commit()
        context.getSharedPreferences("widget",0).edit().remove("checkedAt").commit()
        assertEquals("감시 중",render(state(true)).text(R.id.widget_status))
    }
    @Test fun withoutTheWatchRefreshOnlyRedrawsAndKeepsTheTime(){
        StatusWidget.refresh(context)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(0L,context.getSharedPreferences("widget",0).getLong("checkedAt",0L))
    }
    @Test fun theRefreshIconAsksTheReceiver(){
        render(state(true)).findViewById<View>(R.id.widget_refresh).performClick()
        val sent=Shadows.shadowOf(context).broadcastIntents.last()
        assertEquals(WidgetRefreshReceiver::class.java.name,sent.component?.className)
    }
    @Test fun appsToReapplyAreNamedAndOpenTheReapplyPage(){
        JournalStore(context).commit(entry("demo.chat","Chat",TargetState.NEEDS_REAPPLY_UPDATE))
        val s=state(true)
        assertEquals(1,s.reapply)
        val v=render(s)
        assertEquals("다시 적용할 앱 1개",v.text(R.id.widget_title))
        v.findViewById<View>(R.id.widget_root).performClick()
        val intent=checkNotNull(Shadows.shadowOf(context).nextStartedActivity)
        assertEquals("app.bluedotoff.REAPPLY",intent.action);assertFalse(intent.getBooleanExtra("check",false))
    }
    @Test fun appsToCheckYourselfOpenHomeWhereTheyAreListed(){
        JournalStore(context).commit(entry("demo.chat","Chat",TargetState.NEEDS_REVIEW))
        context.getSharedPreferences("ui-state",0).edit().putBoolean("autoReapply",false).commit()
        val s=state(true)
        assertEquals(0,s.reapply);assertEquals(1,s.check)
        assertEquals("직접 확인할 앱 1개",render(s).text(R.id.widget_title))
        render(s).findViewById<View>(R.id.widget_root).performClick()
        val intent=checkNotNull(Shadows.shadowOf(context).nextStartedActivity)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val a=controller.get()
            val get=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
            MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}.invoke(a,(get.invoke(a) as UiModel).copy(gate=Gate.READY,page=Page.SETTINGS))
            MainActivity::class.java.getDeclaredMethod("navigateNotification",Intent::class.java).apply{isAccessible=true}.invoke(a,intent)
            assertEquals(Page.HOME,(get.invoke(a) as UiModel).page)
        }finally{controller.destroy()}
    }
    @Test fun withoutTheWatchTheWidgetOpensHome(){
        render(state(false)).findViewById<View>(R.id.widget_root).performClick()
        assertEquals(Intent.ACTION_MAIN,checkNotNull(Shadows.shadowOf(context).nextStartedActivity).action)
    }
    @Test @Config(qualifiers="en-rUS") fun inEnglishTheWidgetSpeaksEnglish(){
        assertEquals("Watch off",render(state(false)).text(R.id.widget_title))
        assertEquals("2 apps kept hidden",render(state(true)).text(R.id.widget_title))
    }
    private fun layout(s:StatusWidget.State,widthDp:Int,heightDp:Int):View{
        val density=context.resources.displayMetrics.density
        val v=render(s,heightDp);val w=(widthDp*density).toInt();val h=(heightDp*density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        return v
    }
    /** Every shown text keeps one whole line inside the widget, from the smallest allowed size up, at normal, large and
     *  200% font sizes, and the watch state is always on screen (GPT review rc3). */
    @Test fun nothingIsSqueezedAtAnySize(){
        for(scale in listOf(1f,1.3f,2f)){
            RuntimeEnvironment.setFontScale(scale)
            for(watching in listOf(true,false))for(reapply in listOf(0,3))for(width in listOf(180,250,330))for(height in listOf(56,72,110)){
                val s=state(watching).copy(reapply=reapply)
                val root=layout(s,width,height)
                val where="scale=$scale watching=$watching reapply=$reapply size=${width}x$height"
                fun check(v:View){
                    if(v.visibility!=View.VISIBLE)return
                    if(v is TextView){
                        var top=0;var p:View?=v;while(p!=null&&p!==root){top+=p.top;p=p.parent as? View}
                        assertTrue("$where: ${v.height}px < line ${v.lineHeight}px",v.height>=v.lineHeight)
                        assertTrue("$where: ${top}..${top+v.height}px outside ${root.height}px",top>=0&&top+v.height<=root.height)
                        assertTrue("$where: width ${v.width}",v.width>0)
                    }
                    if(v is ViewGroup)for(i in 0 until v.childCount)check(v.getChildAt(i))
                }
                check(root)
                val title=checkNotNull(root.text(R.id.widget_title))
                val status=root.text(R.id.widget_status)
                // The watch state is on screen: in the title, in the status line, or implied by apps to reapply (only while watching).
                assertTrue("$where: $title / $status",title.startsWith("감시")||status?.startsWith("감시")==true||reapply>0&&watching||!watching&&status!=null)
            }
        }
    }
    /** Owner review pictures (a 4x1 widget is about 330 x 72 dp). */
    private fun picture(name:String,s:StatusWidget.State){
        val v=layout(s,330,72)
        val bmp=android.graphics.Bitmap.createBitmap(v.width,v.height,android.graphics.Bitmap.Config.ARGB_8888);v.draw(android.graphics.Canvas(bmp))
        val dir=java.io.File(checkNotNull(System.getProperty("screenshotDir")));dir.mkdirs()
        java.io.File(dir,"$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun pictures(){
        picture("widget-watching",state(true))
        picture("widget-reapply",state(true).copy(reapply=3))
        picture("widget-off",state(false))
    }
    @Test fun printedTimesCarryTheDate(){
        // An absolute date cannot turn wrong at midnight the way "today" would.
        val text=StatusWidget.time(context,java.time.ZonedDateTime.of(2026,10,28,21,40,0,0,java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertTrue(text,text.contains("28")&&text.contains("40"))
    }
}
