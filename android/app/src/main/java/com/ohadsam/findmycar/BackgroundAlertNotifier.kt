package com.ohadsam.findmycar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One-off system notifications for background BT/GPS events recorded while
 * the WebView was unreachable — js/notify.js's Notify.show() needs the
 * WebView (it goes through @capacitor/local-notifications), so it can't be
 * used from here. Shared by BluetoothClassicPlugin.maybeRecordPendingAction()
 * (Stage 5) and ParkingForegroundService.maybeRecordPendingGpsSuggestion()
 * (Stage 7), so both stay consistent instead of each hand-rolling their own
 * channel/permission handling.
 */
object BackgroundAlertNotifier {
    private const val TAG = "FMC-BgNotify"
    private const val CHANNEL_ID = "findmycar_bt_alerts"

    fun show(context: Context, title: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "התראות רקע", NotificationManager.IMPORTANCE_DEFAULT)
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setColor(0xFF5B8BF5.toInt())
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "show failed (non-fatal)", e)
        }
    }
}
