package tube.maniac.core.anilist

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tube.maniac.core.model.AniListSyncPolicy
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.Series
import tube.maniac.core.model.SyncFieldPolicy

class AniListRateLimiterAndPolicyTest {

    @Test
    fun `detects degraded 30 req per min mode and honors Retry-After on HTTP 429`() = runTest {
        var nowMs = 10_000L
        val recordedSleeps = mutableListOf<Long>()
        val limiter = AniListRateLimiter(
            clockMs = { nowMs },
            sleeper = { ms ->
                recordedSleeps.add(ms)
                nowMs += ms
            },
        )

        limiter.recordHeaders(
            limitHeader = "30",
            remainingHeader = "0",
            retryAfterSecondsHeader = "12",
            httpStatusCode = 429,
        )

        assertTrue(limiter.status.value.isDegradedMode)
        assertEquals(30, limiter.status.value.limit)
        assertEquals(0, limiter.status.value.remaining)

        limiter.acquirePermit()
        assertEquals(listOf(12_000L), recordedSleeps)
    }

    @Test
    fun `recovers cleanly to normal 90 req per min window on HTTP 200`() {
        val limiter = AniListRateLimiter(clockMs = { 50_000L }, sleeper = {})
        limiter.recordHeaders("90", "84", null, 200)

        assertFalse(limiter.status.value.isDegradedMode)
        assertEquals(84, limiter.status.value.remaining)
        assertEquals(0L, limiter.status.value.retryAfterEpochMs)
    }

    @Test
    fun `privacy isolation omits local-only fields and blocks incognito or local-only series`() {
        val series = Series(
            id = 1L,
            sourceId = 0L,
            url = "/manga/berserk",
            title = "Berserk",
            anilistId = 30002,
            readingStatus = ReadingStatus.CURRENT,
            userScore = 9.5f,
            privateNotes = "Secret note",
            startedAt = 1_700_000_000_000L,
            localOnly = false,
        )

        // Incognito blocks all network sync payloads
        assertNull(
            AniListSyncPayloadBuilder.buildSaveMediaListVariables(
                series = series,
                completedChapterCount = 120,
                customCategoryNames = listOf("Favorites"),
                policy = AniListSyncPolicy(),
                isIncognito = true,
            ),
        )

        // Local-Only series blocks all network sync payloads
        assertNull(
            AniListSyncPayloadBuilder.buildSaveMediaListVariables(
                series = series.copy(localOnly = true),
                completedChapterCount = 120,
                customCategoryNames = listOf("Favorites"),
                policy = AniListSyncPolicy(),
                isIncognito = false,
            ),
        )

        // Granular policy respects Local-Only toggles (notes & custom lists omitted by default)
        val payload = AniListSyncPayloadBuilder.buildSaveMediaListVariables(
            series = series,
            completedChapterCount = 120,
            customCategoryNames = listOf("Favorites"),
            policy = AniListSyncPolicy(
                chapterProgress = SyncFieldPolicy.SYNCED_TO_ANILIST,
                readingStatus = SyncFieldPolicy.SYNCED_TO_ANILIST,
                personalScore = SyncFieldPolicy.LOCAL_ONLY,
                customCategoriesAndTags = SyncFieldPolicy.LOCAL_ONLY,
                privateNotes = SyncFieldPolicy.LOCAL_ONLY,
            ),
            isIncognito = false,
        )!!

        assertTrue(payload.containsKey("progress"))
        assertTrue(payload.containsKey("status"))
        assertFalse(payload.containsKey("score"))
        assertFalse(payload.containsKey("notes"))
        assertFalse(payload.containsKey("customLists"))
        assertFalse(payload.containsKey("history"))
    }

    @Test
    fun `oauth callback parser extracts access token from fragment and supports custom client id`() {
        val token = AniListAuthHelper.extractAccessTokenFromCallback(
            "tubiac://anilist-callback#access_token=abc123xyz&token_type=Bearer&expires_in=31536000",
        )
        assertEquals("abc123xyz", token)
        assertEquals(
            "https://anilist.co/api/v2/oauth/authorize?client_id=21850&response_type=token",
            AniListAuthHelper.buildAuthorizeUrl(""),
        )
        assertEquals(
            "https://anilist.co/api/v2/oauth/authorize?client_id=99123&response_type=token",
            AniListAuthHelper.buildAuthorizeUrl(" 99123 "),
        )
    }
}
