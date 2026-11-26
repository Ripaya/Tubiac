package tube.maniac.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderMode {
    WEBTOON,
    CONTINUOUS_VERTICAL,
    LTR,
    RTL,
    DOUBLE_PAGE_SPREAD,
}

@Serializable
enum class ReaderBackground {
    SMART_EDGE,
    BLACK,
    DARK_GRAY,
    WHITE,
    DYNAMIC,
}

@Serializable
enum class ScaleMode {
    FIT_SCREEN,
    FIT_WIDTH,
    FIT_HEIGHT,
    ORIGINAL,
    SMART_FIT,
}

@Serializable
enum class TapZonePreset {
    L_SHAPED,
    KINDLISH,
    EDGE,
    LEFT_RIGHT_CENTER,
    DISABLED,
}

@Serializable
data class ColorFilterConfig(
    val enabled: Boolean = false,
    val brightness: Float = 0f, // -1.0..1.0
    val contrast: Float = 1f, // 0.5..2.0
    val eInkGrayscale: Boolean = false,
    val invertColors: Boolean = false,
    val warmTintStrength: Float = 0f, // 0.0..1.0
)

@Serializable
data class ReaderPreferences(
    val defaultMode: ReaderMode = ReaderMode.RTL,
    val smartAutoDetect: Boolean = true,
    val background: ReaderBackground = ReaderBackground.SMART_EDGE,
    val scaleMode: ScaleMode = ScaleMode.FIT_WIDTH,
    val tapZonePreset: TapZonePreset = TapZonePreset.L_SHAPED,
    val webtoonSidePaddingPercent: Int = 0,
    val autoCropBorders: Boolean = false,
    val volumeKeyNavigation: Boolean = true,
    val showStatusOverlay: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoMarkReadThresholdPercent: Int = 85,
    val doublePageAutoSplit: Boolean = true,
    val doublePageAutoCombine: Boolean = true,
    val colorFilter: ColorFilterConfig = ColorFilterConfig(),
)

/**
 * Pure deterministic detector choosing optimal reader layout from series format,
 * tags, and page aspect ratios while honoring per-series user overrides.
 */
object SmartReaderModeDetector {

    private const val WEBTOON_ASPECT_RATIO_THRESHOLD = 2.1f

    fun resolveMode(
        series: Series,
        defaultMode: ReaderMode,
        smartAutoDetect: Boolean,
        samplePageAspectRatios: List<Float> = emptyList(),
        isLandscapeTablet: Boolean = false,
    ): ReaderMode {
        series.readerModeOverride?.let { return it }
        if (!smartAutoDetect) return defaultMode

        val hasWebtoonTag = series.genres.any { genre ->
            val normalized = genre.lowercase()
            normalized.contains("webtoon") || normalized.contains("long strip") || normalized.contains("manhwa")
        }

        val hasTallStripPages = samplePageAspectRatios.isNotEmpty() &&
            samplePageAspectRatios.count { it >= WEBTOON_ASPECT_RATIO_THRESHOLD } >=
            (samplePageAspectRatios.size + 1) / 2

        return when {
            series.format == SeriesFormat.WEBTOON ||
                series.format == SeriesFormat.MANHWA ||
                series.format == SeriesFormat.MANHUA ||
                hasWebtoonTag ||
                hasTallStripPages -> ReaderMode.WEBTOON

            isLandscapeTablet && (series.format == SeriesFormat.MANGA || series.format == SeriesFormat.COMIC) ->
                ReaderMode.DOUBLE_PAGE_SPREAD

            series.format == SeriesFormat.MANGA -> ReaderMode.RTL
            series.format == SeriesFormat.COMIC -> ReaderMode.LTR
            else -> defaultMode
        }
    }

    /**
     * Detects missing chapter numbers (e.g. Ch. 10 -> Ch. 13 reports 2 missing chapters).
     */
    fun detectMissingChapterGaps(chapters: List<Chapter>): Map<Long, Int> {
        val numbered = chapters
            .filter { it.chapterNumber > 0f }
            .sortedBy { it.chapterNumber }

        val gaps = mutableMapOf<Long, Int>()
        for (index in 1 until numbered.size) {
            val prevWhole = numbered[index - 1].chapterNumber.toInt()
            val currWhole = numbered[index].chapterNumber.toInt()
            val missing = currWhole - prevWhole - 1
            if (missing > 0) {
                gaps[numbered[index].id] = missing
            }
        }
        return gaps
    }
}
