package tube.maniac.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReadingStatus {
    CURRENT,
    PLANNING,
    COMPLETED,
    PAUSED,
    DROPPED,
    REPEATING,
}

@Serializable
enum class SeriesFormat {
    MANGA,
    MANHWA,
    MANHUA,
    WEBTOON,
    COMIC,
    ONE_SHOT,
    UNKNOWN,
}

@Serializable
enum class MetadataSource {
    ANILIST,
    EXTENSION_SOURCE,
}

@Serializable
enum class PublicationStatus {
    ONGOING,
    COMPLETED,
    HIATUS,
    CANCELLED,
    UNKNOWN,
}

@Serializable
data class Series(
    val id: Long = 0L,
    val sourceId: Long,
    val url: String,
    val title: String,
    val customTitle: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val customDescription: String? = null,
    val coverUrl: String? = null,
    val customCoverUrl: String? = null,
    val bannerUrl: String? = null,
    val genres: List<String> = emptyList(),
    val format: SeriesFormat = SeriesFormat.UNKNOWN,
    val publicationStatus: PublicationStatus = PublicationStatus.UNKNOWN,
    val inLibrary: Boolean = false,
    val dateAdded: Long = 0L,
    val lastUpdated: Long = 0L,
    val anilistId: Int? = null,
    val anilistScore: Int? = null,
    val userScore: Float? = null,
    val readingStatus: ReadingStatus = ReadingStatus.CURRENT,
    val metadataSource: MetadataSource = MetadataSource.ANILIST,
    val readerModeOverride: ReaderMode? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val privateNotes: String? = null,
    val localOnly: Boolean = false,
    val unreadCount: Int = 0,
    val downloadedCount: Int = 0,
    val totalChapters: Int = 0,
) {
    val displayTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() } ?: title

    val displayDescription: String
        get() = customDescription?.takeIf { it.isNotBlank() } ?: description.orEmpty()

    val displayCoverUrl: String?
        get() = customCoverUrl?.takeIf { it.isNotBlank() } ?: coverUrl
}

@Serializable
data class Chapter(
    val id: Long = 0L,
    val seriesId: Long,
    val url: String,
    val name: String,
    val chapterNumber: Float = -1f,
    val scanlator: String? = null,
    val dateUpload: Long = 0L,
    val dateFetch: Long = 0L,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
    val downloaded: Boolean = false,
    val sourceOrder: Int = 0,
) {
    val progressFraction: Float
        get() = when {
            read -> 1f
            totalPages > 0 -> (lastPageRead.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

@Serializable
data class Category(
    val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val isHidden: Boolean = false,
    val sortKey: String = "ALPHABETICAL",
    val sortAscending: Boolean = true,
)

@Serializable
data class Tag(
    val id: Long = 0L,
    val name: String,
    val colorHex: String = "#D9483B",
)

@Serializable
data class HistoryEntry(
    val id: Long = 0L,
    val seriesId: Long,
    val chapterId: Long,
    val seriesTitle: String,
    val coverUrl: String? = null,
    val chapterName: String,
    val chapterNumber: Float = -1f,
    val sourceName: String,
    val lastReadAt: Long,
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
)

@Serializable
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
data class DownloadTask(
    val id: Long = 0L,
    val seriesId: Long,
    val chapterId: Long,
    val seriesTitle: String,
    val chapterName: String,
    val sourceId: Long,
    val state: DownloadState = DownloadState.QUEUED,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val queueOrder: Int = 0,
    val errorMessage: String? = null,
) {
    val progressPercent: Int
        get() = if (totalPages > 0) ((downloadedPages * 100) / totalPages).coerceIn(0, 100) else 0
}

@Serializable
data class UpdateError(
    val id: Long = 0L,
    val seriesId: Long,
    val seriesTitle: String,
    val sourceId: Long,
    val sourceName: String,
    val seriesUrl: String,
    val reason: String,
    val isCloudflare: Boolean = false,
    val occurredAt: Long,
)

@Serializable
data class ExtensionRepo(
    val id: Long = 0L,
    val name: String,
    val indexUrl: String,
    val signingKeyFingerprint: String? = null,
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    val lastRefreshedAt: Long = 0L,
    val extensionCount: Int = 0,
)

@Serializable
enum class ExtensionInstallState {
    AVAILABLE,
    INSTALLED,
    UPDATE_AVAILABLE,
    INSTALLING,
    UNTRUSTED,
}

@Serializable
data class ExtensionInfo(
    val pkgName: String,
    val name: String,
    val lang: String,
    val versionName: String,
    val versionCode: Long,
    val isNsfw: Boolean = false,
    val apkName: String? = null,
    val iconUrl: String? = null,
    val repoBaseUrl: String? = null,
    val hasConfigurablePreferences: Boolean = false,
    val installState: ExtensionInstallState = ExtensionInstallState.AVAILABLE,
    val installedVersionCode: Long? = null,
)

@Serializable
data class SavedSearch(
    val id: Long = 0L,
    val sourceId: Long,
    val name: String,
    val query: String,
    val filtersJson: String = "{}",
)

@Serializable
data class MigrationCandidate(
    val targetSourceId: Long,
    val targetSourceName: String,
    val targetSeriesUrl: String,
    val targetTitle: String,
    val coverUrl: String? = null,
    val latestChapterNumber: Float = -1f,
)
