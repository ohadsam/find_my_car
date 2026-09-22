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

    // IMPORTANCE_HIGH, not DEFAULT — and a NEW channel id, which is the whole
    // point of the rename. A real report: the "🚗 מזוהה נסיעה" alert reached
    // the shade during a drive but never appeared on screen, while the user was
    // in Waze; they expected it to pop up the way a WhatsApp message does.
    //
    // DEFAULT makes a sound and puts an icon in the status bar. It does NOT
    // produce a heads-up banner — that needs IMPORTANCE_HIGH (plus PRIORITY_HIGH
    // for pre-O). These are precisely the notifications that ask the driver to
    // decide something, so reaching them only by pulling the shade down defeats
    // the purpose.
    //
    // A channel's importance is fixed at creation: calling
    // createNotificationChannel again with a higher importance on an existing id
    // is silently ignored, so every already-installed user would have kept the
    // old DEFAULT behaviour forever. Hence the v2 id, and deleting the old one
    // so it doesn't linger in the app's notification settings.
    private const val CHANNEL_ID = "findmycar_alerts_v2"
    private const val LEGACY_CHANNEL_ID = "findmycar_bt_alerts"

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
                val mgr = context.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(CHANNEL_ID, "התראות רקע", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "זיהוי נסיעה, חיבור Bluetooth והצעות חניה — מופיעות על המסך"
                    enableVibration(true)
                }
                mgr?.createNotificationChannel(channel)
                runCatching { mgr?.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
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
                // PRIORITY_HIGH is what drives heads-up below Android 8, where
                // channels don't exist; on 8+ the channel's importance wins and
                // this is simply ignored. Both are needed to cover minSdk 22.
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                // Tells the OS this is a time-sensitive prompt rather than
                // background chatter, which some launchers and Do-Not-Disturb
                // configurations use when deciding whether to surface it.
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
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
