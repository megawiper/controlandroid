package com.homemonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Custom Application class.
 *
 * Responsibilities:
 *  - Create the notification channel required for the foreground service
 *    persistent notification (mandatory on Android 8+, API 26+).
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Creates a HIGH-importance notification channel so the foreground
     * service notification is immediately visible when the service starts.
     * Safe to call multiple times – the system ignores duplicate create calls.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId   = getString(R.string.notif_channel_id)
            val channelName = getString(R.string.notif_channel_name)
            val channelDesc = getString(R.string.notif_channel_desc)

            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW   // LOW = no sound, but still visible
            ).apply {
                description = channelDesc
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
