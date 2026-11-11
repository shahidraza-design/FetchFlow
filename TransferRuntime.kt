package app.fetchflow

import android.app.JobInfo
import android.app.JobParameters
import android.app.JobScheduler
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal object TransferLauncher {
    fun start(context: Context, id: Long) {
        if (Build.VERSION.SDK_INT >= 34) {
            val item = DownloadStore.get(context).item(id) ?: return
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).apply {
                if (item.wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }.build()
            val extras = PersistableBundle().apply { putLong("task_id", id) }
            val info = JobInfo.Builder(jobId(id), ComponentName(context, FetchFlowTransferJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(request)
                .setExtras(extras)
                .build()
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val result = runCatching { scheduler.schedule(info) }.getOrDefault(JobScheduler.RESULT_FAILURE)
            if (result != JobScheduler.RESULT_SUCCESS) {
                DownloadStore.get(context).update(id) { it.copy(state = "failed", reason = "Android could not schedule this transfer. Open the app and retry.") }
            }
        } else {
            val intent = Intent(context, FetchFlowLegacyTransferService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    fun stop(context: Context, id: Long) {
        TransferEngine.cancel(id)
        if (Build.VERSION.SDK_INT >= 34) {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(jobId(id))
        } else {
            val intent = Intent(context, FetchFlowLegacyTransferService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    fun jobId(id: Long): Int = ((id xor (id ushr 32)).toInt() and 0x3fffffff).coerceAtLeast(1)

    const val EXTRA_ID = "task_id"
    const val ACTION_START = "app.fetchflow.action.START"
    const val ACTION_STOP = "app.fetchflow.action.STOP"
    const val CHANNEL_ID = "fetchflow_transfers"
    const val NOTIFICATION_ID = 4100
}

internal object TransferNotifications {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(TransferLauncher.CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for FetchFlow downloads"
                setShowBadge(false)
            })
        }
    }

    fun build(context: Context, item: DownloadItem): Notification {
        val open = PendingIntent.getActivity(
            context, item.id.toInt(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val progress = if (item.total > 0) ((item.bytes * 100 / item.total).toInt()).coerceIn(0, 100) else 0
        val builder = NotificationCompat.Builder(context, TransferLauncher.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.name)
            .setContentText(when {
                item.state == "scheduled" -> "Waiting for the scheduled start time"
                item.state == "queued" -> "Waiting for an available download slot"
                item.total > 0 -> "${formatBytes(item.bytes)} of ${formatBytes(item.total)}"
                else -> "Downloading…"
            })
            .setProgress(100, progress, item.state == "downloading" && item.total <= 0)
            .setContentIntent(open)
            .setOngoing(item.state == "downloading" || item.state == "queued" || item.state == "scheduled")
            .setOnlyAlertOnce(true)
        when (item.state) {
            "downloading", "queued", "scheduled" -> builder
                .addAction(action(context, item.id, TransferActionReceiver.PAUSE, "Pause", android.R.drawable.ic_media_pause))
                .addAction(action(context, item.id, TransferActionReceiver.CANCEL, "Cancel", android.R.drawable.ic_menu_close_clear_cancel))
            "paused" -> builder.addAction(action(context, item.id, TransferActionReceiver.RESUME, "Resume", android.R.drawable.ic_media_play))
            "failed" -> builder.addAction(action(context, item.id, TransferActionReceiver.RETRY, "Retry", android.R.drawable.ic_menu_rotate))
        }
        return builder.build()
    }

    private fun action(context: Context, id: Long, action: String, label: String, icon: Int): NotificationCompat.Action {
        val intent = Intent(context, TransferActionReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
            .putExtra(TransferLauncher.EXTRA_ID, id)
        val pending = PendingIntent.getBroadcast(
            context, (id xor action.hashCode().toLong()).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }
}

/** Handles explicit actions from a user's download notification. */
class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(TransferLauncher.EXTRA_ID, -1L) ?: -1L
        if (id <= 0L) return
        val store = DownloadStore.get(context)
        when (intent?.action) {
            PAUSE -> store.pause(id)
            CANCEL -> store.cancel(id)
            RESUME -> store.resume(id)
            RETRY -> store.retry(id)
            else -> return
        }
        val item = store.item(id) ?: return
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (item.state == "cancelled") manager.cancel(TransferLauncher.jobId(id))
            else manager.notify(TransferLauncher.jobId(id), TransferNotifications.build(context, item))
        }
    }

    companion object {
        const val PAUSE = "app.fetchflow.notification.PAUSE"
        const val CANCEL = "app.fetchflow.notification.CANCEL"
        const val RESUME = "app.fetchflow.notification.RESUME"
        const val RETRY = "app.fetchflow.notification.RETRY"
    }
}

/** Android 14+ user-initiated data-transfer job. */
class FetchFlowTransferJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Job>()
    private val stopped = ConcurrentHashMap.newKeySet<Long>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(TransferLauncher.EXTRA_ID, -1L)
        val item = DownloadStore.get(this).item(id) ?: run { jobFinished(params, false); return false }
        DownloadConcurrency.configure(getSharedPreferences("fetchflow.preferences", Context.MODE_PRIVATE).getInt("max_active_downloads", 2))
        stopped.remove(id)
        TransferNotifications.ensureChannel(this)
        setNotification(params, TransferLauncher.jobId(id), TransferNotifications.build(this, item), JobService.JOB_END_NOTIFICATION_POLICY_DETACH)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                DownloadConcurrency.withSlot {
                    TransferEngine(this@FetchFlowTransferJob).execute(id) { updated ->
                        setNotification(params, TransferLauncher.jobId(id), TransferNotifications.build(this@FetchFlowTransferJob, updated), JobService.JOB_END_NOTIFICATION_POLICY_DETACH)
                    }
                }
            } catch (_: CancellationException) {
                // The job scheduler will invoke onStopJob for system or user cancellation.
            } finally {
                running.remove(id)
                if (!stopped.remove(id)) jobFinished(params, false)
            }
        }
        running[id] = job
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(TransferLauncher.EXTRA_ID, -1L)
        stopped.add(id)
        TransferEngine.cancel(id)
        running.remove(id)?.cancel(CancellationException("System stopped the transfer job"))
        val store = DownloadStore.get(this)
        val userStopped = Build.VERSION.SDK_INT >= 31 && params.stopReason == JobParameters.STOP_REASON_USER
        if (userStopped) {
            store.update(id) { if (it.state == "downloading" || it.state == "queued") it.copy(state = "paused", reason = "Stopped by Android. Resume when you are ready.") else it }
        }
        val state = store.item(id)?.state
        return !userStopped && state != "paused" && state != "cancelled" && state != "complete"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Foreground-service fallback for Android 8–13. */
class FetchFlowLegacyTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, Job>()
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TransferNotifications.ensureChannel(this)
        DownloadConcurrency.configure(getSharedPreferences("fetchflow.preferences", Context.MODE_PRIVATE).getInt("max_active_downloads", 2))
        val id = intent?.getLongExtra(TransferLauncher.EXTRA_ID, -1L) ?: -1L
        if (!foregroundStarted) {
            val initial = if (id > 0) DownloadStore.get(this).item(id) else null
            val notification = TransferNotifications.build(this, initial ?: DownloadItem(0, "", "FetchFlow downloads", "downloading"))
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, TransferLauncher.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(TransferLauncher.NOTIFICATION_ID, notification)
            foregroundStarted = true
        }

        when (intent?.action) {
            TransferLauncher.ACTION_STOP -> {
                TransferEngine.cancel(id)
                running.remove(id)?.cancel(CancellationException("Transfer stopped by user"))
                finishIfIdle(startId)
            }
            else -> if (id > 0 && running[id] == null) {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        DownloadConcurrency.withSlot {
                            TransferEngine(this@FetchFlowLegacyTransferService).execute(id) { item ->
                                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(TransferLauncher.jobId(id), TransferNotifications.build(this@FetchFlowLegacyTransferService, item))
                            }
                        }
                    } catch (_: CancellationException) {
                        // Pause and stop actions are reflected in the persistent download state.
                    } finally {
                        running.remove(id)
                        finishIfIdle(startId)
                    }
                }
                running[id] = job
                job.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun finishIfIdle(startId: Int) {
        if (running.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            foregroundStarted = false
            stopSelfResult(startId)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        running.values.forEach { it.cancel(CancellationException("Android foreground-service time limit reached")) }
        running.clear()
        stopSelf(startId)
    }

    override fun onDestroy() {
        running.keys.forEach { TransferEngine.cancel(it) }
        scope.cancel()
        super.onDestroy()
    }
}
