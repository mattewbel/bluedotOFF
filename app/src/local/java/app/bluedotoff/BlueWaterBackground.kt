package app.bluedotoff

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/** A single draw per animation frame, with no timer, bitmap, or work on the ADB worker. */
@Composable
internal fun BlueWaterBackground(remaining:Float,color:Color,modifier:Modifier=Modifier,moving:Boolean=false) {
    val fill=remaining.coerceIn(0f,1f)
    // Two small mathematical waves, no media assets, physics engine or ADB work.
    val phase=if(moving&&fill>0f&&fill<1f){
        val motion=rememberInfiniteTransition(label="water")
        val value by motion.animateFloat(0f,(2*PI).toFloat(),infiniteRepeatable(tween(2400,easing=LinearEasing)),label="wave")
        value
    }else 0f
    Canvas(modifier) {
        if(fill<=0f)return@Canvas
        if(fill>=1f){drawRect(color);return@Canvas}
        val level=size.height*(1f-fill)
        val amplitude=14f*density*sin(PI*fill).toFloat()
        fun wave(offset:Float):Path=Path().apply {
            for(i in 0..48){
                val fraction=i/48f
                val y=level+amplitude*(sin(2*PI*fraction+phase+offset).toFloat()+.28f*sin(4*PI*fraction-phase+offset).toFloat())
                if(i==0)moveTo(0f,y)else lineTo(size.width*fraction,y)
            }
            lineTo(size.width,size.height);lineTo(0f,size.height);close()
        }
        drawPath(wave(1.5f),color.copy(alpha=.22f))
        drawPath(wave(0f),color)
    }
}
