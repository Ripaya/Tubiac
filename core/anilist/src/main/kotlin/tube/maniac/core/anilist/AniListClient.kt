package tube.maniac.core.anilist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.PendingMutationDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.entity.PendingAniListMutationEntity
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.database.entity.toEntity
import tube.maniac.core.model.AniListMedia
import tube.maniac.core.model.AniListRecommendation
import tube.maniac.core.model.AniListRelation
import tube.maniac.core.model.AniListSyncPolicy
import tube.maniac.core.model.AniListUser
import tube.maniac.core.model.ConflictResolution
import tube.maniac.core.model.PublicationStatus
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.Series
import tube.maniac.core.model.SeriesFormat
import tube.maniac.core.model.SyncFieldPolicy
import java.io.IOException
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

object AniListAuthHelper {
    const val OAUTH_CALLBACK_URI = "tubiac://anilist-callback"
    private const val AUTHORIZE_ENDPOINT = "https://anilist.co/api/v2/oauth/authorize"

    fun buildAuthorizeUrl(clientId: String): String =
        "$AUTHORIZE_ENDPOINT?client_id=$clientId&response_type=token"

    fun extractAccessTokenFromCallback(callbackUri: String): String? {
        val fragment = callbackUri.substringAfter("#", "")
        if (fragment.isBlank()) return null
        return fragment.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            .firstOrNull { it.first == "access_token" }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }
}

/**
 * Builds filtered SaveMediaListEntry mutation variables strictly honoring
 * the user's Granular Local-Only vs. Synced to AniList Policy Matrix.
 * Reading history is NEVER included in any AniList payload.
 */
object AniListSyncPayloadBuilder {
    fun buildSaveMediaListVariables(
        series: Series,
        completedChapterCount: Int,
        customCategoryNames: List<String>,
        policy: AniListSyncPolicy,
        isIncognito: Boolean,
    ): JsonObject? {
        val mediaId = series.anilistId ?: return null
        if (isIncognito || series.localOnly) return null

        return buildJsonObject {
            put("mediaId", mediaId)
            if (policy.readingStatus == SyncFieldPolicy.SYNCED_TO_ANILIST) {
                put("status", series.readingStatus.name)
            }
            if (policy.chapterProgress == SyncFieldPolicy.SYNCED_TO_ANILIST) {
                put("progress", completedChapterCount.coerceAtLeast(0))
            }
            if (policy.personalScore == SyncFieldPolicy.SYNCED_TO_ANILIST && series.userScore != null) {
                put("score", series.userScore)
            }
            if (policy.startAndFinishDates == SyncFieldPolicy.SYNCED_TO_ANILIST) {
                series.startedAt?.let { put("startedAt", epochToFuzzyDate(it)) }
                series.completedAt?.let { put("completedAt", epochToFuzzyDate(it)) }
            }
            if (policy.privateNotes == SyncFieldPolicy.SYNCED_TO_ANILIST && !series.privateNotes.isNullOrBlank()) {
                put("notes", series.privateNotes)
            }
            if (policy.customCategoriesAndTags == SyncFieldPolicy.SYNCED_TO_ANILIST && customCategoryNames.isNotEmpty()) {
                put("customLists", JsonArray(customCategoryNames.map { JsonPrimitive(it) }))
            }
        }
    }

    private fun epochToFuzzyDate(epochMs: Long): JsonObject {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return buildJsonObject {
            put("year", cal.get(Calendar.YEAR))
            put("month", cal.get(Calendar.MONTH) + 1)
            put("day", cal.get(Calendar.DAY_OF_MONTH))
        }
    }
}

@Singleton
class AniListGraphQlClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    val rateLimiter: AniListRateLimiter,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchViewer(token: String): Result<AniListUser> = runCatching {
        val data = executeGraphQl(VIEWER_QUERY, buildJsonObject {}, token)
        val viewer = data["Viewer"]?.jsonObject ?: error("Missing Viewer payload")
        val stats = viewer["statistics"]?.jsonObject?.get("manga")?.jsonObject
        AniListUser(
            id = viewer["id"]?.jsonPrimitive?.intOrNull ?: 0,
            name = viewer["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            avatarUrl = viewer["avatar"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull,
            bannerUrl = viewer["bannerImage"]?.jsonPrimitive?.contentOrNull,
            chaptersRead = stats?.get("chaptersRead")?.jsonPrimitive?.intOrNull ?: 0,
            mangaCount = stats?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    suspend fun fetchPopularManga(page: Int = 1, perPage: Int = 20): Result<List<AniListMedia>> = runCatching {
        val vars = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
        }
        val data = executeGraphQl(POPULAR_MANGA_QUERY, vars, token = null)
        val mediaList = data["Page"]?.jsonObject?.get("media")?.jsonArray.orEmpty()
        mediaList.map { parseMedia(it.jsonObject) }
    }

    suspend fun searchManga(query: String, perPage: Int = 12): Result<List<AniListMedia>> = runCatching {
        val vars = buildJsonObject {
            put("search", query)
            put("perPage", perPage)
        }
        val data = executeGraphQl(SEARCH_MANGA_QUERY, vars, token = null)
        val mediaList = data["Page"]?.jsonObject?.get("media")?.jsonArray.orEmpty()
        mediaList.map { parseMedia(it.jsonObject) }
    }

    suspend fun fetchMediaDetail(mediaId: Int): Result<AniListMedia> = runCatching {
        val vars = buildJsonObject { put("id", mediaId) }
        val data = executeGraphQl(MEDIA_DETAIL_QUERY, vars, token = null)
        parseMedia(requireNotNull(data["Media"]?.jsonObject) { "Media not found" })
    }

    suspend fun saveMediaListEntry(variables: JsonObject, token: String): Result<Unit> = runCatching {
        executeGraphQl(SAVE_MEDIA_LIST_MUTATION, variables, token)
    }

    private suspend fun executeGraphQl(
        query: String,
        variables: JsonObject,
        token: String?,
        attempt: Int = 1,
    ): JsonObject = withContext(Dispatchers.IO) {
        rateLimiter.acquirePermit()

        val bodyJson = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val requestBuilder = Request.Builder()
            .url(ANILIST_ENDPOINT)
            .post(json.encodeToString(bodyJson).toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            rateLimiter.recordHeaders(
                limitHeader = response.header("X-RateLimit-Limit"),
                remainingHeader = response.header("X-RateLimit-Remaining"),
                retryAfterSecondsHeader = response.header("Retry-After"),
                httpStatusCode = response.code,
            )

            if (response.code == 429 && attempt <= MAX_RETRY_ATTEMPTS) {
                return@withContext executeGraphQl(query, variables, token, attempt + 1)
            }

            if (!response.isSuccessful) {
                throw IOException("AniList GraphQL error HTTP ${response.code}")
            }

            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            root["data"]?.jsonObject ?: throw IOException("AniList returned empty data")
        }
    }

    private fun parseMedia(obj: JsonObject): AniListMedia {
        val titleObj = obj["title"]?.jsonObject
        val coverObj = obj["coverImage"]?.jsonObject
        val country = obj["countryOfOrigin"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val format = when {
            country == "KR" -> SeriesFormat.MANHWA
            country == "CN" || country == "TW" -> SeriesFormat.MANHUA
            obj["format"]?.jsonPrimitive?.contentOrNull == "ONE_SHOT" -> SeriesFormat.ONE_SHOT
            else -> SeriesFormat.MANGA
        }
        val pubStatus = when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "RELEASING" -> PublicationStatus.ONGOING
            "FINISHED" -> PublicationStatus.COMPLETED
            "HIATUS" -> PublicationStatus.HIATUS
            "CANCELLED" -> PublicationStatus.CANCELLED
            else -> PublicationStatus.UNKNOWN
        }
        val genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val tags = obj["tags"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val staff = obj["staff"]?.jsonObject?.get("nodes")?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonObject?.get("full")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val relations = obj["relations"]?.jsonObject?.get("edges")?.jsonArray?.mapNotNull { edge ->
            val node = edge.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
            AniListRelation(
                mediaId = node["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                relationType = edge.jsonObject["relationType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = node["title"]?.jsonObject?.get("userPreferred")?.jsonPrimitive?.contentOrNull.orEmpty(),
                coverUrl = node["coverImage"]?.jsonObject?.get("medium")?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        val recs = obj["recommendations"]?.jsonObject?.get("nodes")?.jsonArray?.mapNotNull { rec ->
            val node = rec.jsonObject["mediaRecommendation"]?.jsonObject ?: return@mapNotNull null
            AniListRecommendation(
                mediaId = node["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                title = node["title"]?.jsonObject?.get("userPreferred")?.jsonPrimitive?.contentOrNull.orEmpty(),
                coverUrl = node["coverImage"]?.jsonObject?.get("medium")?.jsonPrimitive?.contentOrNull,
                rating = rec.jsonObject["rating"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.orEmpty()

        return AniListMedia(
            id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
            titleRomaji = titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull.orEmpty(),
            titleEnglish = titleObj?.get("english")?.jsonPrimitive?.contentOrNull,
            titleNative = titleObj?.get("native")?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull
                ?.replace(Regex("<[^>]*>"), ""),
            coverLarge = coverObj?.get("extraLarge")?.jsonPrimitive?.contentOrNull
                ?: coverObj?.get("large")?.jsonPrimitive?.contentOrNull,
            bannerImage = obj["bannerImage"]?.jsonPrimitive?.contentOrNull,
            format = format,
            status = pubStatus,
            averageScore = obj["averageScore"]?.jsonPrimitive?.intOrNull,
            chapters = obj["chapters"]?.jsonPrimitive?.intOrNull,
            genres = genres,
            tags = tags,
            staff = staff,
            relations = relations,
            recommendations = recs,
        )
    }

    companion object {
        const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
        private const val MAX_RETRY_ATTEMPTS = 3

        private const val VIEWER_QUERY = """
            query {
              Viewer {
                id
                name
                avatar { large }
                bannerImage
                statistics { manga { count chaptersRead } }
              }
            }
        """

        private const val POPULAR_MANGA_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: MANGA, sort: [TRENDING_DESC, POPULARITY_DESC]) {
                  id
                  title { romaji english native }
                  description(asHtml: false)
                  coverImage { extraLarge large }
                  bannerImage
                  format
                  countryOfOrigin
                  status
                  averageScore
                  chapters
                  genres
                }
              }
            }
        """

        private const val SEARCH_MANGA_QUERY = """
            query (${'$'}search: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                media(type: MANGA, search: ${'$'}search) {
                  id
                  title { romaji english native }
                  description(asHtml: false)
                  coverImage { extraLarge large }
                  bannerImage
                  format
                  countryOfOrigin
                  status
                  averageScore
                  chapters
                  genres
                }
              }
            }
        """

        private const val MEDIA_DETAIL_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: MANGA) {
                id
                title { romaji english native }
                description(asHtml: false)
                coverImage { extraLarge large }
                bannerImage
                format
                countryOfOrigin
                status
                averageScore
                chapters
                genres
                tags { name }
                staff(perPage: 6) { nodes { name { full } } }
                relations { edges { relationType node { id title { userPreferred } coverImage { medium } } } }
                recommendations(perPage: 6, sort: RATING_DESC) {
                  nodes { rating mediaRecommendation { id title { userPreferred } coverImage { medium } } }
                }
              }
            }
        """

        private const val SAVE_MEDIA_LIST_MUTATION = """
            mutation (
              ${'$'}mediaId: Int,
              ${'$'}status: MediaListStatus,
              ${'$'}progress: Int,
              ${'$'}score: Float,
              ${'$'}notes: String,
              ${'$'}startedAt: FuzzyDateInput,
              ${'$'}completedAt: FuzzyDateInput,
              ${'$'}customLists: [String]
            ) {
              SaveMediaListEntry(
                mediaId: ${'$'}mediaId,
                status: ${'$'}status,
                progress: ${'$'}progress,
                score: ${'$'}score,
                notes: ${'$'}notes,
                startedAt: ${'$'}startedAt,
                completedAt: ${'$'}completedAt,
                customLists: ${'$'}customLists
              ) {
                id
                status
                progress
              }
            }
        """
    }
}

@Singleton
class AniListSyncEngine @Inject constructor(
    private val client: AniListGraphQlClient,
    private val seriesDao: SeriesDao,
    private val pendingMutationDao: PendingMutationDao,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) {
    suspend fun pushSeriesProgress(
        series: Series,
        completedChapters: Int,
        categoryNames: List<String>,
        token: String?,
    ) {
        val settings = settingsRepository.currentSettings()
        val variables = AniListSyncPayloadBuilder.buildSaveMediaListVariables(
            series = series,
            completedChapterCount = completedChapters,
            customCategoryNames = categoryNames,
            policy = settings.syncPolicy,
            isIncognito = settings.incognitoMode,
        ) ?: return

        if (token.isNullOrBlank()) {
            enqueueOfflineMutation(series, variables)
            return
        }

        client.saveMediaListEntry(variables, token).onFailure {
            enqueueOfflineMutation(series, variables)
        }
    }

    suspend fun flushOfflineQueue(token: String?): Int {
        if (token.isNullOrBlank()) return 0
        val queued = pendingMutationDao.getAll()
        var flushed = 0
        for (mutation in queued) {
            val vars = runCatching { json.parseToJsonElement(mutation.payloadJson).jsonObject }.getOrNull()
                ?: continue
            val result = client.saveMediaListEntry(vars, token)
            if (result.isSuccess) {
                pendingMutationDao.deleteById(mutation.id)
                flushed++
            } else {
                pendingMutationDao.incrementRetry(mutation.id)
                break
            }
        }
        client.rateLimiter.updatePendingMutations(pendingMutationDao.getAll().size)
        return flushed
    }

    suspend fun importMediaIntoLibrary(
        media: AniListMedia,
        status: ReadingStatus,
        conflictResolution: ConflictResolution,
    ) {
        val existing = seriesDao.getByAniListId(media.id)?.toDomain()
        val merged = when {
            existing == null -> Series(
                sourceId = -1L,
                url = "anilist://${media.id}",
                title = media.preferredTitle,
                description = media.description,
                coverUrl = media.coverLarge,
                bannerUrl = media.bannerImage,
                genres = media.genres,
                format = media.format,
                publicationStatus = media.status,
                inLibrary = true,
                dateAdded = System.currentTimeMillis(),
                anilistId = media.id,
                anilistScore = media.averageScore,
                readingStatus = status,
            )
            conflictResolution == ConflictResolution.SKIP_EXISTING -> return
            conflictResolution == ConflictResolution.OVERWRITE_LOCAL -> existing.copy(
                title = media.preferredTitle,
                description = media.description,
                coverUrl = media.coverLarge,
                bannerUrl = media.bannerImage,
                genres = media.genres,
                anilistScore = media.averageScore,
                readingStatus = status,
            )
            else -> existing.copy(
                description = existing.description ?: media.description,
                coverUrl = existing.coverUrl ?: media.coverLarge,
                bannerUrl = existing.bannerUrl ?: media.bannerImage,
                anilistScore = media.averageScore,
                readingStatus = status,
            )
        }
        seriesDao.upsert(merged.toEntity())
    }

    private suspend fun enqueueOfflineMutation(series: Series, variables: JsonObject) {
        val anilistId = series.anilistId ?: return
        pendingMutationDao.insert(
            PendingAniListMutationEntity(
                seriesId = series.id,
                anilistId = anilistId,
                payloadJson = json.encodeToString(variables),
                createdAt = System.currentTimeMillis(),
            ),
        )
        client.rateLimiter.updatePendingMutations(pendingMutationDao.getAll().size)
    }
}
