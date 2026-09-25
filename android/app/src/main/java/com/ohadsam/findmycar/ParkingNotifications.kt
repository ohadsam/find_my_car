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
import com.ohadsam.findmycar.widgets.ParkedVehicles

/**
 * The persistent "active parking" notifications — one per parked vehicle
 * (v1.51.0), each naming its vehicle.
 *
 * Before v1.51.0 there was a single notification describing only the ACTIVE
 * vehicle: a second vehicle's parking had no notification at all, and a
 * parking saved or ended for a non-active vehicle (Bluetooth, a widget with a
 * vehicle picked, a notification button) could not show up in the shade.
 *
 * [sync] rebuilds the whole set from the vehicles_json mirror — the same list
 * the widgets render from — so every writer (JS syncs, WidgetMirror, the
 * native geocoder) needs only one call and they can never disagree. Each
 * vehicle's notification is keyed by a tag (the vehicle id) under one shared
 * notification id, so posting is idempotent and cancelling one never touches
 * another.
 */
object ParkingNotifications {
    private const val TAG = "FMC-ParkingNotif"
    private const val CHANNEL_ID = "findmycar_parking_active"
    private const val NOTIF_ID = 4202
    private const val TAG_PREFIX = "parking:"

    @Synchronized
    fun sync(context: Context) {
        try {
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val parked = ParkedVehicles.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            val mgr = NotificationManagerCompat.from(context)
            // The pre-v1.51.0 single, untagged notification.
            mgr.cancel(NOTIF_ID)

            val wanted = parked.map { TAG_PREFIX + it.id }.toSet()
            staleTags(context, prefs, wanted).forEach { mgr.cancel(it, NOTIF_ID) }
            if (parked.isEmpty()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "חניה פעילה", NotificationManager.IMPORTANCE_LOW)
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            for (v in parked) {
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("${v.icon.ifBlank { "🚗" }} ${v.name} — חניה פעילה 🅿️".trim())
                    .setContentText(v.address.ifBlank { "מיקום נשמר" })
                    .setSmallIcon(R.drawable.ic_stat_car)
                    .setColor(0xFF5B8BF5.toInt())
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .build()
                mgr.notify(TAG_PREFIX + v.id, NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync failed (non-fatal)", e)
        }
    }

    /**
     * Tags currently posted that no longer describe a parked vehicle. The
     * system can list what is showing from API 23; below that, every vehicle
     * the mirror still knows about is a candidate (a deleted vehicle's
     * notification then simply stays until the next reboot — API 22 only).
     */
    private fun staleTags(context: Context, prefs: android.content.SharedPreferences, wanted: Set<String>): List<String> {
        val shown: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.filter { it.id == NOTIF_ID && it.tag?.startsWith(TAG_PREFIX) == true }
                ?.mapNotNull { it.tag } ?: emptyList()
        } else {
            try {
                val arr = org.json.JSONArray(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id", "") }
                    .filter { it.isNotBlank() }.map { TAG_PREFIX + it }
            } catch (e: Exception) {
                emptyList()
            }
        }
        return shown.filter { it !in wanted }
    }
}
