package com.ohadsam.findmycar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Fires once a day (see DailyStatusScheduler) to post a single system
 * notification reporting, per vehicle that has the per-vehicle setting
 * enabled, whether it currently has an active parking and where — added so
 * a user can glance at the notification shade and confirm the app (and its
 * background BT/GPS detection) is genuinely still alive and tracking real
 * state, without having to open the app or read the diagnostic log.
 * Global master switch + per-vehicle opt-out mirror the existing
 * Bluetooth-settings pattern (fmc_daily_status_v1 / vehicle.dailyStatusEnabled
 * in js/, mirrored here via WidgetDataPlugin.syncVehicles()).
 *
 * Manifest-registered (not dynamically, unlike ParkingForegroundService's BT
 * ACL receiver) specifically so it can also handle BOOT_COMPLETED — a
 * dynamically-registered receiver only exists while its owning
 * process/Service is alive, but this alarm must keep firing across reboots
 * even when no parking session or Bluetooth watch happens to be active
 * (AlarmManager alarms are themselves cleared on reboot, so without this,
 * the feature would silently stop working after every device restart until
 * the user happened to reopen the app).
 */
class DailyStatusReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "FMC-DailyStatus"
        private const val CHANNEL_ID = "findmycar_daily_status"
        private const val NOTIF_ID = 4203
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Alarms don't survive reboot — only re-arm if the user had
                // it enabled; never fires immediately on boot, just restores
                // the next scheduled occurrence.
                val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean(WidgetDataPlugin.KEY_DAILY_STATUS_ENABLED, false)
                NativeLogStore.add(context, TAG, "DAILY", "boot: ${if (enabled) "rescheduling" else "not rescheduling (disabled)"} daily status alarm")
                if (enabled) DailyStatusScheduler.scheduleNext(context)
            }
            DailyStatusScheduler.ACTION_DAILY_STATUS -> {
                try {
                    showStatusNotification(context)
                } catch (e: Exception) {
                    Log.w(TAG, "showStatusNotification failed (non-fatal)", e)
                    NativeLogStore.add(context, TAG, "DAILY", "showStatusNotification FAILED (${e.message})")
                }
                // Always reschedule for tomorrow, even if building/showing the
                // notification above threw — a one-time failure (e.g. a
                // transient SharedPreferences read issue) must never silently
                // end the whole daily cadence.
                DailyStatusScheduler.scheduleNext(context)
            }
        }
    }

    private fun showStatusNotification(context: Context) {
        val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(WidgetDataPlugin.KEY_DAILY_STATUS_ENABLED, false)) {
            NativeLogStore.add(context, TAG, "DAILY", "alarm fired but master switch is off — skipped")
            return
        }
        val vehicles = DailyStatusVehicles.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
        if (vehicles.isEmpty()) {
            // Either no vehicles exist, or every one of them has the
            // per-vehicle setting turned off — nothing to report.
            NativeLogStore.add(context, TAG, "DAILY", "alarm fired but no vehicles opted in — skipped")
            return
        }

        val lines = vehicles.map { v ->
            if (v.hasParking) {
                "${v.icon} ${v.name} — חניה פעילה" + (if (v.address.isNotBlank()) ": ${v.address}" else "")
            } else {
                "${v.icon} ${v.name} — אין חניה פעילה"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                NativeLogStore.add(context, TAG, "DAILY", "alarm fired but POST_NOTIFICATIONS not granted — skipped")
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DEFAULT (not LOW) — this is a once-a-day digest the user
            // explicitly opted into specifically to notice it landed, unlike
            // the persistent "active parking"/"active in background"
            // notifications, which are deliberately quiet/silent since
            // they're always-on background noise otherwise.
            val channel = NotificationChannel(CHANNEL_ID, "סטטוס יומי", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("FindMyCar — סטטוס יומי")
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(0xFF5B8BF5.toInt())
            .setAutoCancel(true)

        if (lines.size == 1) {
            builder.setContentText(lines[0])
        } else {
            builder.setContentText(lines.joinToString(" · "))
            val style = NotificationCompat.InboxStyle()
            lines.forEach { style.addLine(it) }
            builder.setStyle(style)
        }

        NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        NativeLogStore.add(context, TAG, "DAILY", "daily status notification shown (${lines.size} vehicle(s))")
    }
}
