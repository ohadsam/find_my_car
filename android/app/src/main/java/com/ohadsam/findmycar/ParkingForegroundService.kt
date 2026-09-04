package com.ohadsam.findmycar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app process alive while the screen is off or the app is
 * backgrounded, so the real Bluetooth ACL connect/disconnect broadcasts and
 * the JS GPS-speed watch (both consumed by the existing parking logic in
 * js/app.js) keep running — this is the actual fix for the PWA's background
 * limitation, which relied on a foregrounded browser tab.
 *
 * Started/stopped by a simple reference count of "reasons": active while BT
 * auto-detection is enabled (BluetoothClassicPlugin.startWatch/stopWatch) OR
 * a parking session is active (WidgetDataPlugin.update/clear).
 */
class ParkingForegroundService : Service() {

    private var receiver: BroadcastReceiver? = null

    companion object {
        private const val CHANNEL_ID = "findmycar_background"
        private const val NOTIFICATION_ID = 4201
        private val activeReasons = mutableSetOf<String>()

        @Synchronized
        fun setReasonActive(context: Context, reason: String, active: Boolean) {
            val wasEmpty = activeReasons.isEmpty()
            if (active) activeReasons.add(reason) else activeReasons.remove(reason)
            val nowEmpty = activeReasons.isEmpty()

            val intent = Intent(context, ParkingForegroundService::class.java)
            if (wasEmpty && !nowEmpty) {
                ContextCompat.startForegroundService(context, intent)
            } else if (!wasEmpty && nowEmpty) {
                context.stopService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        registerBtReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        receiver?.let { try { unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* already gone */ } }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBtReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val label = try { device?.name } catch (e: SecurityException) { null }
                if (label.isNullOrBlank()) return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED    -> BtEventBus.emitConnected(label)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> BtEventBus.emitDisconnected(label)
                }
            }
        }
        // RECEIVER_NOT_EXPORTED is required on API 33+ for dynamically registered
        // receivers with no permission; this receiver only reacts to system BT
        // broadcasts and never needs to be reachable from other apps.
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "פעילות ברקע", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "שומר על זיהוי Bluetooth ומיקום פעילים ברקע בזמן חניה"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindMyCar פעיל ברקע")
            .setContentText("מזהה Bluetooth ומיקום כדי לשמור/לסיים חניה אוטומטית")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
