package tube.maniac.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import tube.maniac.core.model.AnimationSpeed
import tube.maniac.core.model.AppThemeMode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TubiacTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    useDynamicColor: Boolean = true,
    animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,
    coverSeedColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED_BLACK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val baseScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> TubiacDarkColorScheme
        else -> TubiacLightColorScheme
    }

    val themedScheme = baseScheme
        .let { if (themeMode == AppThemeMode.AMOLED_BLACK) it.toAmoledPureBlack() else it }
        .withCoverAccent(coverSeedColor, isDark)

    val motionScheme = when (animationSpeed) {
        AnimationSpeed.REDUCED -> MotionScheme.standard()
        AnimationSpeed.NORMAL, AnimationSpeed.FAST -> MotionScheme.expressive()
    }

    MaterialExpressiveTheme(
        colorScheme = themedScheme,
        motionScheme = motionScheme,
        shapes = TubiacShapes,
        typography = TubiacTypography,
        content = content,
    )
}
