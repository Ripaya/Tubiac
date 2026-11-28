package tube.maniac

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.ExtensionNetworkBridge
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import tube.maniac.core.diagnostics.AutoIssueReporter
import tube.maniac.core.diagnostics.TubiacLogcat
import tube.maniac.core.extension.loader.ExtensionInstallBridge
import tube.maniac.core.extension.loader.SourceManager
import tube.maniac.core.network.NetworkRuntimeConfig
import uy.kohesive.injekt.registerInjekt
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class TubiacApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var networkConfig: NetworkRuntimeConfig

    @Inject
    lateinit var json: Json

    @Inject
    lateinit var sourceManager: SourceManager

    private val extensionPackageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
                -> sourceManager.reloadExtensions()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AutoIssueReporter.initialize(
            context = this,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            defaultRelayUrl = BuildConfig.ISSUE_RELAY_URL,
            defaultGitHubToken = BuildConfig.ISSUE_GITHUB_TOKEN,
            onPendingQueued = { AutoIssueReportWorker.enqueue(this) },
        )
        TubiacLogcat.i("TubiacApp", "Application started v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        // Populate Injekt registry eagerly before any extension classloader initialization runs
        ExtensionNetworkBridge.sharedClient = okHttpClient
        ExtensionNetworkBridge.userAgentProvider = { networkConfig.userAgent.get() }
        registerInjekt<Application>(this)
        registerInjekt<Context>(this)
        registerInjekt<OkHttpClient>(okHttpClient)
        registerInjekt<CookieJar>(okHttpClient.cookieJar)
        registerInjekt(NetworkHelper())
        registerInjekt(JavaScriptEngine(this))
        registerInjekt(json)

        ExtensionInstallBridge.onInstallSuccess = { sourceManager.reloadExtensions() }
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this,
            extensionPackageReceiver,
            pkgFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        if (AutoIssueReporter.state.value.pendingCount > 0) {
            AutoIssueReportWorker.enqueue(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .components {
                add(ZipEntryFetcher.Factory(this@TubiacApplication))
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .build()
}
