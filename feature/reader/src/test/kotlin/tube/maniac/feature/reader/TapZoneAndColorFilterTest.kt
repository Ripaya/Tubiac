package tube.maniac.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tube.maniac.core.model.ColorFilterConfig
import tube.maniac.core.model.TapZonePreset

class TapZoneAndColorFilterTest {

    @Test
    fun `tap zone flips next and previous in RTL mode`() {
        assertEquals(
            ReaderTapAction.NEXT,
            TapZoneResolver.resolve(0.85f, 0.5f, TapZonePreset.LEFT_RIGHT_CENTER, isRtl = false),
        )
        assertEquals(
            ReaderTapAction.PREVIOUS,
            TapZoneResolver.resolve(0.85f, 0.5f, TapZonePreset.LEFT_RIGHT_CENTER, isRtl = true),
        )
        assertEquals(
            ReaderTapAction.TOGGLE_MENU,
            TapZoneResolver.resolve(0.5f, 0.5f, TapZonePreset.L_SHAPED, isRtl = true),
        )
    }

    @Test
    fun `disabled tap zone returns none`() {
        assertEquals(
            ReaderTapAction.NONE,
            TapZoneResolver.resolve(0.5f, 0.5f, TapZonePreset.DISABLED, isRtl = false),
        )
    }

    @Test
    fun `color filter returns null when disabled and matrix when enabled`() {
        assertNull(ReaderColorFilterBuilder.buildColorMatrix(ColorFilterConfig(enabled = false)))
        assertNotNull(
            ReaderColorFilterBuilder.buildColorMatrix(
                ColorFilterConfig(enabled = true, eInkGrayscale = true, warmTintStrength = 0.4f),
            ),
        )
    }
}
