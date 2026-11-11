package app.fetchflow

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object ScheduledTransferLauncher {
    private fun workName(id: Long) = "fetchflow-scheduled-$id"

    fun enqueue(context: Context, item: DownloadItem) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (item.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ScheduledTransferWorker>()
            .setInputData(workDataOf(ScheduledTransferWorker.KEY_ID to item.id))
            .setInitialDelay((item.scheduledAt - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag("fetchflow-scheduled")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(workName(item.id), androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
    }
}

class ScheduledTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id <= 0L) return Result.failure()
        val store = DownloadStore.get(applicationContext)
        val item = store.item(id) ?: return Result.failure()
        if (item.state == "paused" || item.state == "cancelled" || item.state == "complete") return Result.success()
        store.update(id) { if (it.state == "scheduled") it.copy(state = "queued", reason = "Scheduled time reached; waiting for a download slot") else it }
        val ready = store.item(id) ?: return Result.failure()
        if (ready.state == "paused" || ready.state == "cancelled") return Result.success()
        DownloadConcurrency.configure(applicationContext.getSharedPreferences("fetchflow.preferences", Context.MODE_PRIVATE).getInt("max_active_downloads", 2))
        TransferNotifications.ensureChannel(applicationContext)
        DownloadConcurrency.withSlot {
            setForeground(foregroundInfo(ready))
            TransferEngine(applicationContext).execute(id) { updated -> setForeground(foregroundInfo(updated)) }
        }
        return when (store.item(id)?.state) {
            "complete" -> Result.success()
            "paused", "cancelled" -> Result.success()
            "failed" -> Result.failure()
            else -> Result.retry()
        }
    }

    private fun foregroundInfo(item: DownloadItem): ForegroundInfo {
        val notification = TransferNotifications.build(applicationContext, item)
        val id = TransferLauncher.jobId(item.id)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id, notification)
    }

    companion object {
        const val KEY_ID = "download_id"
    }
}
