package tube.maniac.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Editorial Signature Palette: "Washi Paper & Sumi Ink"
 * Muted, mature, non-glare matte tonal hierarchy with vermilion serialization accents.
 */
object TubiacColors {
    val LightSurface = Color(0xFFF5F2EC)
    val LightSurfaceContainerLow = Color(0xFFEEEAE2)
    val LightSurfaceContainer = Color(0xFFE6E1D7)
    val LightSurfaceContainerHigh = Color(0xFFDED8CC)
    val LightOutlineVariant = Color(0xFFD2CBC0)

    val DarkSurface = Color(0xFF111215)
    val DarkSurfaceContainerLow = Color(0xFF17191D)
    val DarkSurfaceContainer = Color(0xFF1D2026)
    val DarkSurfaceContainerHigh = Color(0xFF252932)
    val DarkOutlineVariant = Color(0xFF2E323C)

    val AmoledPureBlack = Color(0xFF000000)
    val AmoledContainerLow = Color(0xFF090A0D)
    val AmoledContainer = Color(0xFF101216)
    val AmoledContainerHigh = Color(0xFF181B21)
    val AmoledOutlineVariant = Color(0xFF242730)

    val PrimaryVermilion = Color(0xFFC84336)
    val PrimaryVermilionLight = Color(0xFFE88D82)
    val PrimaryContainerLight = Color(0xFFF6DDD9)
    val PrimaryContainerDark = Color(0xFF5A1D18)

    val SecondarySlateInk = Color(0xFF3C4B64)
    val SecondarySlateLight = Color(0xFFB3C3DF)
    val SecondaryContainerLight = Color(0xFFDCE3EF)
    val SecondaryContainerDark = Color(0xFF283347)

    val TertiaryAmber = Color(0xFFB87D2B)
    val TertiaryAmberLight = Color(0xFFE6BD78)
    val TertiaryContainerLight = Color(0xFFF6E7CE)
    val TertiaryContainerDark = Color(0xFF473314)
}

val TubiacLightColorScheme = lightColorScheme(
    primary = TubiacColors.PrimaryVermilion,
    onPrimary = Color.White,
    primaryContainer = TubiacColors.PrimaryContainerLight,
    onPrimaryContainer = Color(0xFF3A0D09),
    secondary = TubiacColors.SecondarySlateInk,
    onSecondary = Color.White,
    secondaryContainer = TubiacColors.SecondaryContainerLight,
    onSecondaryContainer = Color(0xFF121C2D),
    tertiary = TubiacColors.TertiaryAmber,
    onTertiary = Color(0xFF261804),
    tertiaryContainer = TubiacColors.TertiaryContainerLight,
    onTertiaryContainer = Color(0xFF2B1C06),
    background = TubiacColors.LightSurface,
    onBackground = Color(0xFF18191C),
    surface = TubiacColors.LightSurface,
    onSurface = Color(0xFF18191C),
    surfaceVariant = TubiacColors.LightSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFF53504B),
    surfaceContainerLowest = Color(0xFFFAF8F4),
    surfaceContainerLow = TubiacColors.LightSurfaceContainerLow,
    surfaceContainer = TubiacColors.LightSurfaceContainer,
    surfaceContainerHigh = TubiacColors.LightSurfaceContainerHigh,
    outline = Color(0xFF7D7870),
    outlineVariant = TubiacColors.LightOutlineVariant,
)

val TubiacDarkColorScheme = darkColorScheme(
    primary = TubiacColors.PrimaryVermilionLight,
    onPrimary = Color(0xFF4A100B),
    primaryContainer = TubiacColors.PrimaryContainerDark,
    onPrimaryContainer = Color(0xFFF8DFDC),
    secondary = TubiacColors.SecondarySlateLight,
    onSecondary = Color(0xFF1B273A),
    secondaryContainer = TubiacColors.SecondaryContainerDark,
    onSecondaryContainer = Color(0xFFDCE3EF),
    tertiary = TubiacColors.TertiaryAmberLight,
    onTertiary = Color(0xFF362409),
    tertiaryContainer = TubiacColors.TertiaryContainerDark,
    onTertiaryContainer = Color(0xFFF6E7CE),
    background = TubiacColors.DarkSurface,
    onBackground = Color(0xFFE8E4DC),
    surface = TubiacColors.DarkSurface,
    onSurface = Color(0xFFE8E4DC),
    surfaceVariant = TubiacColors.DarkSurfaceContainerHigh,
    onSurfaceVariant = Color(0xFFA7A5A0),
    surfaceContainerLowest = Color(0xFF0C0D10),
    surfaceContainerLow = TubiacColors.DarkSurfaceContainerLow,
    surfaceContainer = TubiacColors.DarkSurfaceContainer,
    surfaceContainerHigh = TubiacColors.DarkSurfaceContainerHigh,
    outline = Color(0xFF6B6F78),
    outlineVariant = TubiacColors.DarkOutlineVariant,
)

fun ColorScheme.toAmoledPureBlack(): ColorScheme = copy(
    background = TubiacColors.AmoledPureBlack,
    surface = TubiacColors.AmoledPureBlack,
    surfaceContainerLowest = TubiacColors.AmoledPureBlack,
    surfaceContainerLow = TubiacColors.AmoledContainerLow,
    surfaceContainer = TubiacColors.AmoledContainer,
    surfaceContainerHigh = TubiacColors.AmoledContainerHigh,
    outlineVariant = TubiacColors.AmoledOutlineVariant,
)

/**
 * Generates a subtle matte cover-derived tonal accent variant for Series Detail screens.
 */
fun ColorScheme.withCoverAccent(seed: Color?, isDark: Boolean): ColorScheme {
    if (seed == null) return this
    return if (isDark) {
        copy(
            primary = lerp(seed, Color(0xFFE8E4DC), 0.42f),
            primaryContainer = lerp(seed, surfaceContainer, 0.68f),
            onPrimaryContainer = Color(0xFFF5F2EC),
            secondaryContainer = lerp(seed, surfaceContainerHigh, 0.78f),
        )
    } else {
        copy(
            primary = lerp(seed, Color(0xFF18191C), 0.28f),
            primaryContainer = lerp(seed, Color(0xFFF5F2EC), 0.80f),
            onPrimaryContainer = lerp(seed, Color(0xFF18191C), 0.75f),
            secondaryContainer = lerp(seed, surfaceContainerLow, 0.86f),
        )
    }
}
