package tube.maniac.core.database

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tube.maniac.core.database.entity.SeriesCategoryCrossRef
import tube.maniac.core.database.entity.SeriesTagCrossRef
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.database.entity.toEntity
import tube.maniac.core.model.BackupPayload
import tube.maniac.core.model.BackupSeriesEntry
import tube.maniac.core.model.SelectiveRestoreOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupFormat {
    TACHIBK_GZIP,
    JSON_ZIP,
}

/**
 * Pure serializer/deserializer for Tubiac backups (.tachibk Gzip envelope & JSON+ZIP archive)
 * decoupled from Android Context so round-trip integrity tests run cleanly on the JVM.
 */
object BackupCodec {
    private const val TACHIBK_MAGIC = "TUBIAC_BK_V1\n"
    private const val ZIP_PAYLOAD_ENTRY = "tubiac-backup.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: BackupPayload, format: BackupFormat, output: OutputStream) {
        val rawJson = json.encodeToString(payload)
        when (format) {
            BackupFormat.TACHIBK_GZIP -> {
                GZIPOutputStream(output).use { gzip ->
                    gzip.write(TACHIBK_MAGIC.toByteArray(Charsets.UTF_8))
                    gzip.write(rawJson.toByteArray(Charsets.UTF_8))
                }
            }
            BackupFormat.JSON_ZIP -> {
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(ZIP_PAYLOAD_ENTRY))
                    zip.write(rawJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    fun decode(input: InputStream, format: BackupFormat): BackupPayload {
        val rawJson = when (format) {
            BackupFormat.TACHIBK_GZIP -> {
                GZIPInputStream(input).use { gzip ->
                    val text = gzip.readBytes().toString(Charsets.UTF_8)
                    text.removePrefix(TACHIBK_MAGIC)
                }
            }
            BackupFormat.JSON_ZIP -> {
                var extracted: String? = null
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == ZIP_PAYLOAD_ENTRY || entry.name.endsWith(".json")) {
                            extracted = zip.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
                requireNotNull(extracted) { "Backup ZIP archive does not contain $ZIP_PAYLOAD_ENTRY" }
            }
        }
        return json.decodeFromString(rawJson)
    }

    fun encodeToBytes(payload: BackupPayload, format: BackupFormat): ByteArray {
        val baos = ByteArrayOutputStream()
        encode(payload, format, baos)
        return baos.toByteArray()
    }

    fun decodeFromBytes(bytes: ByteArray, format: BackupFormat): BackupPayload =
        decode(ByteArrayInputStream(bytes), format)

    fun filterForSelectiveRestore(
        payload: BackupPayload,
        options: SelectiveRestoreOptions,
    ): BackupPayload {
        val filteredSeries = if (options.restoreLibraryAndChapters) {
            payload.seriesEntries.map { entry ->
                entry.copy(
                    series = if (options.restoreAniListMappings) {
                        entry.series
                    } else {
                        entry.series.copy(anilistId = null, anilistScore = null)
                    },
                    categoryNames = if (options.restoreCategoriesAndTags) entry.categoryNames else emptyList(),
                    tagNames = if (options.restoreCategoriesAndTags) entry.tagNames else emptyList(),
                )
            }
        } else {
            emptyList()
        }

        return payload.copy(
            seriesEntries = filteredSeries,
            categories = if (options.restoreCategoriesAndTags) payload.categories else emptyList(),
            tags = if (options.restoreCategoriesAndTags) payload.tags else emptyList(),
            history = if (options.restoreHistory) payload.history else emptyList(),
            repos = if (options.restoreExtensionRepos) payload.repos else emptyList(),
            settings = if (options.restoreSettings) payload.settings else null,
        )
    }
}

@Singleton
class BackupManager @Inject constructor(
    private val database: TubiacDatabase,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun createSnapshot(): BackupPayload {
        val categories = database.categoryDao().getAll().map { it.toDomain() }
        val tags = database.tagDao().getAll().map { it.toDomain() }
        val categoryNameById = categories.associate { it.id to it.name }
        val tagNameById = tags.associate { it.id to it.name }

        val seriesEntries = database.seriesDao().getLibrarySnapshot().map { seriesEntity ->
            val chapters = database.chapterDao().getBySeries(seriesEntity.id).map { it.toDomain() }
            val catNames = database.categoryDao().getCategoryIdsForSeries(seriesEntity.id)
                .mapNotNull { categoryNameById[it] }
            val tagNames = database.tagDao().getTagIdsForSeries(seriesEntity.id)
                .mapNotNull { tagNameById[it] }
            BackupSeriesEntry(
                series = seriesEntity.toDomain(),
                chapters = chapters,
                categoryNames = catNames,
                tagNames = tagNames,
            )
        }

        return BackupPayload(
            createdAtEpochMs = System.currentTimeMillis(),
            seriesEntries = seriesEntries,
            categories = categories,
            tags = tags,
            history = database.historyDao().getAll().map { it.toDomain() },
            repos = database.extensionRepoDao().getAll().map { it.toDomain() },
            settings = settingsRepository.currentSettings(),
        )
    }

    suspend fun exportBackup(format: BackupFormat, output: OutputStream) {
        val snapshot = createSnapshot()
        BackupCodec.encode(snapshot, format, output)
    }

    suspend fun restoreBackup(
        input: InputStream,
        format: BackupFormat,
        options: SelectiveRestoreOptions,
    ) {
        val decoded = BackupCodec.decode(input, format)
        val filtered = BackupCodec.filterForSelectiveRestore(decoded, options)

        val categoryIdByName = mutableMapOf<String, Long>()
        if (options.restoreCategoriesAndTags) {
            filtered.categories.forEach { cat ->
                val id = database.categoryDao().upsert(cat.copy(id = 0L).toEntity())
                categoryIdByName[cat.name] = id
            }
        }

        val tagIdByName = mutableMapOf<String, Long>()
        if (options.restoreCategoriesAndTags) {
            filtered.tags.forEach { tag ->
                val id = database.tagDao().upsert(tag.copy(id = 0L).toEntity())
                tagIdByName[tag.name] = id
            }
        }

        if (options.restoreLibraryAndChapters) {
            filtered.seriesEntries.forEach { entry ->
                val seriesId = database.seriesDao().upsert(entry.series.copy(id = 0L).toEntity())
                val chapters = entry.chapters.map { ch ->
                    ch.copy(id = 0L, seriesId = seriesId).toEntity()
                }
                if (chapters.isNotEmpty()) {
                    database.chapterDao().upsertAll(chapters)
                }
                entry.categoryNames.forEach { name ->
                    categoryIdByName[name]?.let { catId ->
                        database.categoryDao().assignSeriesToCategory(SeriesCategoryCrossRef(seriesId, catId))
                    }
                }
                entry.tagNames.forEach { name ->
                    tagIdByName[name]?.let { tagId ->
                        database.tagDao().assignSeriesToTag(SeriesTagCrossRef(seriesId, tagId))
                    }
                }
            }
        }

        if (options.restoreHistory) {
            filtered.history.forEach { history ->
                database.historyDao().upsert(history.copy(id = 0L).toEntity())
            }
        }

        if (options.restoreExtensionRepos) {
            filtered.repos.forEach { repo ->
                database.extensionRepoDao().upsert(repo.copy(id = 0L).toEntity())
            }
        }

        if (options.restoreSettings) {
            filtered.settings?.let { restoredSettings ->
                settingsRepository.replaceSettings(restoredSettings)
            }
        }
    }

    suspend fun vacuumDatabase() {
        database.openHelper.writableDatabase.execSQL("VACUUM")
        database.openHelper.writableDatabase.execSQL("ANALYZE")
    }
}
