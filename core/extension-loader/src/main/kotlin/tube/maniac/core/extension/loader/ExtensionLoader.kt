package tube.maniac.core.extension.loader

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.ExtensionNetworkBridge
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.network.NetworkRuntimeConfig
import uy.kohesive.injekt.registerInjekt
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedExtension(
    val info: ExtensionInfo,
    val sources: List<Source>,
    val loadError: String? = null,
)

/**
 * Discovers and dynamically loads BOTH:
 * 1. Sandbox-extracted extension APKs under files/extensions/<pkg>.apk (no OS sideload friction)
 * 2. System-installed Tachiyomi/Mihon extension packages via PackageManager
 */
@Singleton
class ExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    networkConfig: NetworkRuntimeConfig,
    json: Json,
) {
    init {
        ExtensionNetworkBridge.sharedClient = okHttpClient
        ExtensionNetworkBridge.userAgentProvider = { networkConfig.userAgent.get() }
        (context.applicationContext as? Application)?.let { app ->
            registerInjekt<Application>(app)
        }
        registerInjekt<Context>(context.applicationContext ?: context)
        registerInjekt(NetworkHelper())
        registerInjekt(json)
    }

    fun sandboxExtensionsDir(): File = File(context.filesDir, "extensions").apply { mkdirs() }

    fun loadInstalledExtensions(): List<LoadedExtension> {
        val pm = context.packageManager
        val byPkg = linkedMapOf<String, LoadedExtension>()

        // 1. Load sandbox-extracted APKs stored locally in files/extensions/
        sandboxExtensionsDir().listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
            ?.forEach { apkFile ->
                val loaded = loadSandboxApk(pm, apkFile)
                if (loaded != null) {
                    byPkg[loaded.info.pkgName] = loaded
                }
            }

        // 2. Load system-installed extension APKs (replacing sandbox only if newer versionCode)
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        packages
            .filter { isTachiyomiExtension(it) }
            .forEach { pkgInfo ->
                val loaded = loadPackageExtension(pm, pkgInfo, isSandbox = false) ?: return@forEach
                val existing = byPkg[loaded.info.pkgName]
                if (existing == null || loaded.info.versionCode > existing.info.versionCode) {
                    byPkg[loaded.info.pkgName] = loaded
                }
            }

        return byPkg.values.sortedBy { it.info.name.lowercase() }
    }

    private fun loadSandboxApk(pm: PackageManager, apkFile: File): LoadedExtension? {
        runCatching { apkFile.setReadOnly() } // Android 14+ PathClassLoader requires non-writable DEX container
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: return null

        pkgInfo.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
        }
        return loadPackageExtension(pm, pkgInfo, isSandbox = true)
    }

    private fun isTachiyomiExtension(pkgInfo: PackageInfo): Boolean {
        val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
        val hasMeta = pkgInfo.applicationInfo?.metaData?.containsKey(META_EXTENSION_CLASS) == true
        return hasFeature || hasMeta
    }

    private fun loadPackageExtension(
        pm: PackageManager,
        pkgInfo: PackageInfo,
        isSandbox: Boolean,
    ): LoadedExtension? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val meta = appInfo.metaData ?: return null
        val classNamesRaw = meta.getString(META_EXTENSION_CLASS).orEmpty()
        if (classNamesRaw.isBlank()) return null

        val isNsfw = meta.getInt(META_EXTENSION_NSFW, 0) == 1 ||
            meta.getInt(META_CONTENT_WARNING, 0) >= 3
        val pkgName = pkgInfo.packageName
        val label = meta.getString(META_TACHIYOMIX_NAME)?.takeIf { it.isNotBlank() }
            ?: runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkgName)
                .removePrefix("Tachiyomi: ")
                .removePrefix("Mihon: ")
        val lang = extractLanguageFromPkg(pkgName)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
        val versionName = pkgInfo.versionName ?: "1.0.0"
        val libVersion = extractLibVersion(meta, versionName)

        val apkPath = appInfo.sourceDir ?: return null
        val nativeLibDir = appInfo.nativeLibraryDir
        val loadedSources = mutableListOf<Source>()
        val errors = mutableListOf<String>()

        if (libVersion != null && !isSupportedLibVersion(libVersion)) {
            errors.add("Unsupported extension libVersion $libVersion (expected $MIN_LIB_VERSION..$MAX_LIB_VERSION)")
        } else {
            runCatching {
                val parentLoader = context.applicationContext?.classLoader ?: context.classLoader
                val classLoader = PathClassLoader(apkPath, nativeLibDir, parentLoader)
                classNamesRaw.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { rawName ->
                        val fqcn = if (rawName.startsWith(".")) "$pkgName$rawName" else rawName
                        try {
                            val clazz = Class.forName(fqcn, false, classLoader)
                            when (val instance = clazz.getDeclaredConstructor().newInstance()) {
                                is Source -> loadedSources.add(instance)
                                is SourceFactory -> loadedSources.addAll(instance.createSources())
                                else -> errors.add("$fqcn: Unknown extension entry type ${instance.javaClass.name}")
                            }
                        } catch (err: Throwable) {
                            val cause = err.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
                            errors.add("$fqcn: ${err.javaClass.simpleName}: ${err.message ?: ""} $cause".trim())
                        }
                    }
            }.onFailure { err ->
                errors.add("ClassLoader: ${err.javaClass.simpleName}: ${err.message ?: err.javaClass.simpleName}")
            }
        }

        val info = ExtensionInfo(
            pkgName = pkgName,
            name = label,
            lang = lang,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            isNsfw = isNsfw,
            hasConfigurablePreferences = loadedSources.any { it is ConfigurableSource },
            installState = ExtensionInstallState.INSTALLED,
            installedVersionCode = versionCode,
            apkPath = apkPath,
            isSandboxInstalled = isSandbox,
        )
        return LoadedExtension(
            info = info,
            sources = loadedSources,
            loadError = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private fun extractLanguageFromPkg(pkgName: String): String {
        val parts = pkgName.split(".")
        return if (parts.size >= 4) parts[3] else "all"
    }

    private fun extractLibVersion(meta: android.os.Bundle, versionName: String): String? {
        val explicit = meta.getString(META_EXTENSION_LIB)?.takeIf { it.isNotBlank() }
            ?: meta.getString(META_TACHIYOMIX_LIB)?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val segments = versionName.split(".")
        return if (segments.size >= 2) "${segments[0]}.${segments[1]}" else null
    }

    private fun isSupportedLibVersion(rawVersion: String): Boolean {
        val parts = rawVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val value = major + (minor / 10.0)
        return value in MIN_LIB_VERSION..MAX_LIB_VERSION
    }

    companion object {
        const val EXTENSION_FEATURE = "tachiyomi.extension"
        const val META_EXTENSION_CLASS = "tachiyomi.extension.class"
        const val META_EXTENSION_NSFW = "tachiyomi.extension.nsfw"
        const val META_EXTENSION_LIB = "tachiyomi.extension.lib"
        const val META_TACHIYOMIX_LIB = "tachiyomix.extensionLib"
        const val META_CONTENT_WARNING = "tachiyomix.contentWarning"
        const val META_TACHIYOMIX_NAME = "tachiyomix.name"
        const val MIN_LIB_VERSION = 1.3
        const val MAX_LIB_VERSION = 1.6
    }
}
