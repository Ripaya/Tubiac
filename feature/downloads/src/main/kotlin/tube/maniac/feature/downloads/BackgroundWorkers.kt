package tube.maniac.feature.downloads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import tube.maniac.core.database.BackupFormat
import tube.maniac.core.database.BackupManager
import tube.maniac.core.database.SettingsRepository
import tube.maniac.core.database.dao.ChapterDao
import tube.maniac.core.database.dao.DownloadDao
import tube.maniac.core.database.dao.SeriesDao
import tube.maniac.core.database.dao.UpdateErrorDao
import tube.maniac.core.database.entity.ChapterEntity
import tube.maniac.core.database.entity.UpdateErrorEntity
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.model.AutoBackupFrequency
import tube.maniac.core.model.DownloadState
import tube.maniac.core.model.DownloadStorageFormat
import tube.maniac.core.model.PublicationStatus
import tube.maniac.core.network.CloudflareChallengeException
import tube.maniac.core.network.IpBlockedException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * WorkManager-backed concurrent chapter downloader supporting configurable page concurrency
 * (default 5), SHA-256 unique filename disambiguation, Wi-Fi constraints, and CBZ / raw folder output.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val chapterDao: ChapterDao,
    private val sourceManager: SourceManager,
    private val okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.currentSettings()
        val semaphore = Semaphore(settings.concurrentPageDownloads.coerceIn(1, 10))
        val pending = downloadDao.getPendingDownloads()

        for (task in pending) {
            val source = sourceManager.getSource(task.sourceId) ?: run {
                downloadDao.updateState(task.id, DownloadState.FAILED, "Source not installed")
                continue
            }
            val chapter = chapterDao.getById(task.chapterId) ?: continue
            downloadDao.updateState(task.id, DownloadState.DOWNLOADING)

            runCatching {
                val pages = source.getPageList(
                    SChapter.create().apply {
                        url = chapter.url
                        name = chapter.name
                    },
                )
                val total = pages.size
                val doneCount = AtomicInteger(0)
                downloadDao.updateProgress(task.id, 0, total, DownloadState.DOWNLOADING)

                val chapterHash = sha256Short("${task.sourceId}_${task.seriesId}_${task.chapterId}")
                val outputRoot = File(applicationContext.getExternalFilesDir("downloads"), chapterHash).apply {
                    mkdirs()
                }

                val downloadedFiles = coroutineScope {
                    pages.mapIndexed { idx, page ->
                        async {
                            semaphore.withPermit {
                                val url = page.imageUrl ?: page.url
                                val file = File(outputRoot, String.format(java.util.Locale.US, "%04d_%s.jpg", idx, sha256Short(url)))
                                okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                                    if (!resp.isSuccessful) error("HTTP ${resp.code} fetching page ${idx + 1}")
                                    resp.body?.byteStream()?.use { input ->
                                        FileOutputStream(file).use { out -> input.copyTo(out) }
                                    }
                                }
                                downloadDao.updateProgress(
                                    task.id,
                                    doneCount.incrementAndGet(),
                                    total,
                                    DownloadState.DOWNLOADING,
                                )
                                file
                            }
                        }
                    }.awaitAll()
                }

                if (settings.downloadStorageFormat == DownloadStorageFormat.CBZ_ARCHIVE) {
                    val cbzFile = File(applicationContext.getExternalFilesDir("downloads"), "$chapterHash.cbz")
                    ZipOutputStream(FileOutputStream(cbzFile)).use { zos ->
                        downloadedFiles.sortedBy { it.name }.forEach { img ->
                            zos.putNextEntry(ZipEntry(img.name))
                            img.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    outputRoot.deleteRecursively()
                }

                chapterDao.setDownloaded(task.chapterId, true)
                downloadDao.updateState(task.id, DownloadState.COMPLETED)
            }.onFailure { err ->
                val message = when (err) {
                    is CloudflareChallengeException -> "Cloudflare challenge — open in WebView"
                    is IpBlockedException -> "Blocked by site firewall (HTTP ${err.statusCode}) — switch network/VPN or check WebView"
                    else -> err.message ?: "Download error"
                }
                downloadDao.updateState(task.id, DownloadState.FAILED, message)
            }
        }
        return Result.success()
    }

    private fun sha256Short(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val WORK_NAME = "tubiac_download_queue"

        fun enqueue(context: Context, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Smart background library updater honoring skip-completed and unread-cap rules,
 * capturing Cloudflare or source errors into UpdateErrorDao for 1-tap recovery.
 */
@HiltWorker
class LibraryUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val seriesDao: SeriesDao,
    private val chapterDao: ChapterDao,
    private val updateErrorDao: UpdateErrorDao,
    private val sourceManager: SourceManager,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.currentSettings()
        val library = seriesDao.getLibrarySnapshot()

        for (series in library) {
            if (settings.skipCompletedOnUpdate && series.publicationStatus == PublicationStatus.COMPLETED) {
                continue
            }
            if (settings.unreadUpdateCap > 0 && chapterDao.countUnread(series.id) >= settings.unreadUpdateCap) {
                continue
            }
            val source = sourceManager.getSource(series.sourceId) ?: continue
            runCatching {
                val remoteChapters = source.getChapterList(
                    SManga.create().apply {
                        url = series.url
                        title = series.title
                    },
                )
                val existingUrls = chapterDao.getBySeries(series.id).map { it.url }.toSet()
                val newEntities = remoteChapters
                    .filterNot { it.url in existingUrls }
                    .mapIndexed { idx, ch ->
                        ChapterEntity(
                            seriesId = series.id,
                            url = ch.url,
                            name = ch.name,
                            chapterNumber = ch.chapter_number,
                            scanlator = ch.scanlator,
                            dateUpload = ch.date_upload,
                            dateFetch = System.currentTimeMillis(),
                            sourceOrder = idx,
                        )
                    }
                if (newEntities.isNotEmpty()) {
                    chapterDao.upsertAll(newEntities)
                }
                updateErrorDao.deleteBySeries(series.id)
            }.onFailure { err ->
                updateErrorDao.upsert(
                    UpdateErrorEntity(
                        seriesId = series.id,
                        seriesTitle = series.title,
                        sourceId = series.sourceId,
                        sourceName = source.name,
                        seriesUrl = series.url,
                        reason = err.message ?: "Could not reach source",
                        isCloudflare = err is CloudflareChallengeException || err is IpBlockedException,
                        occurredAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return Result.success()
    }
}

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.currentSettings()
        if (settings.autoBackupFrequency == AutoBackupFrequency.OFF) return Result.success()

        val backupDir = File(applicationContext.getExternalFilesDir("backups"), "auto").apply { mkdirs() }
        val file = File(backupDir, "tubiac_${System.currentTimeMillis()}.tachibk")
        FileOutputStream(file).use { out ->
            backupManager.exportBackup(BackupFormat.TACHIBK_GZIP, out)
        }

        val existing = backupDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        existing.drop(settings.autoBackupMaxFiles.coerceAtLeast(1)).forEach { it.delete() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "tubiac_auto_backup"

        fun schedule(context: Context, frequency: AutoBackupFrequency) {
            val wm = WorkManager.getInstance(context)
            if (frequency == AutoBackupFrequency.OFF) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val intervalDays = if (frequency == AutoBackupFrequency.WEEKLY) 7L else 1L
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays, TimeUnit.DAYS).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
