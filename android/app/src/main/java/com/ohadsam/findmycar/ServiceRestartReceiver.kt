package com.ohadsam.findmycar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts ParkingForegroundService after the two events that kill the app's
 * process without any user action: a device reboot, and the app's own APK
 * being replaced (an update).
 *
 * Real, previously-shipped gap: every caller of
 * ParkingForegroundService.setReasonActive() is a Capacitor @PluginMethod —
 * i.e. only ever reachable while the app is actually open. So after a reboot
 * or an update, background BT/GPS detection simply stayed dead until the user
 * happened to launch the app again, with nothing in the diagnostic log to
 * explain the silence (the service genuinely wasn't running, so it couldn't
 * log anything either). ParkingForegroundService.startIfNeeded() re-derives
 * whether it should be running from the persisted mirror (an active parking,
 * or the Bluetooth master switch being on) rather than from the in-memory
 * reason set, which a process restart has necessarily wiped.
 *
 * Starting a foreground service from BOOT_COMPLETED / MY_PACKAGE_REPLACED is
 * explicitly exempt from Android 12+'s background-FGS-start restriction, so
 * this is a legal place to do it (startIfNeeded() still wraps the call in its
 * own try/catch — an OEM may impose stricter rules than stock Android).
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "FMC-ServiceRestart" }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "received $action — checking whether to restart the foreground service")
        NativeLogStore.add(context, TAG, "SERVICE", "received $action — checking whether to restart the foreground service")
        try {
            ParkingForegroundService.startIfNeeded(context)
        } catch (e: Exception) {
            // Must never crash the boot broadcast — worst case detection stays
            // inactive until the app is next opened, exactly as before.
            Log.w(TAG, "startIfNeeded threw (non-fatal)", e)
        }
    }
}
