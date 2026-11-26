package tube.maniac.feature.reader

import androidx.compose.ui.graphics.ColorMatrix
import tube.maniac.core.model.ColorFilterConfig
import tube.maniac.core.model.TapZonePreset

enum class ReaderTapAction {
    PREVIOUS,
    NEXT,
    TOGGLE_MENU,
    NONE,
}

/**
 * Pure tap-zone resolver mapping normalized screen coordinates (0f..1f)
 * into reader navigation or menu toggle actions.
 */
object TapZoneResolver {
    fun resolve(
        normalizedX: Float,
        normalizedY: Float,
        preset: TapZonePreset,
        isRtl: Boolean,
    ): ReaderTapAction {
        if (preset == TapZonePreset.DISABLED) return ReaderTapAction.NONE

        val rawAction = when (preset) {
            TapZonePreset.L_SHAPED -> when {
                normalizedY < 0.25f -> ReaderTapAction.PREVIOUS
                normalizedX < 0.30f -> ReaderTapAction.PREVIOUS
                normalizedX > 0.70f -> ReaderTapAction.NEXT
                normalizedY > 0.75f -> ReaderTapAction.NEXT
                else -> ReaderTapAction.TOGGLE_MENU
            }
            TapZonePreset.KINDLISH -> when {
                normalizedX < 0.25f -> ReaderTapAction.PREVIOUS
                normalizedY < 0.20f -> ReaderTapAction.TOGGLE_MENU
                else -> ReaderTapAction.NEXT
            }
            TapZonePreset.EDGE -> when {
                normalizedX < 0.22f -> ReaderTapAction.PREVIOUS
                normalizedX > 0.78f -> ReaderTapAction.NEXT
                else -> ReaderTapAction.TOGGLE_MENU
            }
            TapZonePreset.LEFT_RIGHT_CENTER -> when {
                normalizedX < 0.33f -> ReaderTapAction.PREVIOUS
                normalizedX > 0.67f -> ReaderTapAction.NEXT
                else -> ReaderTapAction.TOGGLE_MENU
            }
            TapZonePreset.DISABLED -> ReaderTapAction.NONE
        }

        if (!isRtl) return rawAction
        return when (rawAction) {
            ReaderTapAction.PREVIOUS -> ReaderTapAction.NEXT
            ReaderTapAction.NEXT -> ReaderTapAction.PREVIOUS
            else -> rawAction
        }
    }
}

object ReaderColorFilterBuilder {
    fun buildColorMatrix(config: ColorFilterConfig): ColorMatrix? {
        if (!config.enabled) return null

        val result = ColorMatrix()

        if (config.eInkGrayscale) {
            val gray = ColorMatrix().apply { setToSaturation(0f) }
            result.timesAssign(gray)
        }

        val c = if (config.eInkGrayscale) (config.contrast * 1.25f).coerceIn(0.5f, 2.5f) else config.contrast
        val b = config.brightness * 128f
        val translate = (-0.5f * c + 0.5f) * 255f + b
        val contrastBrightness = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        result.timesAssign(contrastBrightness)

        if (config.warmTintStrength > 0.01f) {
            val w = config.warmTintStrength.coerceIn(0f, 1f)
            val warm = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 12f * w,
                    0f, 1f - (0.06f * w), 0f, 0f, 4f * w,
                    0f, 0f, 1f - (0.22f * w), 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            result.timesAssign(warm)
        }

        if (config.invertColors) {
            val invert = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            result.timesAssign(invert)
        }

        return result
    }
}
