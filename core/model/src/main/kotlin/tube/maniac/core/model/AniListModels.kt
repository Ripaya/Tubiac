package tube.maniac.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SyncFieldPolicy {
    LOCAL_ONLY,
    SYNCED_TO_ANILIST,
}

/**
 * Granular Local-Only vs. Synced to AniList Policy Matrix.
 * Reading history is structurally locked to LOCAL_ONLY and never leaves the device.
 */
@Serializable
data class AniListSyncPolicy(
    val chapterProgress: SyncFieldPolicy = SyncFieldPolicy.SYNCED_TO_ANILIST,
    val readingStatus: SyncFieldPolicy = SyncFieldPolicy.SYNCED_TO_ANILIST,
    val personalScore: SyncFieldPolicy = SyncFieldPolicy.SYNCED_TO_ANILIST,
    val customCategoriesAndTags: SyncFieldPolicy = SyncFieldPolicy.LOCAL_ONLY,
    val startAndFinishDates: SyncFieldPolicy = SyncFieldPolicy.SYNCED_TO_ANILIST,
    val privateNotes: SyncFieldPolicy = SyncFieldPolicy.LOCAL_ONLY,
) {
    val detailedReadingHistory: SyncFieldPolicy
        get() = SyncFieldPolicy.LOCAL_ONLY
}

@Serializable
enum class ConflictResolution {
    MERGE,
    OVERWRITE_LOCAL,
    SKIP_EXISTING,
}

@Serializable
enum class SyncMode {
    REALTIME_ON_COMPLETE,
    SCHEDULED_BATCH,
    MANUAL_ONLY,
}

@Serializable
data class AniListUser(
    val id: Int,
    val name: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val chaptersRead: Int = 0,
    val mangaCount: Int = 0,
)

@Serializable
data class AniListMedia(
    val id: Int,
    val titleRomaji: String,
    val titleEnglish: String? = null,
    val titleNative: String? = null,
    val description: String? = null,
    val coverLarge: String? = null,
    val bannerImage: String? = null,
    val format: SeriesFormat = SeriesFormat.MANGA,
    val status: PublicationStatus = PublicationStatus.UNKNOWN,
    val averageScore: Int? = null,
    val chapters: Int? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val staff: List<String> = emptyList(),
    val relations: List<AniListRelation> = emptyList(),
    val recommendations: List<AniListRecommendation> = emptyList(),
) {
    val preferredTitle: String
        get() = titleEnglish?.takeIf { it.isNotBlank() } ?: titleRomaji
}

@Serializable
data class AniListRelation(
    val mediaId: Int,
    val relationType: String,
    val title: String,
    val coverUrl: String? = null,
)

@Serializable
data class AniListRecommendation(
    val mediaId: Int,
    val title: String,
    val coverUrl: String? = null,
    val rating: Int = 0,
)

@Serializable
data class AniListReview(
    val id: Int,
    val summary: String,
    val body: String = "",
    val score: Int = 0,
    val rating: Int = 0,
    val userName: String = "",
)

@Serializable
data class AniListThread(
    val id: Int,
    val title: String,
    val body: String = "",
    val replyCount: Int = 0,
    val viewCount: Int = 0,
    val siteUrl: String = "",
    val authorName: String = "",
)

@Serializable
data class AniListMediaListEntry(
    val media: AniListMedia,
    val status: ReadingStatus,
    val progress: Int = 0,
    val score: Float = 0f,
    val notes: String? = null,
)

@Serializable
data class RateLimitStatus(
    val remaining: Int = 90,
    val limit: Int = 90,
    val isDegradedMode: Boolean = false,
    val retryAfterEpochMs: Long = 0L,
    val lastSyncedAtEpochMs: Long = 0L,
    val pendingMutationsCount: Int = 0,
)

@Serializable
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED_BLACK,
}

@Serializable
enum class AnimationSpeed {
    NORMAL,
    FAST,
    REDUCED,
}

@Serializable
enum class ShelfDisplayMode {
    GRID,
    LIST,
    COMPACT,
}

@Serializable
enum class DoHProvider(val label: String, val dohUrl: String, val bootstrapIps: List<String>) {
    DISABLED("System DNS", "", emptyList()),
    CLOUDFLARE("Cloudflare (1.1.1.1)", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE("Google (8.8.8.8)", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
    ADGUARD("AdGuard DNS", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
}

@Serializable
enum class DownloadStorageFormat {
    CBZ_ARCHIVE,
    RAW_IMAGE_FOLDER,
}

@Serializable
enum class AutoBackupFrequency {
    OFF,
    DAILY,
    WEEKLY,
}

@Serializable
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val anilistClientId: String = "",
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val useDynamicColor: Boolean = false,
    val useMatteTexture: Boolean = true,
    val useCoverDerivedDetailAccent: Boolean = true,
    val animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,
    val incognitoMode: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val secureScreenEnabled: Boolean = false,
    val dohProvider: DoHProvider = DoHProvider.CLOUDFLARE,
    val customUserAgent: String = DEFAULT_USER_AGENT,
    val spoofXRequestedWith: Boolean = true,
    val showNsfwExtensions: Boolean = false,
    val shelfDisplayMode: ShelfDisplayMode = ShelfDisplayMode.GRID,
    val showUnreadBadges: Boolean = true,
    val showDownloadBadges: Boolean = true,
    val skipCompletedOnUpdate: Boolean = true,
    val unreadUpdateCap: Int = 0,
    val concurrentPageDownloads: Int = 5,
    val concurrentSourceDownloads: Int = 2,
    val wifiOnlyDownloads: Boolean = true,
    val autoDownloadNewChapters: Boolean = false,
    val downloadAheadCount: Int = 2,
    val autoDeleteReadChapters: Boolean = false,
    val downloadStorageFormat: DownloadStorageFormat = DownloadStorageFormat.CBZ_ARCHIVE,
    val localSourceTreeUri: String? = null,
    val autoBackupFrequency: AutoBackupFrequency = AutoBackupFrequency.DAILY,
    val autoBackupMaxFiles: Int = 5,
    val autoBackupTreeUri: String? = null,
    val syncMode: SyncMode = SyncMode.REALTIME_ON_COMPLETE,
    val syncPolicy: AniListSyncPolicy = AniListSyncPolicy(),
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val autoReportIssuesEnabled: Boolean = true,
    val customIssueRelayUrl: String = "",
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
    }
}

@Serializable
data class BackupSeriesEntry(
    val series: Series,
    val chapters: List<Chapter>,
    val categoryNames: List<String>,
    val tagNames: List<String>,
)

@Serializable
data class BackupPayload(
    val schemaVersion: Int = 1,
    val appVersion: String = "0.1.0-alpha1",
    val createdAtEpochMs: Long,
    val seriesEntries: List<BackupSeriesEntry> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val repos: List<ExtensionRepo> = emptyList(),
    val settings: AppSettings? = null,
)

@Serializable
data class SelectiveRestoreOptions(
    val restoreLibraryAndChapters: Boolean = true,
    val restoreCategoriesAndTags: Boolean = true,
    val restoreHistory: Boolean = true,
    val restoreAniListMappings: Boolean = true,
    val restoreExtensionRepos: Boolean = true,
    val restoreSettings: Boolean = false,
)
