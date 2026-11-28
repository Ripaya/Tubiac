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
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.TubiacLogcat
import tube.maniac.core.diagnostics.unwrapRoot
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.network.NetworkRuntimeConfig
import uy.kohesive.injekt.registerInjekt
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedExtension(
    val info: ExtensionInfo,
    val sources: List<Source>,
    val loadError: String? = null,
)

/**
 * Child-first ClassLoader modeled after Mihon's extension isolation:
 * delegates shared host API, Android, Kotlin, and networking classes to the parent
 * so runtime interface/instance checks (`is Source`, `OkHttpClient`, `Injekt`) share identical
 * Class identities, while loading bundled helper/multisrc/extension classes from the extension APK first.
 */
internal class ChildFirstPathClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }
        if (shouldDelegateToParent(name)) {
            return super.loadClass(name, resolve)
        }
        return try {
            findClass(name).also { if (resolve) resolveClass(it) }
        } catch (_: ClassNotFoundException) {
            super.loadClass(name, resolve)
        }
    }

    private fun shouldDelegateToParent(name: String): Boolean =
        PARENT_FIRST_PREFIXES.any { prefix -> name.startsWith(prefix) }

    companion object {
        private val PARENT_FIRST_PREFIXES = listOf(
            "java.",
            "javax.",
            "sun.",
            "dalvik.",
            "android.",
            "androidx.",
            "kotlin.",
            "kotlinx.coroutines.",
            "kotlinx.serialization.",
            "eu.kanade.tachiyomi.AppInfo",
            "eu.kanade.tachiyomi.source.",
            "eu.kanade.tachiyomi.network.",
            "eu.kanade.tachiyomi.util.",
            "uy.kohesive.injekt.",
            "rx.",
            "okhttp3.",
            "okio.",
            "org.jsoup.",
            "tube.maniac.",
        )
    }
}

/**
 * Discovers and dynamically loads BOTH:
 * 1. Sandbox-extracted extension APKs under files/extensions/<pkg>.apk (zero OS sideload friction)
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
        registerInjekt<OkHttpClient>(okHttpClient)
        registerInjekt<CookieJar>(okHttpClient.cookieJar)
        registerInjekt(NetworkHelper())
        registerInjekt(json)
    }

    fun sandboxExtensionsDir(): File = File(context.filesDir, "extensions").apply { mkdirs() }

    private fun nativeLibsDir(pkgName: String): File =
        File(File(context.filesDir, "extensions_native"), pkgName).apply { mkdirs() }

    @Synchronized
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

        // 2. Load system-installed extension APKs (preferring system install if equal/newer versionCode or sandbox errored)
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        val packages: List<PackageInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }
        }.getOrDefault(emptyList())

        packages
            .filter { isTachiyomiExtension(it) }
            .forEach { pkgInfo ->
                val loaded = loadPackageExtension(pm, pkgInfo, isSandbox = false) ?: return@forEach
                val existing = byPkg[loaded.info.pkgName]
                if (existing == null ||
                    loaded.info.versionCode > existing.info.versionCode ||
                    (existing.sources.isEmpty() && loaded.sources.isNotEmpty())
                ) {
                    byPkg[loaded.info.pkgName] = loaded
                }
            }

        val list = byPkg.values.sortedBy { it.info.name.lowercase() }
        TubiacLogcat.i("ExtensionLoader", "Loaded ${list.size} extension(s), ${list.sumOf { it.sources.size }} active source(s)")
        return list
    }

    private fun loadSandboxApk(pm: PackageManager, apkFile: File): LoadedExtension? {
        runCatching { apkFile.setReadOnly() } // Android 14+ requires non-writable DEX container
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
        }.getOrNull()

        if (pkgInfo == null) {
            val fallbackPkg = apkFile.nameWithoutExtension
            val errMsg = "Android PackageManager could not inspect ${apkFile.name}; APK may be incomplete or require System Install."
            AutoIssueReporter.reportRuntimeBug("extension-load", "Extension APK parse failed: $fallbackPkg", errMsg)
            return LoadedExtension(
                info = ExtensionInfo(
                    pkgName = fallbackPkg,
                    name = fallbackPkg.substringAfterLast(".").replaceFirstChar { it.uppercase() },
                    lang = extractLanguageFromPkg(fallbackPkg),
                    versionName = "unknown",
                    versionCode = 0L,
                    installState = ExtensionInstallState.INSTALLED,
                    isSandboxInstalled = true,
                ),
                sources = emptyList(),
                loadError = errMsg,
            )
        }

        val extractedNativeDir = extractNativeLibs(apkFile, pkgInfo.packageName)
        pkgInfo.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
            if (extractedNativeDir != null) {
                nativeLibraryDir = extractedNativeDir.absolutePath
            }
        }
        return loadPackageExtension(pm, pkgInfo, isSandbox = true, explicitNativeDir = extractedNativeDir?.absolutePath)
    }

    private fun extractNativeLibs(apkFile: File, pkgName: String): File? {
        val outDir = nativeLibsDir(pkgName)
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        if (supportedAbis.isEmpty()) return null
        var extractedAny = false
        runCatching {
            val entriesByAbi = linkedMapOf<String, MutableList<Pair<String, ByteArray>>>()
            ZipInputStream(apkFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.startsWith("lib/") && name.endsWith(".so") && !name.contains("..")) {
                        val parts = name.split("/")
                        if (parts.size == 3) {
                            val abi = parts[1]
                            val soName = parts[2]
                            entriesByAbi.getOrPut(abi) { mutableListOf() }.add(soName to zis.readBytes())
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            val chosenAbi = supportedAbis.firstOrNull { entriesByAbi.containsKey(it) }
            if (chosenAbi != null) {
                entriesByAbi[chosenAbi].orEmpty().forEach { (soName, bytes) ->
                    val target = File(outDir, soName)
                    if (!target.exists() || target.length() != bytes.size.toLong()) {
                        target.writeBytes(bytes)
                    }
                    extractedAny = true
                }
            }
        }.onFailure { err ->
            TubiacLogcat.w("ExtensionLoader", "Native library extraction skipped for $pkgName: ${err.message}")
        }
        return if (extractedAny || outDir.listFiles()?.isNotEmpty() == true) outDir else null
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
        explicitNativeDir: String? = null,
    ): LoadedExtension? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val pkgName = pkgInfo.packageName
        val meta = appInfo.metaData
        val classNamesRaw = meta?.getString(META_EXTENSION_CLASS).orEmpty()

        val isNsfw = meta?.let {
            it.getInt(META_EXTENSION_NSFW, 0) == 1 || it.getInt(META_CONTENT_WARNING, 0) >= 3
        } ?: false

        val label = meta?.getString(META_TACHIYOMIX_NAME)?.takeIf { it.isNotBlank() }
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

        if (classNamesRaw.isBlank()) {
            val errMsg = "Missing `$META_EXTENSION_CLASS` metadata in $pkgName"
            AutoIssueReporter.reportRuntimeBug("extension-load", "Extension metadata missing: $label", errMsg)
            return LoadedExtension(
                info = ExtensionInfo(
                    pkgName = pkgName,
                    name = label,
                    lang = lang,
                    versionName = versionName,
                    versionCode = versionCode,
                    libVersion = libVersion,
                    isNsfw = isNsfw,
                    installState = ExtensionInstallState.INSTALLED,
                    installedVersionCode = versionCode,
                    isSandboxInstalled = isSandbox,
                ),
                sources = emptyList(),
                loadError = errMsg,
            )
        }

        val apkPath = appInfo.sourceDir ?: return null
        val nativeLibDir = explicitNativeDir ?: appInfo.nativeLibraryDir
        val loadedSources = mutableListOf<Source>()
        val errors = mutableListOf<String>()

        try {
            val parentLoader = context.applicationContext?.classLoader ?: context.classLoader
            val classLoader = ChildFirstPathClassLoader(apkPath, nativeLibDir, parentLoader)
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
                        val root = err.unwrapRoot()
                        val msg = "$fqcn: ${root.javaClass.simpleName}: ${root.message ?: "instantiation failed"}"
                        errors.add(msg)
                        AutoIssueReporter.reportRuntimeBug(
                            category = "extension-load",
                            title = "[Extension] Failed loading $label ($pkgName)",
                            detail = "libVersion=$libVersion, version=$versionName ($versionCode), sandbox=$isSandbox\n$msg",
                            throwable = root,
                        )
                    }
                }
        } catch (err: Throwable) {
            val root = err.unwrapRoot()
            val msg = "ClassLoader: ${root.javaClass.simpleName}: ${root.message ?: root.javaClass.simpleName}"
            errors.add(msg)
            AutoIssueReporter.reportRuntimeBug(
                category = "extension-load",
                title = "[Extension] ClassLoader failure for $label ($pkgName)",
                detail = "libVersion=$libVersion, apkPath=$apkPath, sandbox=$isSandbox\n$msg",
                throwable = root,
            )
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

    private fun extractLibVersion(meta: android.os.Bundle?, versionName: String): String? {
        val explicit = meta?.getString(META_EXTENSION_LIB)?.takeIf { it.isNotBlank() }
            ?: meta?.getString(META_TACHIYOMIX_LIB)?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val segments = versionName.split(".")
        return if (segments.size >= 2) "${segments[0]}.${segments[1]}" else null
    }

    companion object {
        const val EXTENSION_FEATURE = "tachiyomi.extension"
        const val META_EXTENSION_CLASS = "tachiyomi.extension.class"
        const val META_EXTENSION_NSFW = "tachiyomi.extension.nsfw"
        const val META_EXTENSION_LIB = "tachiyomi.extension.lib"
        const val META_TACHIYOMIX_LIB = "tachiyomix.extensionLib"
        const val META_CONTENT_WARNING = "tachiyomix.contentWarning"
        const val META_TACHIYOMIX_NAME = "tachiyomix.name"
    }
}
