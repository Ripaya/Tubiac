package tube.maniac

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import tube.maniac.core.diagnostics.AutoIssueReporter

/**
 * Background worker that uploads pending deduplicated crash/logcat/extension-error
 * reports automatically when network connectivity is available.
 */
@HiltWorker
class AutoIssueReportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AutoIssueReporter.flushPendingReports(okHttpClient)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "tubiac_auto_issue_reporter"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<AutoIssueReportWorker>()
                .setConstraints(constraints)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
            }
        }
    }
}
