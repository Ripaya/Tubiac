package tube.maniac

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
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
                "tube.maniac.EXTENSION_INSTALL_STATUS" -> {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                            runCatching { startActivity(it) }
                        }
                    } else if (status == PackageInstaller.STATUS_SUCCESS) {
                        sourceManager.reloadExtensions()
                    }
                }

                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
                -> sourceManager.reloadExtensions()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            extensionPackageReceiver,
            IntentFilter("tube.maniac.EXTENSION_INSTALL_STATUS"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
