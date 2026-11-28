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
import eu.kanade.tachiyomi.source.online.HttpSource
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
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.unwrapRoot
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

                val downloadsDir = (applicationContext.getExternalFilesDir("downloads")
                    ?: File(applicationContext.filesDir, "downloads")).apply {
                    mkdirs()
                    runCatching { File(this, ".nomedia").apply { if (!exists()) createNewFile() } }
                }
                val chapterHash = sha256Short("${task.sourceId}_${task.seriesId}_${task.chapterId}")
                val outputRoot = File(downloadsDir, chapterHash).apply {
                    mkdirs()
                }

                val downloadedFiles = coroutineScope {
                    pages.mapIndexed { idx, page ->
                        async {
                            semaphore.withPermit {
                                val httpSource = source as? HttpSource
                                if (page.imageUrl.isNullOrBlank() && httpSource != null && page.url.isNotBlank()) {
                                    page.imageUrl = httpSource.getImageUrl(page)
                                }
                                val url = page.imageUrl ?: page.url
                                val pageRequest = httpSource?.let { runCatching { it.imageRequest(page) }.getOrNull() }
                                    ?: Request.Builder().url(url).get().build()
                                val finalFile = okHttpClient.newCall(pageRequest).execute().use { resp ->
                                    if (!resp.isSuccessful) error("HTTP ${resp.code} fetching page ${idx + 1}")
                                    val ext = extensionFromMime(resp.header("Content-Type"), url)
                                    val targetFile = File(outputRoot, String.format(java.util.Locale.US, "%04d_%s.%s", idx, sha256Short(url), ext))
                                    val tmpFile = File(outputRoot, "${targetFile.name}.tmp")
                                    resp.body.byteStream().use { input ->
                                        FileOutputStream(tmpFile).use { out -> input.copyTo(out) }
                                    }
                                    if (targetFile.exists()) targetFile.delete()
                                    if (!tmpFile.renameTo(targetFile)) {
                                        tmpFile.copyTo(targetFile, overwrite = true)
                                        tmpFile.delete()
                                    }
                                    targetFile
                                }
                                downloadDao.updateProgress(
                                    task.id,
                                    doneCount.incrementAndGet(),
                                    total,
                                    DownloadState.DOWNLOADING,
                                )
                                finalFile
                            }
                        }
                    }.awaitAll()
                }

                if (settings.downloadStorageFormat == DownloadStorageFormat.CBZ_ARCHIVE) {
                    val cbzFile = File(downloadsDir, "$chapterHash.cbz")
                    val tmpCbz = File(downloadsDir, "$chapterHash.cbz.tmp")
                    try {
                        ZipOutputStream(FileOutputStream(tmpCbz)).use { zos ->
                            downloadedFiles.sortedBy { it.name }.forEach { img ->
                                zos.putNextEntry(ZipEntry(img.name))
                                img.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        if (cbzFile.exists()) cbzFile.delete()
                        if (!tmpCbz.renameTo(cbzFile)) {
                            tmpCbz.copyTo(cbzFile, overwrite = true)
                            tmpCbz.delete()
                        }
                        outputRoot.deleteRecursively()
                    } catch (err: Throwable) {
                        tmpCbz.delete()
                        throw err
                    }
                }

                chapterDao.setDownloaded(task.chapterId, true)
                downloadDao.updateState(task.id, DownloadState.COMPLETED)
            }.onFailure { err ->
                val root = err.unwrapRoot()
                val message = when (root) {
                    is CloudflareChallengeException -> "Cloudflare challenge — open in WebView"
                    is IpBlockedException -> "Blocked by site firewall (HTTP ${root.statusCode}) — switch network/VPN or check WebView"
                    else -> {
                        if (root !is kotlinx.coroutines.CancellationException) {
                            AutoIssueReporter.reportRuntimeBug(
                                category = "download-worker",
                                title = "[Download] Failed chapter ${chapter.name}",
                                detail = "seriesId=${task.seriesId}, chapterId=${task.chapterId}, sourceId=${task.sourceId}",
                                throwable = root,
                            )
                        }
                        root.message ?: "Download error"
                    }
                }
                downloadDao.updateState(task.id, DownloadState.FAILED, message)
            }
        }
        return Result.success()
    }

    private fun extensionFromMime(contentType: String?, url: String): String {
        val mime = contentType.orEmpty().lowercase()
        return when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("avif") -> "avif"
            mime.contains("gif") -> "gif"
            url.substringBefore('?').endsWith(".png", ignoreCase = true) -> "png"
            url.substringBefore('?').endsWith(".webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
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
                val sManga = SManga.create().apply {
                    url = series.url
                    title = series.title
                }
                val existingEntities = chapterDao.getBySeries(series.id)
                val existingSChapters = existingEntities.map { ch ->
                    SChapter.create().apply {
                        url = ch.url
                        name = ch.name
                        chapter_number = ch.chapterNumber
                        scanlator = ch.scanlator
                        date_upload = ch.dateUpload
                    }
                }
                val update = source.getMangaUpdate(
                    manga = sManga,
                    chapters = existingSChapters,
                    fetchDetails = false,
                    fetchChapters = true,
                )
                val remoteChapters = update.chapters()
                val existingUrls = existingEntities.map { it.url }.toSet()
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
                val root = err.unwrapRoot()
                val isBlocked = root is CloudflareChallengeException || root is IpBlockedException
                if (!isBlocked && root !is kotlinx.coroutines.CancellationException) {
                    AutoIssueReporter.reportRuntimeBug(
                        category = "library-update",
                        title = "[Update] Failed checking ${series.title} (${source.name})",
                        detail = "seriesUrl=${series.url}",
                        throwable = root,
                    )
                }
                updateErrorDao.upsert(
                    UpdateErrorEntity(
                        seriesId = series.id,
                        seriesTitle = series.title,
                        sourceId = series.sourceId,
                        sourceName = source.name,
                        seriesUrl = series.url,
                        reason = root.message ?: "Could not reach source",
                        isCloudflare = isBlocked,
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

        val backupsBase = applicationContext.getExternalFilesDir("backups")
            ?: File(applicationContext.filesDir, "backups")
        val backupDir = File(backupsBase, "auto").apply { mkdirs() }
        val targetFile = File(backupDir, "tubiac_${System.currentTimeMillis()}.tachibk")
        val tmpFile = File(backupDir, "${targetFile.name}.tmp")
        try {
            FileOutputStream(tmpFile).use { out ->
                backupManager.exportBackup(BackupFormat.TACHIBK_GZIP, out)
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (err: Throwable) {
            tmpFile.delete()
            throw err
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
