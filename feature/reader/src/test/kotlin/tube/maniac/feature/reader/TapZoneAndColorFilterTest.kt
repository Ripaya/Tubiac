package tube.maniac.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tube.maniac.core.model.Chapter
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

    @Test
    fun `chapter sorting resolves next and previous chapters deterministically`() {
        val raw = listOf(
            Chapter(id = 1L, seriesId = 10L, url = "/1", name = "Ch 1", chapterNumber = 1f, sourceOrder = 2),
            Chapter(id = 3L, seriesId = 10L, url = "/3", name = "Ch 3", chapterNumber = 3f, sourceOrder = 0),
            Chapter(id = 2L, seriesId = 10L, url = "/2", name = "Ch 2", chapterNumber = 2f, sourceOrder = 1),
        )
        val sorted = ReaderChapterOrder.sortDescending(raw)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })

        val (prevFrom2, nextFrom2) = ReaderChapterOrder.adjacentChapters(sorted, 2L)
        assertEquals(1L, prevFrom2?.id)
        assertEquals(3L, nextFrom2?.id)
    }
}
