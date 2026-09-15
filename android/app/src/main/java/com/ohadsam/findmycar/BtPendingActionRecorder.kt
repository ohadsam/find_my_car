package com.ohadsam.findmycar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ohadsam.findmycar.core.BtConnectDecision
import com.ohadsam.findmycar.core.BtDecisionEngine
import com.ohadsam.findmycar.core.BtDisconnectDecision
import com.ohadsam.findmycar.core.NativeVehicle
import com.ohadsam.findmycar.core.PendingBtAction
import com.ohadsam.findmycar.core.VehicleJsonParser

/**
 * Stage 5 of the native background-detection migration (see CLAUDE.md
 * "Native background detection"): when the WebView is unreachable
 * (MainActivity.getActiveWebView() == null), records what BtDecisionEngine
 * decided for real, so JS can reconcile ("catch up") the next time it
 * resumes — see PendingBtActionStore. Deliberately a no-op when the WebView
 * IS reachable: the live listener path (BluetoothClassicPlugin, via
 * BtEventBus) already handles the event through the normal, unchanged flow,
 * so acting here too would double the action. Only ever records
 * AutoEnd/AutoStart — SuggestEnd needs a confirmation modal, which has no
 * meaning without a UI to show it in, so it stays JS/foreground-only exactly
 * like today.
 *
 * Real, previously-shipped bug this object's extraction fixes (see
 * CLAUDE.md "Open investigation" resolution): this logic used to live
 * directly inside BluetoothClassicPlugin, called only from its
 * BtEventBus.Listener callbacks — but that listener registration is torn
 * down (BluetoothClassicPlugin.handleOnDestroy() -> BtEventBus.removeListener())
 * exactly when the Activity is destroyed, which is precisely the
 * WebView-unreachable scenario this whole mechanism exists to handle. So the
 * pending-action recording could never actually fire in the one case it was
 * built for. Now called directly and unconditionally from
 * ParkingForegroundService's own BT broadcast receiver — the thing that's
 * actually alive independent of Activity/Plugin lifecycle — on every real
 * ACL broadcast, regardless of whether any live listener currently exists.
 * The `MainActivity.getActiveWebView() != null` guard below is what still
 * correctly no-ops this when the live JS path is the one handling the event.
 */
object BtPendingActionRecorder {
    private const val TAG = "FMC-BtPending"

    fun maybeRecord(context: Context, label: String, connected: Boolean) {
        try {
            if (MainActivity.getActiveWebView() != null) return // live path already handles it
            val json = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                .getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]"
            val vehicles = VehicleJsonParser.parse(json)
            val hasParking: (String) -> Boolean = { id -> vehicles.find { it.id == id }?.hasParking ?: false }
            val direction = if (connected) "connected" else "disconnected"

            if (connected) {
                // #btEndParking (js/app.js) just moves the EXISTING parking
                // record to history — it never needs a fresh location fix,
                // unlike auto-start below.
                for (decision in BtDecisionEngine.onConnected(vehicles, label, hasParking)) {
                    if (decision !is BtConnectDecision.AutoEnd) continue
                    record(context, direction, "autoEnd", decision.vehicle, label, lat = null, lng = null)
                }
            } else {
                for (decision in BtDecisionEngine.onDisconnected(vehicles, label, hasParking)) {
                    if (decision !is BtDisconnectDecision.AutoStart) continue
                    val (lat, lng) = lastKnownLocation(context)
                    record(context, direction, "autoStart", decision.vehicle, label, lat, lng)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "maybeRecord failed (non-fatal)", e)
        }
    }

    private fun record(
        context: Context, direction: String, action: String, vehicle: NativeVehicle, label: String, lat: Double?, lng: Double?,
    ) {
        val entry = PendingBtAction(direction, action, vehicle.id, vehicle.name, label, lat, lng, System.currentTimeMillis())
        PendingBtActionStore.add(context, entry)
        Log.i(TAG, "recorded pending BT action (WebView unreachable): $entry")
        val title = if (action == "autoEnd") "🚗 חניה הסתיימה אוטומטית" else "🅿️ חניה חדשה תישמר בפתיחה הבאה"
        BackgroundAlertNotifier.show(context, title, "${vehicle.name} — יטופל כשהאפליקציה תיפתח מחדש")
    }

    /** Best-effort — no fresh location request, just whatever the system already has cached. */
    private fun lastKnownLocation(context: Context): Pair<Double?, Double?> {
        return try {
            val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!fineGranted) return null to null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null to null
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) loc.latitude to loc.longitude else null to null
        } catch (e: Exception) {
            null to null
        }
    }
}
