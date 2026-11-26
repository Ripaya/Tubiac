package tube.maniac.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import tube.maniac.core.model.Category
import tube.maniac.core.model.Chapter
import tube.maniac.core.model.DownloadState
import tube.maniac.core.model.DownloadTask
import tube.maniac.core.model.ExtensionRepo
import tube.maniac.core.model.HistoryEntry
import tube.maniac.core.model.MetadataSource
import tube.maniac.core.model.PublicationStatus
import tube.maniac.core.model.ReaderMode
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.SavedSearch
import tube.maniac.core.model.Series
import tube.maniac.core.model.SeriesFormat
import tube.maniac.core.model.Tag
import tube.maniac.core.model.UpdateError

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["source_id", "url"], unique = true),
        Index(value = ["anilist_id"]),
        Index(value = ["in_library"]),
    ],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val url: String,
    val title: String,
    @ColumnInfo(name = "custom_title") val customTitle: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    @ColumnInfo(name = "custom_description") val customDescription: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    @ColumnInfo(name = "custom_cover_url") val customCoverUrl: String? = null,
    @ColumnInfo(name = "banner_url") val bannerUrl: String? = null,
    val genres: List<String> = emptyList(),
    val format: SeriesFormat = SeriesFormat.UNKNOWN,
    @ColumnInfo(name = "publication_status") val publicationStatus: PublicationStatus = PublicationStatus.UNKNOWN,
    @ColumnInfo(name = "in_library") val inLibrary: Boolean = false,
    @ColumnInfo(name = "date_added") val dateAdded: Long = 0L,
    @ColumnInfo(name = "last_updated") val lastUpdated: Long = 0L,
    @ColumnInfo(name = "anilist_id") val anilistId: Int? = null,
    @ColumnInfo(name = "anilist_score") val anilistScore: Int? = null,
    @ColumnInfo(name = "user_score") val userScore: Float? = null,
    @ColumnInfo(name = "reading_status") val readingStatus: ReadingStatus = ReadingStatus.CURRENT,
    @ColumnInfo(name = "metadata_source") val metadataSource: MetadataSource = MetadataSource.ANILIST,
    @ColumnInfo(name = "reader_mode_override") val readerModeOverride: ReaderMode? = null,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "private_notes") val privateNotes: String? = null,
    @ColumnInfo(name = "local_only") val localOnly: Boolean = false,
)

@Fts4(contentEntity = SeriesEntity::class)
@Entity(tableName = "series_fts")
data class SeriesFtsEntity(
    val title: String,
    @ColumnInfo(name = "custom_title") val customTitle: String?,
    val author: String?,
    val description: String?,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["series_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["series_id", "url"], unique = true),
        Index(value = ["series_id"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    val url: String,
    val name: String,
    @ColumnInfo(name = "chapter_number") val chapterNumber: Float = -1f,
    val scanlator: String? = null,
    @ColumnInfo(name = "date_upload") val dateUpload: Long = 0L,
    @ColumnInfo(name = "date_fetch") val dateFetch: Long = 0L,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    @ColumnInfo(name = "last_page_read") val lastPageRead: Int = 0,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
    val downloaded: Boolean = false,
    @ColumnInfo(name = "source_order") val sourceOrder: Int = 0,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean = false,
    @ColumnInfo(name = "sort_key") val sortKey: String = "ALPHABETICAL",
    @ColumnInfo(name = "sort_ascending") val sortAscending: Boolean = true,
)

@Entity(
    tableName = "series_categories",
    primaryKeys = ["series_id", "category_id"],
    foreignKeys = [
        ForeignKey(SeriesEntity::class, ["id"], ["series_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("category_id")],
)
data class SeriesCategoryCrossRef(
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "color_hex") val colorHex: String = "#D9483B",
)

@Entity(
    tableName = "series_tags",
    primaryKeys = ["series_id", "tag_id"],
    foreignKeys = [
        ForeignKey(SeriesEntity::class, ["id"], ["series_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tag_id")],
)
data class SeriesTagCrossRef(
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["chapter_id"], unique = true),
        Index(value = ["last_read_at"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "series_title") val seriesTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    @ColumnInfo(name = "chapter_name") val chapterName: String,
    @ColumnInfo(name = "chapter_number") val chapterNumber: Float = -1f,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long,
    @ColumnInfo(name = "last_page_read") val lastPageRead: Int = 0,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
)

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["chapter_id"], unique = true), Index(value = ["queue_order"])],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "series_title") val seriesTitle: String,
    @ColumnInfo(name = "chapter_name") val chapterName: String,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val state: DownloadState = DownloadState.QUEUED,
    @ColumnInfo(name = "downloaded_pages") val downloadedPages: Int = 0,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
    @ColumnInfo(name = "queue_order") val queueOrder: Int = 0,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)

@Entity(tableName = "extension_repos", indices = [Index(value = ["index_url"], unique = true)])
data class ExtensionRepoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "index_url") val indexUrl: String,
    @ColumnInfo(name = "signing_key_fingerprint") val signingKeyFingerprint: String? = null,
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    @ColumnInfo(name = "last_refreshed_at") val lastRefreshedAt: Long = 0L,
    @ColumnInfo(name = "extension_count") val extensionCount: Int = 0,
)

@Entity(tableName = "update_errors", indices = [Index(value = ["series_id"], unique = true)])
data class UpdateErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "series_title") val seriesTitle: String,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "series_url") val seriesUrl: String,
    val reason: String,
    @ColumnInfo(name = "is_cloudflare") val isCloudflare: Boolean = false,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
)

@Entity(tableName = "saved_searches", indices = [Index("source_id")])
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    val name: String,
    val query: String,
    @ColumnInfo(name = "filters_json") val filtersJson: String = "{}",
)

@Entity(tableName = "pending_anilist_mutations", indices = [Index("created_at")])
data class PendingAniListMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "anilist_id") val anilistId: Int,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
)

// Domain mappers
fun SeriesEntity.toDomain(unreadCount: Int = 0, downloadedCount: Int = 0, totalChapters: Int = 0) = Series(
    id = id,
    sourceId = sourceId,
    url = url,
    title = title,
    customTitle = customTitle,
    author = author,
    artist = artist,
    description = description,
    customDescription = customDescription,
    coverUrl = coverUrl,
    customCoverUrl = customCoverUrl,
    bannerUrl = bannerUrl,
    genres = genres,
    format = format,
    publicationStatus = publicationStatus,
    inLibrary = inLibrary,
    dateAdded = dateAdded,
    lastUpdated = lastUpdated,
    anilistId = anilistId,
    anilistScore = anilistScore,
    userScore = userScore,
    readingStatus = readingStatus,
    metadataSource = metadataSource,
    readerModeOverride = readerModeOverride,
    startedAt = startedAt,
    completedAt = completedAt,
    privateNotes = privateNotes,
    localOnly = localOnly,
    unreadCount = unreadCount,
    downloadedCount = downloadedCount,
    totalChapters = totalChapters,
)

fun Series.toEntity() = SeriesEntity(
    id = id,
    sourceId = sourceId,
    url = url,
    title = title,
    customTitle = customTitle,
    author = author,
    artist = artist,
    description = description,
    customDescription = customDescription,
    coverUrl = coverUrl,
    customCoverUrl = customCoverUrl,
    bannerUrl = bannerUrl,
    genres = genres,
    format = format,
    publicationStatus = publicationStatus,
    inLibrary = inLibrary,
    dateAdded = dateAdded,
    lastUpdated = lastUpdated,
    anilistId = anilistId,
    anilistScore = anilistScore,
    userScore = userScore,
    readingStatus = readingStatus,
    metadataSource = metadataSource,
    readerModeOverride = readerModeOverride,
    startedAt = startedAt,
    completedAt = completedAt,
    privateNotes = privateNotes,
    localOnly = localOnly,
)

fun ChapterEntity.toDomain() = Chapter(
    id = id,
    seriesId = seriesId,
    url = url,
    name = name,
    chapterNumber = chapterNumber,
    scanlator = scanlator,
    dateUpload = dateUpload,
    dateFetch = dateFetch,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    totalPages = totalPages,
    downloaded = downloaded,
    sourceOrder = sourceOrder,
)

fun Chapter.toEntity() = ChapterEntity(
    id = id,
    seriesId = seriesId,
    url = url,
    name = name,
    chapterNumber = chapterNumber,
    scanlator = scanlator,
    dateUpload = dateUpload,
    dateFetch = dateFetch,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    totalPages = totalPages,
    downloaded = downloaded,
    sourceOrder = sourceOrder,
)

fun CategoryEntity.toDomain() = Category(id, name, sortOrder, isDefault, isHidden, sortKey, sortAscending)
fun Category.toEntity() = CategoryEntity(id, name, sortOrder, isDefault, isHidden, sortKey, sortAscending)
fun TagEntity.toDomain() = Tag(id, name, colorHex)
fun Tag.toEntity() = TagEntity(id, name, colorHex)
fun HistoryEntity.toDomain() = HistoryEntry(
    id, seriesId, chapterId, seriesTitle, coverUrl, chapterName, chapterNumber, sourceName, lastReadAt, lastPageRead, totalPages,
)
fun HistoryEntry.toEntity() = HistoryEntity(
    id, seriesId, chapterId, seriesTitle, coverUrl, chapterName, chapterNumber, sourceName, lastReadAt, lastPageRead, totalPages,
)
fun DownloadEntity.toDomain() = DownloadTask(
    id, seriesId, chapterId, seriesTitle, chapterName, sourceId, state, downloadedPages, totalPages, queueOrder, errorMessage,
)
fun DownloadTask.toEntity() = DownloadEntity(
    id, seriesId, chapterId, seriesTitle, chapterName, sourceId, state, downloadedPages, totalPages, queueOrder, errorMessage,
)
fun ExtensionRepoEntity.toDomain() = ExtensionRepo(
    id, name, indexUrl, signingKeyFingerprint, enabled, trusted, lastRefreshedAt, extensionCount,
)
fun ExtensionRepo.toEntity() = ExtensionRepoEntity(
    id, name, indexUrl, signingKeyFingerprint, enabled, trusted, lastRefreshedAt, extensionCount,
)
fun UpdateErrorEntity.toDomain() = UpdateError(
    id, seriesId, seriesTitle, sourceId, sourceName, seriesUrl, reason, isCloudflare, occurredAt,
)
fun UpdateError.toEntity() = UpdateErrorEntity(
    id, seriesId, seriesTitle, sourceId, sourceName, seriesUrl, reason, isCloudflare, occurredAt,
)
fun SavedSearchEntity.toDomain() = SavedSearch(id, sourceId, name, query, filtersJson)
fun SavedSearch.toEntity() = SavedSearchEntity(id, sourceId, name, query, filtersJson)
