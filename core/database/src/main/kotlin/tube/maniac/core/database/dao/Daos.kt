package tube.maniac.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tube.maniac.core.database.entity.CategoryEntity
import tube.maniac.core.database.entity.ChapterEntity
import tube.maniac.core.database.entity.DownloadEntity
import tube.maniac.core.database.entity.ExtensionRepoEntity
import tube.maniac.core.database.entity.HistoryEntity
import tube.maniac.core.database.entity.PendingAniListMutationEntity
import tube.maniac.core.database.entity.SavedSearchEntity
import tube.maniac.core.database.entity.SeriesCategoryCrossRef
import tube.maniac.core.database.entity.SeriesEntity
import tube.maniac.core.database.entity.SeriesTagCrossRef
import tube.maniac.core.database.entity.TagEntity
import tube.maniac.core.database.entity.UpdateErrorEntity
import tube.maniac.core.model.DownloadState
import tube.maniac.core.model.ReadingStatus

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE in_library = 1 ORDER BY date_added DESC")
    fun observeLibrary(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE in_library = 1 ORDER BY date_added DESC LIMIT :limit")
    fun observeLastAdded(limit: Int = 15): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :seriesId LIMIT 1")
    fun observeById(seriesId: Long): Flow<SeriesEntity?>

    @Query("SELECT * FROM series WHERE id = :seriesId LIMIT 1")
    suspend fun getById(seriesId: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE source_id = :sourceId AND url = :url LIMIT 1")
    suspend fun getBySourceAndUrl(sourceId: Long, url: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE anilist_id = :anilistId LIMIT 1")
    suspend fun getByAniListId(anilistId: Int): SeriesEntity?

    @Query("SELECT * FROM series WHERE in_library = 1")
    suspend fun getLibrarySnapshot(): List<SeriesEntity>

    @Query(
        """
        SELECT series.* FROM series
        JOIN series_fts ON series.id = series_fts.rowid
        WHERE series_fts MATCH :ftsQuery
        ORDER BY series.in_library DESC, series.title ASC
        """,
    )
    fun searchFts(ftsQuery: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE in_library = 1 AND reading_status = :status ORDER BY title ASC")
    fun observeByReadingStatus(status: ReadingStatus): Flow<List<SeriesEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(series: SeriesEntity): Long

    @Update
    suspend fun update(series: SeriesEntity)

    @Query("UPDATE series SET in_library = :inLibrary, date_added = :dateAdded WHERE id = :seriesId")
    suspend fun setInLibrary(seriesId: Long, inLibrary: Boolean, dateAdded: Long)

    @Query("DELETE FROM series WHERE id = :seriesId")
    suspend fun deleteById(seriesId: Long)

    @Query("DELETE FROM series")
    suspend fun deleteAll()
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE series_id = :seriesId ORDER BY source_order ASC, chapter_number DESC")
    fun observeBySeries(seriesId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE series_id = :seriesId ORDER BY source_order ASC, chapter_number DESC")
    suspend fun getBySeries(seriesId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getById(chapterId: Long): ChapterEntity?

    @Query(
        """
        SELECT c.* FROM chapters c
        INNER JOIN series s ON c.series_id = s.id
        WHERE s.in_library = 1 AND c.read = 0
        ORDER BY c.date_upload DESC, c.date_fetch DESC
        LIMIT :limit
        """,
    )
    fun observeLatestReleased(limit: Int = 25): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapters WHERE series_id = :seriesId AND read = 0")
    suspend fun countUnread(seriesId: Long): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE series_id = :seriesId AND downloaded = 1")
    suspend fun countDownloaded(seriesId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query("UPDATE chapters SET read = :read, last_page_read = :lastPageRead, total_pages = :totalPages WHERE id = :chapterId")
    suspend fun updateProgress(chapterId: Long, read: Boolean, lastPageRead: Int, totalPages: Int)

    @Query("UPDATE chapters SET read = :read WHERE id IN (:chapterIds)")
    suspend fun setReadBatch(chapterIds: List<Long>, read: Boolean)

    @Query("UPDATE chapters SET bookmark = :bookmark WHERE id IN (:chapterIds)")
    suspend fun setBookmarkBatch(chapterIds: List<Long>, bookmark: Boolean)

    @Query("UPDATE chapters SET downloaded = :downloaded WHERE id = :chapterId")
    suspend fun setDownloaded(chapterId: Long, downloaded: Boolean)

    @Query("DELETE FROM chapters WHERE series_id = :seriesId")
    suspend fun deleteBySeries(seriesId: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort_order ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM series_categories")
    fun observeAllMappings(): Flow<List<SeriesCategoryCrossRef>>

    @Query("SELECT category_id FROM series_categories WHERE series_id = :seriesId")
    suspend fun getCategoryIdsForSeries(seriesId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignSeriesToCategory(crossRef: SeriesCategoryCrossRef)

    @Query("DELETE FROM series_categories WHERE series_id = :seriesId")
    suspend fun clearCategoriesForSeries(seriesId: Long)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteById(categoryId: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM series_tags")
    fun observeAllMappings(): Flow<List<SeriesTagCrossRef>>

    @Query("SELECT tag_id FROM series_tags WHERE series_id = :seriesId")
    suspend fun getTagIdsForSeries(seriesId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignSeriesToTag(crossRef: SeriesTagCrossRef)

    @Query("DELETE FROM series_tags WHERE series_id = :seriesId")
    suspend fun clearTagsForSeries(seriesId: Long)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteById(tagId: Long)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY last_read_at DESC")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY last_read_at DESC LIMIT :limit")
    fun observeContinueReading(limit: Int = 15): Flow<List<HistoryEntity>>

    @Query(
        """
        SELECT * FROM history
        WHERE series_title LIKE '%' || :query || '%' OR chapter_name LIKE '%' || :query || '%'
        ORDER BY last_read_at DESC
        """,
    )
    fun searchHistory(query: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY last_read_at DESC")
    suspend fun getAll(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY queue_order ASC, id ASC")
    fun observeQueue(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING') ORDER BY queue_order ASC, id ASC")
    suspend fun getPendingDownloads(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadEntity): Long

    @Query("UPDATE downloads SET state = :state, error_message = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: DownloadState, error: String? = null)

    @Query("UPDATE downloads SET downloaded_pages = :downloaded, total_pages = :total, state = :state WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Int, total: Int, state: DownloadState)

    @Query("UPDATE downloads SET queue_order = :order WHERE id = :id")
    suspend fun updateOrder(id: Long, order: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}

@Dao
interface ExtensionRepoDao {
    @Query("SELECT * FROM extension_repos ORDER BY name ASC")
    fun observeAll(): Flow<List<ExtensionRepoEntity>>

    @Query("SELECT * FROM extension_repos ORDER BY name ASC")
    suspend fun getAll(): List<ExtensionRepoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: ExtensionRepoEntity): Long

    @Query("UPDATE extension_repos SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE extension_repos SET trusted = :trusted WHERE id = :id")
    suspend fun setTrusted(id: Long, trusted: Boolean)

    @Query("UPDATE extension_repos SET last_refreshed_at = :timestamp, extension_count = :count WHERE id = :id")
    suspend fun updateRefreshStatus(id: Long, timestamp: Long, count: Int)

    @Query("DELETE FROM extension_repos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM extension_repos")
    suspend fun deleteAll()
}

@Dao
interface UpdateErrorDao {
    @Query("SELECT * FROM update_errors ORDER BY occurred_at DESC")
    fun observeAll(): Flow<List<UpdateErrorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(error: UpdateErrorEntity): Long

    @Query("DELETE FROM update_errors WHERE series_id = :seriesId")
    suspend fun deleteBySeries(seriesId: Long)

    @Query("DELETE FROM update_errors")
    suspend fun clearAll()
}

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_searches WHERE source_id = :sourceId ORDER BY name ASC")
    fun observeBySource(sourceId: Long): Flow<List<SavedSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(search: SavedSearchEntity): Long

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PendingMutationDao {
    @Query("SELECT * FROM pending_anilist_mutations ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingAniListMutationEntity>

    @Query("SELECT COUNT(*) FROM pending_anilist_mutations")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mutation: PendingAniListMutationEntity): Long

    @Query("UPDATE pending_anilist_mutations SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("DELETE FROM pending_anilist_mutations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
