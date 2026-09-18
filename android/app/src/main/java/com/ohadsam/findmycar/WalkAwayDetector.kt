package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.NativeVehicle
import com.ohadsam.findmycar.core.PendingParkingSuggestion
import com.ohadsam.findmycar.core.VehicleJsonParser

/**
 * Opens and closes the "did they park and walk away?" window, and raises the
 * suggestion WalkAwayEngine decides on. The per-fix decision itself lives in
 * ParkingForegroundService.onLocationShadow (where location updates arrive),
 * exactly as BtPendingActionRecorder owns the recording while the service's own
 * receiver owns the event.
 *
 * **Called from the service's BT receiver, not from BluetoothClassicPlugin** —
 * the same lesson as BtPendingActionRecorder (see CLAUDE.md "Resolved: real,
 * previously-shipped bug"): the plugin's BtEventBus listener is torn down
 * exactly when the Activity is destroyed, which is precisely the situation this
 * feature exists for (phone in a pocket, app not open).
 *
 * **Deliberately NOT gated on the WebView being unreachable**, unlike
 * BtPendingActionRecorder. There is no live JS equivalent to defer to: walk
 * detection needs continuous location updates, and the browser API JS would
 * have to use (`navigator.geolocation.watchPosition`) is throttled the moment
 * the Activity stops being visible — the exact window this runs in. The
 * service's own LocationManager watch is not, so native is the only
 * implementation and it must run whether or not a WebView happens to be alive.
 */
object WalkAwayDetector {
    private const val TAG = "FMC-WalkAway"

    /**
     * Opens a window if this disconnect is one worth watching. All must hold:
     *  - the Bluetooth master switch is on;
     *  - the label belongs to a linked vehicle;
     *  - that vehicle has `walkAwaySuggest` on (opt-in, per vehicle);
     *  - `bluetoothAutoStart` is OFF — with it on a parking is already saved
     *    outright on disconnect and there is nothing to suggest;
     *  - the vehicle has no active parking already.
     */
    fun maybeOpenWindow(context: Context, label: String) {
        try {
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(WidgetDataPlugin.KEY_BT_ENABLED, false)) return
            val vehicles = VehicleJsonParser.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            val vehicle = vehicles.firstOrNull { it.bluetoothDevice == label && eligible(it) } ?: return

            val (lat, lng) = LastKnownLocation.get(context)
            PendingParkingSuggestionStore.openWindow(
                context,
                PendingParkingSuggestionStore.Window(
                    vehicleId = vehicle.id,
                    vehicleName = vehicle.name,
                    label = label,
                    lat = lat,
                    lng = lng,
                    disconnectedAt = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "opened walk-away window for vehicle=${vehicle.name} (fix=${lat != null})")
            NativeLogStore.add(
                context, TAG, "WALK",
                "Bluetooth disconnected from ${vehicle.name} — watching for a walk away from the car" +
                    if (lat == null) " (no location fix captured yet)" else "",
            )
            // The service is already running (the "bluetooth" reason keeps it
            // alive whenever the master switch is on), but its location watch
            // only starts for a parking session — nudge it to start now.
            ParkingForegroundService.onWalkAwayWindowChanged(context)
        } catch (e: Exception) {
            Log.w(TAG, "maybeOpenWindow failed (non-fatal)", e)
        }
    }

    private fun eligible(v: NativeVehicle): Boolean =
        v.walkAwaySuggest && !v.bluetoothAutoStart && !v.hasParking

    /**
     * Raises the suggestion: persists it for JS to turn into a confirmation on
     * its next resume, and posts a notification that can answer it from the
     * shade without opening the app at all.
     *
     * Never saves a parking itself — like GPS's SuggestEnd, this only ever asks.
     */
    fun raise(context: Context, window: PendingParkingSuggestionStore.Window) {
        try {
            val entry = PendingParkingSuggestion(
                vehicleId = window.vehicleId,
                vehicleName = window.vehicleName,
                label = window.label,
                lat = window.lat,
                lng = window.lng,
                timestamp = window.disconnectedAt,
            )
            PendingParkingSuggestionStore.set(context, entry)
            Log.i(TAG, "raised walk-away parking suggestion: $entry")
            NativeLogStore.add(
                context, TAG, "WALK",
                "walked away from ${window.vehicleName} — offering to save the parking spot",
            )
            // Same headless path as every other notification button (CLAUDE.md,
            // "One headless path, not two"). The button carries no coordinates:
            // the location lives in the store entry just written, which both the
            // live path and the killed-app replay read — so the spot saved is
            // where the car actually is, not wherever the user is standing when
            // they tap, and PendingWidgetAction needs no new fields to carry a
            // location through the replay.
            BackgroundAlertNotifier.show(
                context,
                "🅿️ לשמור את החניה?",
                "${window.vehicleName} — נראה שחנית והתרחקת מהרכב.",
                listOf(
                    BackgroundAlertNotifier.Action("שמור חניה", WidgetActionReceiver.ACTION_SAVE_AT, window.vehicleId),
                    BackgroundAlertNotifier.Action("לא עכשיו", WidgetActionReceiver.ACTION_DISMISS, null),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "raise failed (non-fatal)", e)
        }
    }

    fun closeWindow(context: Context, reason: String) {
        try {
            if (PendingParkingSuggestionStore.getWindow(context) == null) return
            PendingParkingSuggestionStore.closeWindow(context)
            Log.i(TAG, "closed walk-away window ($reason)")
            NativeLogStore.add(context, TAG, "WALK", "stopped watching for a walk away — $reason")
            ParkingForegroundService.onWalkAwayWindowChanged(context)
        } catch (e: Exception) {
            Log.w(TAG, "closeWindow failed (non-fatal)", e)
        }
    }
}
