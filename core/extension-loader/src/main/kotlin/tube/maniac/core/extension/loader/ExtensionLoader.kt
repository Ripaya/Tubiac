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
import okhttp3.OkHttpClient
import tube.maniac.core.model.ExtensionInfo
import tube.maniac.core.model.ExtensionInstallState
import tube.maniac.core.network.NetworkRuntimeConfig
import uy.kohesive.injekt.Injekt
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedExtension(
    val info: ExtensionInfo,
    val sources: List<Source>,
    val loadError: String? = null,
)

/**
 * Discovers and dynamically loads installed Tachiyomi/Mihon extension APKs via
 * PackageManager metadata and PathClassLoader without requiring hardcoded parsers.
 */
@Singleton
class ExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    networkConfig: NetworkRuntimeConfig,
) {
    init {
        ExtensionNetworkBridge.sharedClient = okHttpClient
        ExtensionNetworkBridge.userAgentProvider = { networkConfig.userAgent.get() }
        (context.applicationContext as? Application)?.let { app ->
            Injekt.register<Application>(app)
        }
        Injekt.register<Context>(context.applicationContext ?: context)
        Injekt.register(NetworkHelper())
    }

    fun loadInstalledExtensions(): List<LoadedExtension> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        return packages
            .filter { isTachiyomiExtension(it) }
            .mapNotNull { loadSingleExtension(pm, it) }
    }

    private fun isTachiyomiExtension(pkgInfo: PackageInfo): Boolean {
        val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
        val hasMeta = pkgInfo.applicationInfo?.metaData?.containsKey(META_EXTENSION_CLASS) == true
        return hasFeature || hasMeta
    }

    private fun loadSingleExtension(pm: PackageManager, pkgInfo: PackageInfo): LoadedExtension? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val meta = appInfo.metaData ?: return null
        val classNamesRaw = meta.getString(META_EXTENSION_CLASS).orEmpty()
        if (classNamesRaw.isBlank()) return null

        val isNsfw = meta.getInt(META_EXTENSION_NSFW, 0) == 1
        val pkgName = pkgInfo.packageName
        val label = pm.getApplicationLabel(appInfo).toString()
            .removePrefix("Tachiyomi: ")
            .removePrefix("Mihon: ")
        val lang = extractLanguageFromPkg(pkgName)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        val classLoader = PathClassLoader(appInfo.sourceDir, appInfo.nativeLibraryDir, context.classLoader)
        val loadedSources = mutableListOf<Source>()
        val errors = mutableListOf<String>()

        classNamesRaw.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { rawName ->
            val fqcn = if (rawName.startsWith(".")) "$pkgName$rawName" else rawName
            runCatching {
                val clazz = Class.forName(fqcn, false, classLoader)
                when (val instance = clazz.getDeclaredConstructor().newInstance()) {
                    is Source -> loadedSources.add(instance)
                    is SourceFactory -> loadedSources.addAll(instance.createSources())
                }
            }.onFailure { err ->
                errors.add("$fqcn: ${err.javaClass.simpleName}: ${err.message ?: "unknown error"}")
            }
        }

        val info = ExtensionInfo(
            pkgName = pkgName,
            name = label,
            lang = lang,
            versionName = pkgInfo.versionName ?: "1.0.0",
            versionCode = versionCode,
            isNsfw = isNsfw,
            hasConfigurablePreferences = loadedSources.any { it is ConfigurableSource },
            installState = ExtensionInstallState.INSTALLED,
            installedVersionCode = versionCode,
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

    companion object {
        const val EXTENSION_FEATURE = "tachiyomi.extension"
        const val META_EXTENSION_CLASS = "tachiyomi.extension.class"
        const val META_EXTENSION_NSFW = "tachiyomi.extension.nsfw"
    }
}
