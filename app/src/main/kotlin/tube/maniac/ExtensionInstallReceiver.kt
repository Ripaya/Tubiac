package tube.maniac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import tube.maniac.core.extension.loader.ExtensionInstallBridge

class ExtensionInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    runCatching { context.startActivity(it) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                ExtensionInstallBridge.lastInstallError = null
                ExtensionInstallBridge.onInstallSuccess?.invoke()
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ExtensionInstallBridge.lastInstallError = msg ?: "Install status code $status"
            }
        }
    }
}
