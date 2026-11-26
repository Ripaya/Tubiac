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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import tube.maniac.core.database.dao.ExtensionRepoDao
import tube.maniac.core.database.entity.toDomain
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.model.ExtensionRepo
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RepoIndexEntryDto(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String = "all",
    val code: Long = 1L,
    val version: String = "1.0.0",
    val nsfw: Int = 0,
    @SerialName("sources") val sourcesCount: List<RepoSourceDto> = emptyList(),
)

@Serializable
data class RepoSourceDto(
    val name: String,
    val lang: String = "all",
    val id: String = "",
    val baseUrl: String = "",
)

data class RepoRefreshResult(
    val extensions: List<ExtensionInfo>,
    val errors: Map<String, String> = emptyMap(),
)

/**
 * Fetches and refreshes Tachiyomi/Mihon index.min.json third-party repository indices
 * and handles APK lifecycle operations via Android PackageInstaller Session APIs.
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
            val request = Request.Builder().url(repo.indexUrl).get().build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Repository returned HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }
            val entries = json.decodeFromString<List<RepoIndexEntryDto>>(body)
            val baseUrl = repo.indexUrl.substringBeforeLast("/")

            repoDao.updateRefreshStatus(
                id = repo.id,
                timestamp = System.currentTimeMillis(),
                count = entries.size,
            )

            entries.map { dto ->
                val installed = installedByPkg[dto.pkg]
                val state = when {
                    !repo.trusted -> ExtensionInstallState.UNTRUSTED
                    installed == null -> ExtensionInstallState.AVAILABLE
                    dto.code > installed.versionCode -> ExtensionInstallState.UPDATE_AVAILABLE
                    else -> ExtensionInstallState.INSTALLED
                }
                ExtensionInfo(
                    pkgName = dto.pkg,
                    name = dto.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: "),
                    lang = dto.lang,
                    versionName = dto.version,
                    versionCode = dto.code,
                    isNsfw = dto.nsfw == 1,
                    apkName = dto.apk,
                    iconUrl = "$baseUrl/icon/${dto.pkg}.png",
                    repoBaseUrl = baseUrl,
                    installState = state,
                    installedVersionCode = installed?.versionCode,
                )
            }
        }
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
     * Streams a remote extension APK directly into an Android PackageInstaller session.
     */
    suspend fun installExtensionApk(extension: ExtensionInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = requireNotNull(extension.repoBaseUrl) { "Missing repository base URL" }
            val apkName = requireNotNull(extension.apkName) { "Missing APK filename" }
            val apkUrl = if (apkName.startsWith("http://") || apkName.startsWith("https://")) {
                apkName
            } else {
                "$base/apk/$apkName"
            }

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
                    val stream = requireNotNull(response.body?.byteStream()) { "Empty APK response" }
                    session.openWrite("extension.apk", 0, -1).use { out ->
                        stream.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callbackIntent = Intent("tube.maniac.EXTENSION_INSTALL_STATUS").setPackage(context.packageName)
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

    fun requestUninstall(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkgName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
