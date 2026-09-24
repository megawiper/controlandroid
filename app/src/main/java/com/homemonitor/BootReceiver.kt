package com.homemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver
 *
 * Listens for BOOT_COMPLETED (and QUICKBOOT_POWERON for some OEM devices).
 * When the device finishes booting it automatically starts MonitorService
 * as a foreground service, so monitoring resumes without user interaction.
 *
 * NOTE: The user must have opened the app at least once after installation
 *       for this receiver to fire (Android 3.1+ background-start restriction).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Boot completed – starting MonitorService")

            val serviceIntent = Intent(context, MonitorService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On Android 8+ we must use startForegroundService() when the
                // app is not in the foreground. The service itself must then call
                // startForeground() within 5 seconds or the OS will ANR it.
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
