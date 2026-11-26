package tube.maniac.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.database.entity.toEntity
import tube.maniac.core.model.AppSettings
import tube.maniac.core.model.AppThemeMode
import tube.maniac.core.model.BackupPayload
import tube.maniac.core.model.BackupSeriesEntry
import tube.maniac.core.model.Category
import tube.maniac.core.model.Chapter
import tube.maniac.core.model.ExtensionRepo
import tube.maniac.core.model.HistoryEntry
import tube.maniac.core.model.SelectiveRestoreOptions
import tube.maniac.core.model.Series
import tube.maniac.core.model.SeriesFormat
import tube.maniac.core.model.Tag

class BackupAndDaoUnitTest {

    private fun samplePayload(): BackupPayload = BackupPayload(
        createdAtEpochMs = 1_760_000_000_000L,
        seriesEntries = listOf(
            BackupSeriesEntry(
                series = Series(
                    id = 10L,
                    sourceId = 99L,
                    url = "/manga/vagabond",
                    title = "Vagabond",
                    format = SeriesFormat.MANGA,
                    inLibrary = true,
                    anilistId = 30656,
                    anilistScore = 95,
                    genres = listOf("Action", "Drama"),
                ),
                chapters = listOf(
                    Chapter(
                        id = 101L,
                        seriesId = 10L,
                        url = "/ch/1",
                        name = "Ch. 1",
                        chapterNumber = 1f,
                        read = true,
                        lastPageRead = 24,
                        totalPages = 24,
                    ),
                ),
                categoryNames = listOf("Seinen"),
                tagNames = listOf("Masterpiece"),
            ),
        ),
        categories = listOf(Category(id = 1L, name = "Seinen", isDefault = true)),
        tags = listOf(Tag(id = 1L, name = "Masterpiece")),
        history = listOf(
            HistoryEntry(
                id = 1L,
                seriesId = 10L,
                chapterId = 101L,
                seriesTitle = "Vagabond",
                chapterName = "Ch. 1",
                chapterNumber = 1f,
                sourceName = "Local Source",
                lastReadAt = 1_760_000_000_000L,
                lastPageRead = 24,
                totalPages = 24,
            ),
        ),
        repos = listOf(
            ExtensionRepo(
                id = 1L,
                name = "Community Repo",
                indexUrl = "https://example.org/extensions/index.min.json",
                trusted = true,
            ),
        ),
        settings = AppSettings(themeMode = AppThemeMode.AMOLED_BLACK, concurrentPageDownloads = 6),
    )

    @Test
    fun `tachibk gzip round trip preserves 100 percent of payload`() {
        val original = samplePayload()
        val encoded = BackupCodec.encodeToBytes(original, BackupFormat.TACHIBK_GZIP)
        val restored = BackupCodec.decodeFromBytes(encoded, BackupFormat.TACHIBK_GZIP)

        assertEquals(original, restored)
    }

    @Test
    fun `json zip round trip preserves 100 percent of payload`() {
        val original = samplePayload()
        val encoded = BackupCodec.encodeToBytes(original, BackupFormat.JSON_ZIP)
        val restored = BackupCodec.decodeFromBytes(encoded, BackupFormat.JSON_ZIP)

        assertEquals(original, restored)
    }

    @Test
    fun `selective restore strips unchecked modules cleanly`() {
        val payload = samplePayload()
        val filtered = BackupCodec.filterForSelectiveRestore(
            payload = payload,
            options = SelectiveRestoreOptions(
                restoreLibraryAndChapters = true,
                restoreCategoriesAndTags = false,
                restoreHistory = false,
                restoreAniListMappings = false,
                restoreExtensionRepos = false,
                restoreSettings = false,
            ),
        )

        assertEquals(1, filtered.seriesEntries.size)
        assertNull(filtered.seriesEntries.first().series.anilistId)
        assertTrue(filtered.categories.isEmpty())
        assertTrue(filtered.tags.isEmpty())
        assertTrue(filtered.history.isEmpty())
        assertTrue(filtered.repos.isEmpty())
        assertNull(filtered.settings)
    }

    @Test
    fun `room entity mappers and safe FTS query builder preserve fidelity`() {
        val series = samplePayload().seriesEntries.first().series
        val roundTripped = series.toEntity().toDomain()
        assertEquals(series.title, roundTripped.title)
        assertEquals(series.anilistId, roundTripped.anilistId)
        assertEquals(series.genres, roundTripped.genres)

        assertEquals("solo* leveling*", TubiacDatabase.buildSafeFtsQuery("  solo: (leveling)! "))
    }
}
