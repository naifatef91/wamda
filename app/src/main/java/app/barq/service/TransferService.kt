package app.barq.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.barq.BarqApp
import app.barq.R
import app.barq.TransferController
import app.barq.TransferState
import app.barq.ui.MainActivity
import app.barq.ui.formatBytes
import app.barq.ui.formatSpeed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the CPU awake while a transfer runs, and mirrors
 * progress into the notification. The engine itself lives in [TransferController].
 */
class TransferService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, build(getString(R.string.notif_running), null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barq:transfer").apply { acquire(60 * 60 * 1000L) }

        lifecycleScope.launch {
            TransferController.state.collectLatest { s ->
                when (s) {
                    is TransferState.Running -> notify(
                        "${formatSpeed(s.snapshot.speedBps)}  ·  ${formatBytes(s.snapshot.bytesDone)} / ${formatBytes(s.snapshot.bytesTotal)}",
                        s.snapshot.fraction,
                    )
                    is TransferState.Finished, is TransferState.Failed, TransferState.Idle -> stopSelf()
                    else -> notify(getString(R.string.notif_running), null)
                }
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notify(text: String, fraction: Float?) {
        getSystemService(NOTIFICATION_SERVICE).let { nm ->
            (nm as android.app.NotificationManager).notify(NOTIF_ID, build(text, fraction))
        }
    }

    private fun build(text: String, fraction: Float?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, BarqApp.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (fraction != null) setProgress(1000, (fraction * 1000).toInt(), false) }
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
