package com.ohadsam.findmycar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ohadsam.findmycar.widgets.QuickSaveWidgetProvider

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

    /**
     * One shade button. [action] is handed to WidgetActionReceiver, the same
     * receiver the widgets use, so there is a single headless action path
     * rather than a second one just for notifications — "end"/"save"/"swap"
     * perform the real thing (falling back to PendingWidgetActionStore when
     * the WebView is gone), and "dismiss" only clears the notification.
     */
    data class Action(val label: String, val action: String, val vehicleId: String?)

    fun show(context: Context, title: String, body: String, actions: List<Action> = emptyList()) {
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
            // Stable id, needed up front so each action's PendingIntent can
            // tell WidgetActionReceiver which notification to dismiss.
            val notifId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setColor(0xFF5B8BF5.toInt())
                .setAutoCancel(true)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            actions.forEachIndexed { i, a ->
                val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                    putExtra(QuickSaveWidgetProvider.EXTRA_ACTION, a.action)
                    putExtra(WidgetActionReceiver.EXTRA_VEHICLE_ID, a.vehicleId)
                    putExtra(WidgetActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
                }
                // Request code must be unique per (notification, button) or
                // Android reuses one PendingIntent for every button.
                val pi = PendingIntent.getBroadcast(context, notifId + i, intent, piFlags)
                builder.addAction(0, a.label, pi)
            }
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "show failed (non-fatal)", e)
        }
    }
}
