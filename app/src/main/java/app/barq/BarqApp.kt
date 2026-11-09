package app.barq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class BarqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // Housekeeping off the main thread: packed .apks from earlier sends, and resume states nobody finished within a week.
        Thread {
            runCatching { java.io.File(cacheDir, "apks").deleteRecursively() }
            runCatching { app.barq.engine.ReceiveStore.sweepOrphans(this, app.barq.engine.ReceiveStore.ORPHAN_AGE_MS) }
        }.start()
    }

    companion object {
        const val CHANNEL_TRANSFERS = "transfers"
    }
}
