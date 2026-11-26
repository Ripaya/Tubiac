package tube.maniac.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartReaderModeDetectorTest {

    private fun sampleSeries(
        format: SeriesFormat = SeriesFormat.UNKNOWN,
        genres: List<String> = emptyList(),
        override: ReaderMode? = null,
    ) = Series(
        id = 1L,
        sourceId = 100L,
        url = "/series/1",
        title = "Sample",
        format = format,
        genres = genres,
        readerModeOverride = override,
    )

    @Test
    fun `respects explicit per-series reader override above smart detection`() {
        val series = sampleSeries(
            format = SeriesFormat.MANHWA,
            override = ReaderMode.LTR,
        )
        val resolved = SmartReaderModeDetector.resolveMode(
            series = series,
            defaultMode = ReaderMode.RTL,
            smartAutoDetect = true,
            samplePageAspectRatios = listOf(4.2f, 3.8f),
        )
        assertEquals(ReaderMode.LTR, resolved)
    }

    @Test
    fun `detects webtoon mode for manhwa and tall aspect ratio strips`() {
        val manhwa = sampleSeries(format = SeriesFormat.MANHWA)
        assertEquals(
            ReaderMode.WEBTOON,
            SmartReaderModeDetector.resolveMode(manhwa, ReaderMode.RTL, smartAutoDetect = true),
        )

        val unknownWithTallPages = sampleSeries(format = SeriesFormat.UNKNOWN)
        assertEquals(
            ReaderMode.WEBTOON,
            SmartReaderModeDetector.resolveMode(
                series = unknownWithTallPages,
                defaultMode = ReaderMode.RTL,
                smartAutoDetect = true,
                samplePageAspectRatios = listOf(3.5f, 4.0f, 1.4f),
            ),
        )
    }

    @Test
    fun `defaults to double page spread for manga on landscape tablets`() {
        val manga = sampleSeries(format = SeriesFormat.MANGA)
        assertEquals(
            ReaderMode.DOUBLE_PAGE_SPREAD,
            SmartReaderModeDetector.resolveMode(
                series = manga,
                defaultMode = ReaderMode.LTR,
                smartAutoDetect = true,
                isLandscapeTablet = true,
            ),
        )
    }

    @Test
    fun `detects missing chapter gaps accurately`() {
        val chapters = listOf(
            Chapter(id = 1, seriesId = 1, url = "/1", name = "Ch 1", chapterNumber = 1f),
            Chapter(id = 2, seriesId = 1, url = "/2", name = "Ch 2", chapterNumber = 2f),
            Chapter(id = 3, seriesId = 1, url = "/5", name = "Ch 5", chapterNumber = 5f),
            Chapter(id = 4, seriesId = 1, url = "/5.5", name = "Ch 5.5", chapterNumber = 5.5f),
            Chapter(id = 5, seriesId = 1, url = "/7", name = "Ch 7", chapterNumber = 7f),
        )
        val gaps = SmartReaderModeDetector.detectMissingChapterGaps(chapters)
        assertEquals(mapOf(3L to 2, 5L to 1), gaps)
    }
}
