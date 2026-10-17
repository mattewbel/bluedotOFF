package app.bluedotoff

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Shared presentation tokens for native connection screens and the Compose main screen. */
object BlueDotAppearance {
    const val HEADER_HEIGHT = 76
    const val PAGE_GUTTER = 24
    const val WIDE_GUTTER = 40
    const val SETUP_WIDTH = 640
    const val SECTION_GAP = 20
    const val BUTTON_HEIGHT = 56
    const val BUTTON_RADIUS = 18
    val dotBlue = Color(0xFF356AFF)

    fun dark(context: Context) = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun scheme(context: Context, dark: Boolean = dark(context), tint: Float = 1f): ColorScheme {
        val base = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
        val surface = lerp(base.surface, if (dark) Color(0xFF142B4B) else Color(0xFFDDE8FF), tint)
        val accent = lerp(base.primary, if (dark) Color(0xFFACC1FF) else dotBlue, tint)
        return base.copy(primary = accent, onPrimary = if (tint > 0.5f && !dark) Color.White else base.onPrimary,
            surface = surface, background = surface)
    }

    fun setupProgress(context: Context, step: Int, total: Int = 11) = UiText.text(context, "연결 준비 · $step / $total")
}
