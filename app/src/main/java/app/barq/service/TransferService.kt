package app.barq.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
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
    private var wifiLock: WifiManager.WifiLock? = null

    /** The real artwork for the expanded notification; the status bar keeps the monochrome silhouette. */
    private val largeIcon by lazy { android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_art) }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()

        lifecycleScope.launch {
            TransferController.state.collectLatest { s ->
                when (s) {
                    is TransferState.Running -> notify(
                        "${formatSpeed(s.snapshot.speedBps)}  ·  ${formatBytes(s.snapshot.bytesDone)} / ${formatBytes(s.snapshot.bytesTotal)}",
                        s.snapshot.fraction,
                    )
                    is TransferState.Finished, is TransferState.Failed, TransferState.Idle -> { releaseLocks(); stopSelf() }
                    else -> notify(getString(R.string.notif_running), null)
                }
            }
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    /**
     * dataSync keeps the process alive; connectedDevice adds the nearby-phone nature of the work. Android 14+
     * only grants connectedDevice while a matching runtime permission is held (nearby devices, Bluetooth), so
     * if the system refuses the pair we fall back to dataSync alone rather than crash a transfer.
     */
    private fun startInForeground() {
        val n = build(getString(R.string.notif_running), null)
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            startForeground(NOTIF_ID, n, both)
        } catch (e: SecurityException) {
            android.util.Log.w("Wamda", "connectedDevice service type refused, using dataSync only", e)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    /** Held for the whole transfer: no CPU sleep, and the Wi-Fi radio stays awake at full speed with the screen off. */
    private fun acquireLocks() {
        wakeLock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "barq:transfer").apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        }.getOrNull()
        wifiLock = runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "barq:transfer").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    /** Safe to call more than once; each lock is released only if it is still held. */
    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null; wifiLock = null
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
            .setLargeIcon(largeIcon)
            .setColor(0xFF5FE3C2.toInt())
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
