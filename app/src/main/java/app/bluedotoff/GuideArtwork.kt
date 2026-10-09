package app.bluedotoff

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.View
import app.bluedotoff.core.*
import org.json.JSONObject
import androidx.compose.ui.graphics.toArgb
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

data class GuideContent(val title:String,val body:String,val screen:String,val rows:List<String>,val highlight:Int,val badge:String,val layout:String="settings") {
    companion object {
        fun load(c:Context,s:GuideSelection):GuideContent {
            val root=JSONObject(c.assets.open("guide/steps.json").bufferedReader().use{it.readText()})
            val j=root.getJSONObject(if(s.manual)"manual" else s.step.asset);val r=j.getJSONArray("rows")
            return GuideContent(UiText.text(c,j.getString("title")),UiText.text(c,j.getString("body")),UiText.text(c,j.getString("screen")),(0 until r.length()).map{UiText.text(c,r.getString(it))},j.getInt("highlight"),j.getString("badge"),j.optString("layout","settings"))
        }
    }
}
/** Privacy-safe examples show controls in the destination screen, never a step checklist. */
class GuideArtwork(context:Context,val selection:GuideSelection):View(context) {
    val content=GuideContent.load(context,selection)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val logicalHeight=when(content.layout){"permission"->if(selection.step==GuideStep.NEARBY)310f else 220f;"developer"->320f;"split"->320f;else->228f}
    init{contentDescription=UiText.text(context,"${selection.step.number}단계 예시. ${content.body}");importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    override fun onMeasure(w:Int,h:Int){val width=MeasureSpec.getSize(w).coerceAtLeast(1);val diagram=width.coerceAtMost((480*resources.displayMetrics.density).toInt());setMeasuredDimension(width,(diagram*logicalHeight/360f).toInt().coerceAtLeast(1))}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val scale=height/logicalHeight;canvas.save();canvas.translate((width-360*scale)/2f,0f);canvas.scale(scale,scale)
        val bg=if(dark)Color.rgb(31,34,41)else Color.rgb(243,245,249)
        val card=if(dark)Color.rgb(44,48,57)else Color.WHITE
        val fg=if(dark)Color.rgb(242,244,248)else Color.rgb(30,36,49)
        val accent=BlueDotAppearance.scheme(context,tint=0f).primary.toArgb()
        fun rect(left:Float,top:Float,right:Float,bottom:Float,color:Int,radius:Float=12f){paint.color=color;canvas.drawRoundRect(left,top,right,bottom,radius,radius,paint)}
        rect(0f,0f,360f,logicalHeight,bg,20f)
        fun label(s:String,x:Float,y:Float,size:Float,color:Int=fg){paint.color=color;paint.textSize=size;paint.typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);canvas.drawText(s,x,y,paint)}
        fun wrap(s:String,x:Float,y:Float,width:Int,size:Float=14f,color:Int=fg,center:Boolean=false,maxLines:Int=5){
            val tp=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{textSize=size;this.color=color;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)}
            val text=StaticLayout.Builder.obtain(s,0,s.length,tp,width).setAlignment(if(center)Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f,1f).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save();canvas.translate(x,y);text.draw(canvas);canvas.restore()
        }
        fun row(s:String,top:Float,selected:Boolean=false,badge:String="",height:Float=42f){
            rect(14f,top,346f,top+height,card)
            if(selected){paint.color=BlueDotAppearance.dotBlue.toArgb();paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;canvas.drawRoundRect(14f,top,346f,top+height,12f,12f,paint);paint.style=Paint.Style.FILL}
            wrap(s,26f,top+10f,if(badge.isBlank())306 else 258,14f,maxLines=2)
            if(badge.isNotBlank())label(badge,306f,top+27f,14f,accent)
        }
        when(content.layout){
            "permission"->{
                // Match the Samsung OS permission sheet: icon, question, Allow, Don't allow.
                if(selection.step==GuideStep.NEARBY)label("◇",169f,38f,26f,accent)
                else {
                    paint.color=accent;paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
                    val bell=android.graphics.Path().apply{moveTo(169f,35f);lineTo(172f,30f);lineTo(172f,25f);quadTo(172f,15f,180f,15f);quadTo(188f,15f,188f,25f);lineTo(188f,30f);lineTo(191f,35f);close()}
                    canvas.drawPath(bell,paint);canvas.drawArc(177f,34f,183f,40f,0f,180f,false,paint);paint.style=Paint.Style.FILL
                }
                wrap(content.screen,24f,53f,312,17f,center=true,maxLines=6)
                val allowTop=if(selection.step==GuideStep.NEARBY)194f else 116f
                rect(28f,allowTop,332f,allowTop+40f,card)
                paint.color=BlueDotAppearance.dotBlue.toArgb();paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;canvas.drawRoundRect(28f,allowTop,332f,allowTop+40f,12f,12f,paint);paint.style=Paint.Style.FILL
                wrap(content.rows[0],28f,allowTop+11f,304,16f,center=true,maxLines=1)
                wrap(content.rows[1],28f,allowTop+61f,304,16f,center=true,maxLines=1)
            }
            "developer"->{
                label("‹   ${content.screen}",20f,30f,16f)
                row(content.rows[0],46f,true,"›")
                label("↓",174f,117f,20f,accent)
                label("‹   ${content.rows[1]}",20f,146f,16f)
                row(content.rows[2],162f)
                row(content.rows[3],212f,true,"7×")
            }
            "notification"->{
                label(content.screen,20f,32f,17f)
                rect(14f,48f,346f,191f,card,18f)
                wrap(content.rows[0],26f,62f,306,14f,maxLines=2)
                row(content.rows[1],105f,true,"→",52f)
            }
            "split"->{
                label(content.screen,20f,30f,16f)
                rect(14f,46f,346f,152f,card)
                wrap(content.rows[0],26f,58f,306,14f,maxLines=2)
                label("••••••",135f,127f,18f,accent)
                rect(160f,162f,200f,166f,fg,2f)
                label("bluedotOFF",26f,194f,16f)
                row(content.rows[1],206f,true,"",34f)
                row(content.rows[2],250f,true,"",34f)
            }
            else->{
                label("‹   ${content.screen}",20f,32f,17f)
                content.rows.forEachIndexed{i,s->row(s,48f+i*46f,i==content.highlight,if(i==content.highlight)content.badge else "")}
            }
        }
        label(UiText.text(context,"이동한 화면 예시 · 강조된 곳을 누르세요"),20f,logicalHeight-12f,11f,if(dark)Color.LTGRAY else Color.DKGRAY)
        canvas.restore()
    }
}
