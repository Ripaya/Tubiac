package tube.maniac.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Fallback Signature Palette: "Tubiac Ink & Vermilion"
 * Active on Android 11 or when Dynamic Color is disabled.
 */
object TubiacColors {
    val LightSurface = Color(0xFFFAF8F5)
    val LightSurfaceContainerLow = Color(0xFFF3EFEA)
    val LightSurfaceContainer = Color(0xFFECE7E1)
    val LightSurfaceContainerHigh = Color(0xFFE5DFD7)

    val DarkSurface = Color(0xFF111216)
    val DarkSurfaceContainerLow = Color(0xFF17191F)
    val DarkSurfaceContainer = Color(0xFF1D2027)
    val DarkSurfaceContainerHigh = Color(0xFF262A33)

    val AmoledPureBlack = Color(0xFF000000)
    val AmoledContainerLow = Color(0xFF0A0B0E)
    val AmoledContainer = Color(0xFF111318)

    val PrimaryVermilion = Color(0xFFD9483B)
    val PrimaryVermilionLight = Color(0xFFFFB4AA)
    val PrimaryContainerLight = Color(0xFFFFDAD5)
    val PrimaryContainerDark = Color(0xFF7D1F17)

    val SecondarySlateInk = Color(0xFF4E5D78)
    val SecondarySlateLight = Color(0xFFB6C6E4)
    val SecondaryContainerLight = Color(0xFFD9E3F8)
    val SecondaryContainerDark = Color(0xFF35445E)

    val TertiaryAmber = Color(0xFFE09F3E)
    val TertiaryAmberLight = Color(0xFFF7C97E)
    val TertiaryContainerLight = Color(0xFFFFE5BE)
    val TertiaryContainerDark = Color(0xFF5D3F0F)
}

val TubiacLightColorScheme = lightColorScheme(
    primary = TubiacColors.PrimaryVermilion,
    onPrimary = Color.White,
    primaryContainer = TubiacColors.PrimaryContainerLight,
    onPrimaryContainer = Color(0xFF410804),
    secondary = TubiacColors.SecondarySlateInk,
    onSecondary = Color.White,
    secondaryContainer = TubiacColors.SecondaryContainerLight,
    onSecondaryContainer = Color(0xFF101C31),
    tertiary = TubiacColors.TertiaryAmber,
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = TubiacColors.TertiaryContainerLight,
    onTertiaryContainer = Color(0xFF2D1C00),
    background = TubiacColors.LightSurface,
    onBackground = Color(0xFF1B1B1F),
    surface = TubiacColors.LightSurface,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = TubiacColors.LightSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFF48464B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = TubiacColors.LightSurfaceContainerLow,
    surfaceContainer = TubiacColors.LightSurfaceContainer,
    surfaceContainerHigh = TubiacColors.LightSurfaceContainerHigh,
    outline = Color(0xFF79757B),
    outlineVariant = Color(0xFFCAC4CC),
)

val TubiacDarkColorScheme = darkColorScheme(
    primary = TubiacColors.PrimaryVermilionLight,
    onPrimary = Color(0xFF630E08),
    primaryContainer = TubiacColors.PrimaryContainerDark,
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = TubiacColors.SecondarySlateLight,
    onSecondary = Color(0xFF1F2E46),
    secondaryContainer = TubiacColors.SecondaryContainerDark,
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = TubiacColors.TertiaryAmberLight,
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = TubiacColors.TertiaryContainerDark,
    onTertiaryContainer = Color(0xFFFFE5BE),
    background = TubiacColors.DarkSurface,
    onBackground = Color(0xFFE6E2DD),
    surface = TubiacColors.DarkSurface,
    onSurface = Color(0xFFE6E2DD),
    surfaceVariant = TubiacColors.DarkSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFFC8C5CC),
    surfaceContainerLowest = Color(0xFF0C0D10),
    surfaceContainerLow = TubiacColors.DarkSurfaceContainerLow,
    surfaceContainer = TubiacColors.DarkSurfaceContainer,
    surfaceContainerHigh = TubiacColors.DarkSurfaceContainerHigh,
    outline = Color(0xFF918E96),
    outlineVariant = Color(0xFF3E3D45),
)

fun ColorScheme.toAmoledPureBlack(): ColorScheme = copy(
    background = TubiacColors.AmoledPureBlack,
    surface = TubiacColors.AmoledPureBlack,
    surfaceContainerLowest = TubiacColors.AmoledPureBlack,
    surfaceContainerLow = TubiacColors.AmoledContainerLow,
    surfaceContainer = TubiacColors.AmoledContainer,
)

/**
 * Generates a subtle cover-derived tonal accent variant for Series Detail screens.
 */
fun ColorScheme.withCoverAccent(seed: Color?, isDark: Boolean): ColorScheme {
    if (seed == null) return this
    return if (isDark) {
        copy(
            primary = lerp(seed, Color.White, 0.35f),
            primaryContainer = lerp(seed, Color.Black, 0.55f),
            onPrimaryContainer = Color.White,
            secondaryContainer = lerp(seed, surfaceContainerHigh, 0.72f),
        )
    } else {
        copy(
            primary = lerp(seed, Color.Black, 0.15f),
            primaryContainer = lerp(seed, Color.White, 0.78f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.72f),
            secondaryContainer = lerp(seed, surfaceContainerLow, 0.85f),
        )
    }
}
