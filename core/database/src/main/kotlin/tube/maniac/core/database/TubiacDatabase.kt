package tube.maniac.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tube.maniac.core.database.dao.CategoryDao
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.dao.ExtensionRepoDao
import tube.maniac.core.database.dao.HistoryDao
import tube.maniac.core.database.dao.PendingMutationDao
import tube.maniac.core.database.dao.SavedSearchDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.dao.TagDao
import tube.maniac.core.database.dao.UpdateErrorDao
import tube.maniac.core.database.entity.CategoryEntity
import tube.maniac.core.database.entity.ChapterEntity
import tube.maniac.core.database.entity.DownloadEntity
import tube.maniac.core.database.entity.ExtensionRepoEntity
import tube.maniac.core.database.entity.HistoryEntity
import tube.maniac.core.database.entity.PendingAniListMutationEntity
import tube.maniac.core.database.entity.SavedSearchEntity
import tube.maniac.core.database.entity.SeriesCategoryCrossRef
import tube.maniac.core.database.entity.SeriesEntity
import tube.maniac.core.database.entity.SeriesFtsEntity
import tube.maniac.core.database.entity.SeriesTagCrossRef
import tube.maniac.core.database.entity.TagEntity
import tube.maniac.core.database.entity.UpdateErrorEntity
import tube.maniac.core.model.DownloadState
import tube.maniac.core.model.MetadataSource
import tube.maniac.core.model.PublicationStatus
import tube.maniac.core.model.ReaderMode
import tube.maniac.core.model.ReadingStatus
import tube.maniac.core.model.SeriesFormat

class TubiacConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(list: List<String>): String = json.encodeToString(list)

    @TypeConverter
    fun toStringList(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromSeriesFormat(value: SeriesFormat): String = value.name

    @TypeConverter
    fun toSeriesFormat(raw: String): SeriesFormat =
        runCatching { SeriesFormat.valueOf(raw) }.getOrDefault(SeriesFormat.UNKNOWN)

    @TypeConverter
    fun fromPublicationStatus(value: PublicationStatus): String = value.name

    @TypeConverter
    fun toPublicationStatus(raw: String): PublicationStatus =
        runCatching { PublicationStatus.valueOf(raw) }.getOrDefault(PublicationStatus.UNKNOWN)

    @TypeConverter
    fun fromReadingStatus(value: ReadingStatus): String = value.name

    @TypeConverter
    fun toReadingStatus(raw: String): ReadingStatus =
        runCatching { ReadingStatus.valueOf(raw) }.getOrDefault(ReadingStatus.CURRENT)

    @TypeConverter
    fun fromMetadataSource(value: MetadataSource): String = value.name

    @TypeConverter
    fun toMetadataSource(raw: String): MetadataSource =
        runCatching { MetadataSource.valueOf(raw) }.getOrDefault(MetadataSource.ANILIST)

    @TypeConverter
    fun fromReaderMode(value: ReaderMode?): String? = value?.name

    @TypeConverter
    fun toReaderMode(raw: String?): ReaderMode? =
        raw?.let { runCatching { ReaderMode.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromDownloadState(value: DownloadState): String = value.name

    @TypeConverter
    fun toDownloadState(raw: String): DownloadState =
        runCatching { DownloadState.valueOf(raw) }.getOrDefault(DownloadState.QUEUED)
}

@Database(
    entities = [
        SeriesEntity::class,
        SeriesFtsEntity::class,
        ChapterEntity::class,
        CategoryEntity::class,
        SeriesCategoryCrossRef::class,
        TagEntity::class,
        SeriesTagCrossRef::class,
        HistoryEntity::class,
        DownloadEntity::class,
        ExtensionRepoEntity::class,
        UpdateErrorEntity::class,
        SavedSearchEntity::class,
        PendingAniListMutationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(TubiacConverters::class)
abstract class TubiacDatabase : RoomDatabase() {
    abstract fun seriesDao(): SeriesDao
    abstract fun chapterDao(): ChapterDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun extensionRepoDao(): ExtensionRepoDao
    abstract fun updateErrorDao(): UpdateErrorDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun pendingMutationDao(): PendingMutationDao

    companion object {
        const val DATABASE_NAME = "tubiac.db"

        /**
         * Sanitizes user input into a safe Room FTS4 prefix query.
         */
        fun buildSafeFtsQuery(rawInput: String): String {
            val tokens = rawInput.trim()
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            return tokens.joinToString(" ") { "$it*" }
        }
    }
}
