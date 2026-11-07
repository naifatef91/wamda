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
    }

    companion object {
        const val CHANNEL_TRANSFERS = "transfers"
    }
}
