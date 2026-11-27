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
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import tube.maniac.core.extension.loader.ExtensionInstallBridge
import tube.maniac.core.extension.loader.SourceManager
import javax.inject.Inject

@HiltAndroidApp
class TubiacApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ZipEntryFetcher.Factory(this@TubiacApplication))
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .build()
}
