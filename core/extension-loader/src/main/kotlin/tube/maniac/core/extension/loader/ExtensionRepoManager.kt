package tube.maniac.core.extension.loader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import tube.maniac.core.database.dao.ExtensionRepoDao
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.TubiacLogcat
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionRepo
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

object ExtensionInstallBridge {
    @Volatile
    var onInstallSuccess: (() -> Unit)? = null

    @Volatile
    var lastInstallError: String? = null
}

data class RepoRefreshResult(
    val extensions: List<ExtensionInfo>,
    val errors: Map<String, String> = emptyMap(),
)

/**
 * Fetches and decodes Mihon v2 (`repo.json` -> `index.pb`) and legacy (`index.min.json`)
 * extension repositories, extracts APK binaries directly into isolated sandbox storage
 * (for reliable zero-friction execution) or hands them off to Android PackageInstaller.
 */
@Singleton
class ExtensionRepoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val repoDao: ExtensionRepoDao,
) {
    suspend fun fetchRepoExtensions(
        repo: ExtensionRepo,
        installedByPkg: Map<String, ExtensionInfo>,
    ): Result<List<ExtensionInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = RepoIndexDecoder.candidateIndexUrls(repo.indexUrl)
            require(candidates.isNotEmpty()) { "Invalid repository URL" }

            var decoded: DecodedRepoIndex? = null
            var lastError: Throwable? = null

            for (candidateUrl in candidates) {
                runCatching {
                    fetchCandidate(candidateUrl, repo, installedByPkg)
                }.onSuccess { result ->
                    if (!result.isLegacyDeprecationStub && result.entries.isNotEmpty()) {
                        decoded = result
                    } else if (decoded == null) {
                        decoded = result
                    }
                }.onFailure { err ->
                    lastError = err
                }
                val current = decoded
                if (current != null && !current.isLegacyDeprecationStub && current.entries.isNotEmpty()) {
                    break
                }
            }

            val finalIndex = decoded ?: throw (lastError ?: IllegalStateException("Repository unreachable"))
            repoDao.updateRefreshStatus(
                id = repo.id,
                timestamp = System.currentTimeMillis(),
                count = finalIndex.entries.size,
            )
            finalIndex.entries
        }
    }

    private fun fetchCandidate(
        url: String,
        repo: ExtensionRepo,
        installedByPkg: Map<String, ExtensionInfo>,
    ): DecodedRepoIndex {
        val request = Request.Builder().url(url).get().build()
        val rawBytes = okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code} for $url" }
            response.body.bytes()
        }

        val fallbackBase = url.substringBeforeLast("/")
        return when {
            url.endsWith("repo.json", ignoreCase = true) -> {
                val meta = json.decodeFromString<RepoMetaManifestDto>(rawBytes.toString(Charsets.UTF_8))
                val rawTarget = meta.indexV2 ?: meta.indexV1 ?: "$fallbackBase/index.pb"
                val targetIndex = when {
                    rawTarget.startsWith("http://") || rawTarget.startsWith("https://") -> rawTarget
                    rawTarget.startsWith("/") -> {
                        val schemeHost = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/")
                        "$schemeHost$rawTarget"
                    }
                    else -> "$fallbackBase/${rawTarget.trimStart('/')}"
                }
                val nested = fetchCandidate(targetIndex, repo, installedByPkg)
                nested.copy(
                    repoName = meta.meta?.name ?: nested.repoName,
                    signingFingerprint = meta.meta?.signingKeyFingerprint ?: nested.signingFingerprint,
                )
            }
            url.endsWith(".pb", ignoreCase = true) || isGzipOrBinaryPb(rawBytes) -> {
                RepoIndexDecoder.decodeProtobufIndex(rawBytes, fallbackBase, repo, installedByPkg)
            }
            else -> {
                RepoIndexDecoder.decodeJsonIndex(
                    json = json,
                    rawJson = rawBytes.toString(Charsets.UTF_8),
                    repoBaseUrl = fallbackBase,
                    repo = repo,
                    installedByPkg = installedByPkg,
                )
            }
        }
    }

    private fun isGzipOrBinaryPb(bytes: ByteArray): Boolean {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) return true
        val prefix = bytes.take(32).map { it.toInt().toChar() }.joinToString("").trimStart()
        return !prefix.startsWith("[") && !prefix.startsWith("{")
    }

    suspend fun refreshAllEnabledRepos(
        installedByPkg: Map<String, ExtensionInfo>,
    ): List<ExtensionInfo> = refreshAllEnabledReposWithErrors(installedByPkg).extensions

    suspend fun refreshAllEnabledReposWithErrors(
        installedByPkg: Map<String, ExtensionInfo>,
    ): RepoRefreshResult {
        val repos = repoDao.getAll().map { it.toDomain() }.filter { it.enabled }
        val results = mutableListOf<ExtensionInfo>()
        val errors = linkedMapOf<String, String>()
        repos.forEach { repo ->
            fetchRepoExtensions(repo, installedByPkg)
                .onSuccess { results.addAll(it) }
                .onFailure { err -> errors[repo.name] = err.message ?: "Failed to refresh ${repo.name}" }
        }
        return RepoRefreshResult(
            extensions = results.distinctBy { it.pkgName },
            errors = errors,
        )
    }

    /**
     * Downloads, validates, and extracts the extension APK directly into Tubiac's
     * read-only sandbox directory so PathClassLoader loads it immediately without OS sideload blocks.
     */
    suspend fun installExtensionApk(extension: ExtensionInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apkUrl = resolveApkUrl(extension)
            val bytes = okHttpClient.newCall(Request.Builder().url(apkUrl).get().build()).execute().use { response ->
                require(response.isSuccessful) { "APK download failed (HTTP ${response.code})" }
                response.body.bytes()
            }

            // Verify APK archive contains manifest & compiled dex before loading
            var hasDex = false
            var hasManifest = false
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "classes.dex" || entry.name.endsWith(".dex")) hasDex = true
                    if (entry.name == "AndroidManifest.xml") hasManifest = true
                    entry = zis.nextEntry
                }
            }
            require(hasDex && hasManifest) { "Downloaded package is not a valid extension APK" }

            val extDir = File(context.filesDir, "extensions").apply { mkdirs() }
            val tempFile = File(extDir, "${extension.pkgName}.apk.tmp")
            val finalFile = File(extDir, "${extension.pkgName}.apk")
            if (tempFile.exists()) {
                runCatching { tempFile.setWritable(true) }
                tempFile.delete()
            }
            if (finalFile.exists()) {
                runCatching { finalFile.setWritable(true) }
                finalFile.delete()
            }
            tempFile.writeBytes(bytes)
            // Mark read-only BEFORE renaming so Android 14+ PathClassLoader W^X policy accepts it atomically
            runCatching { tempFile.setReadOnly() }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
                runCatching { finalFile.setReadOnly() }
            }

            TubiacLogcat.i("ExtensionRepoManager", "Extracted ${extension.pkgName} (${bytes.size} bytes) to sandbox")
            ExtensionInstallBridge.lastInstallError = null
            ExtensionInstallBridge.onInstallSuccess?.invoke()
            Unit
        }.onFailure { err ->
            ExtensionInstallBridge.lastInstallError = err.message
            AutoIssueReporter.reportRuntimeBug(
                category = "extension-install",
                title = "[Extension] Sandbox extract failed for ${extension.pkgName}",
                detail = "apkUrl=${extension.apkUrl ?: extension.apkName}",
                throwable = err,
            )
        }
    }

    /** Optional OS-level system installation via Android PackageInstaller. */
    suspend fun installSystemApk(extension: ExtensionInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apkUrl = resolveApkUrl(extension)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(extension.pkgName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                okHttpClient.newCall(Request.Builder().url(apkUrl).get().build()).execute().use { response ->
                    require(response.isSuccessful) { "APK download failed (HTTP ${response.code})" }
                    session.openWrite("extension.apk", 0, -1).use { out ->
                        response.body.byteStream().copyTo(out)
                        session.fsync(out)
                    }
                }
                val callbackIntent = Intent("tube.maniac.EXTENSION_INSTALL_STATUS").apply {
                    setClassName(context.packageName, "tube.maniac.ExtensionInstallReceiver")
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        }
    }

    fun uninstallExtension(pkgName: String, isSandboxInstalled: Boolean) {
        val sandboxFile = File(File(context.filesDir, "extensions"), "$pkgName.apk")
        if (sandboxFile.exists()) {
            runCatching { sandboxFile.setWritable(true) }
            sandboxFile.delete()
            ExtensionInstallBridge.onInstallSuccess?.invoke()
        }
        if (!isSandboxInstalled) {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkgName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    fun requestUninstall(pkgName: String) {
        uninstallExtension(pkgName, isSandboxInstalled = false)
    }

    private fun resolveApkUrl(extension: ExtensionInfo): String {
        val rawApk = extension.apkUrl?.trim()
        val repoBaseUrl = extension.repoBaseUrl?.trimEnd('/')
        if (!rawApk.isNullOrBlank()) {
            when {
                rawApk.startsWith("http://") || rawApk.startsWith("https://") -> return rawApk
                rawApk.startsWith("/") && !repoBaseUrl.isNullOrBlank() -> {
                    val origin = repoBaseUrl.substringBefore("://") + "://" + repoBaseUrl.substringAfter("://").substringBefore("/")
                    return "$origin$rawApk"
                }
                rawApk.contains("/") && !repoBaseUrl.isNullOrBlank() -> {
                    return "$repoBaseUrl/${rawApk.trimStart('/')}"
                }
            }
        }
        val apkName = extension.apkName
        if (!apkName.isNullOrBlank() && (apkName.startsWith("http://") || apkName.startsWith("https://"))) {
            return apkName
        }
        val base = requireNotNull(extension.repoBaseUrl) { "Missing repository base URL" }.trimEnd('/')
        val name = requireNotNull(apkName ?: rawApk?.substringAfterLast("/")) { "Missing APK filename" }
        return "$base/apk/$name"
    }

    companion object {
        fun normalizeRepoIndexUrl(raw: String): String =
            RepoIndexDecoder.extractRepoInputUrl(raw).trimEnd('/')
    }
}
