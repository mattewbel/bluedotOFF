package app.bluedotoff

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="ko-rKR")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideArtworkTest {
    @Test fun waterAndConnectionNoticeKeepReadableTextInBothThemes(){
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        for(dark in listOf(false,true)){
            val blue=BlueDotAppearance.scheme(context,dark,1f)
            val base=BlueDotAppearance.scheme(context,dark,0f)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(blue.onSurface.toArgb(),blue.surface.toArgb())>=4.5)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(base.onTertiaryContainer.toArgb(),base.tertiaryContainer.toArgb())>=4.5)
        }
    }
    @Test @Config(qualifiers="en-rUS") fun englishGuidesTranslateEveryUserFacingField(){
        val c=ApplicationProvider.getApplicationContext<android.content.Context>()
        val choices=GuideStep.entries.map{GuideSelection(it)}+GuideSelection(GuideStep.CODE_INPUT,true)
        choices.forEach{selection->val content=GuideContent.load(c,selection)
            assertFalse(Regex("[가-힣]").containsMatchIn((listOf(content.title,content.body,content.screen)+content.rows).joinToString()))
            val view=GuideArtwork(c,selection)
            assertTrue(view.contentDescription.toString().startsWith("Step "))
        }
    }
    private fun capture(name:String,dark:Boolean=false,width:Int=1080,font:Float=1f,manual:Boolean=false){
        val base=ApplicationProvider.getApplicationContext<android.content.Context>()
        val config=Configuration(base.resources.configuration).apply{uiMode=if(dark)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO;fontScale=font}
        val c=base.createConfigurationContext(config)
        val views=GuideStep.entries.map{GuideArtwork(c,GuideSelection(it,manual&&it==GuideStep.CODE_INPUT)).apply{measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));layout(0,0,width,measuredHeight)}}
        assertTrue(views.all{it.measuredHeight>0&&it.measuredHeight<=width})
        assertTrue(views.all{it.contentDescription.toString().contains("단계 예시")})
        val b=Bitmap.createBitmap(width,views.sumOf{it.height},Bitmap.Config.ARGB_8888);val canvas=Canvas(b)
        views.forEach{v->v.draw(canvas);canvas.translate(0f,v.height.toFloat())}
        File(System.getProperty("screenshotDir"),"72-guide-$name.png").apply{parentFile.mkdirs();outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        b.recycle()
    }
    @Test fun guideLight(){capture("light")}
    @Test fun guideDark(){capture("dark",dark=true)}
    @Test fun guideFoldWide(){capture("fold",width=1920)}
    @Test fun guideLargeFontManualFallback(){capture("large-manual",font=1.6f,manual=true)}
    @Test fun syntheticGuideContainsNoRealNetworkOrCodeData(){val c=ApplicationProvider.getApplicationContext<android.content.Context>();val s=c.assets.open("guide/steps.json").bufferedReader().use{it.readText()};assertFalse(Regex("\\b[0-9]{6}\\b|(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|RFCY").containsMatchIn(s))}
}
