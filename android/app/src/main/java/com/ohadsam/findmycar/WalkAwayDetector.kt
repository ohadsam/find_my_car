package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.NativeVehicle
import com.ohadsam.findmycar.core.PendingParkingSuggestion
import com.ohadsam.findmycar.core.VehicleJsonParser

/**
 * Raises the "did you park and walk away?" suggestion the moment an eligible
 * Bluetooth disconnect happens, and can retract it again if it turns out to
 * have been wrong. Previously this waited for WalkAwayEngine to confirm actual
 * walking (speed + displacement) before ever notifying — see CLAUDE.md
 * "Walk-away parking suggestion" for that design. In practice a lot of
 * disconnects never produced a confirmed walk at all (a poor GPS fix indoors,
 * a phone left in a pocket not moving in the required pattern, a user who just
 * doesn't walk fast/far enough within the window), so the suggestion frequently
 * never appeared even when the user genuinely had parked — indistinguishable
 * from "not working". Now the notification fires on every eligible disconnect,
 * and WalkAwayEngine's job changes from *deciding whether to ask* to *deciding
 * whether to take the already-shown question back* (see [abort] below) if a
 * location fix shortly afterward proves the car never actually stopped there.
 * This matches this codebase's own stated bias elsewhere (see CLAUDE.md's GPS
 * speed-threshold history): "prefer a fix that degrades to 'fires a bit more
 * often than ideal' over one that can degrade to 'never fires'".
 *
 * **Called from the service's BT receiver, not from BluetoothClassicPlugin** —
 * the same lesson as BtPendingActionRecorder (see CLAUDE.md "Resolved: real,
 * previously-shipped bug"): the plugin's BtEventBus listener is torn down
 * exactly when the Activity is destroyed, which is precisely the situation this
 * feature exists for (phone in a pocket, app not open).
 *
 * **Deliberately NOT gated on the WebView being unreachable**, unlike
 * BtPendingActionRecorder. The retraction half still needs continuous location
 * updates, and the browser API JS would have to use
 * (`navigator.geolocation.watchPosition`) is throttled the moment the Activity
 * stops being visible — the exact window this runs in. The service's own
 * LocationManager watch is not, so native is the only implementation and it
 * must run whether or not a WebView happens to be alive.
 */
object WalkAwayDetector {
    private const val TAG = "FMC-WalkAway"

    /**
     * Raises the suggestion immediately if this disconnect is one worth asking
     * about. All must hold:
     *  - the Bluetooth master switch is on;
     *  - the label belongs to a linked vehicle;
     *  - that vehicle has `walkAwaySuggest` on (opt-in, per vehicle);
     *  - `bluetoothAutoStart` is OFF — with it on a parking is already saved
     *    outright on disconnect and there is nothing to suggest;
     *  - the vehicle has no active parking already.
     *
     * A window still opens after raising — not to gate the ask (that already
     * happened), only so [abort] has something to retract from if the car
     * turns out to still be moving.
     */
    fun maybeOpenWindow(context: Context, label: String) {
        try {
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(WidgetDataPlugin.KEY_BT_ENABLED, false)) {
                NativeLogStore.add(context, TAG, "WALK", "disconnect from \"$label\" ignored — the Bluetooth master switch is off")
                return
            }
            val vehicles = VehicleJsonParser.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            val linked = vehicles.filter { it.bluetoothDevice == label }
            val vehicle = linked.firstOrNull { eligible(it) }
            if (vehicle == null) {
                // Declining used to be completely silent, which made "the
                // feature decided not to" indistinguishable from "the feature
                // never ran" — the ambiguous silence this whole diagnostic log
                // exists to eliminate. Say which condition failed, by name.
                NativeLogStore.add(context, TAG, "WALK", declineReason(label, linked))
                return
            }

            // A new eligible disconnect supersedes any suggestion already
            // outstanding — the store only ever holds one at a time (see its
            // own doc comment), and simply overwriting it without cancelling
            // the old notification would leave a stale, unanswerable duplicate
            // stacked in the shade above the new one. Real risk: a flapping BT
            // link (brief signal drops) can produce several disconnects in a
            // few seconds, same class of problem widgetActionDedupeMs exists
            // for elsewhere.
            PendingParkingSuggestionStore.getWindow(context)?.let { BackgroundAlertNotifier.cancel(context, it.notificationId) }

            val (lat, lng) = LastKnownLocation.get(context)
            val draft = PendingParkingSuggestionStore.Window(
                vehicleId = vehicle.id,
                vehicleName = vehicle.name,
                label = label,
                lat = lat,
                lng = lng,
                disconnectedAt = System.currentTimeMillis(),
            )
            val notifId = raise(context, draft)
            PendingParkingSuggestionStore.openWindow(context, draft.copy(notificationId = notifId))
            Log.i(TAG, "raised immediate walk-away suggestion for vehicle=${vehicle.name} (fix=${lat != null})")
            // The service is already running (the "bluetooth" reason keeps it
            // alive whenever the master switch is on), but its location watch
            // only starts for a parking session — nudge it to start now, so a
            // still-driving retraction (see abort()) has fixes to work from.
            ParkingForegroundService.onWalkAwayWindowChanged(context)
        } catch (e: Exception) {
            Log.w(TAG, "maybeOpenWindow failed (non-fatal)", e)
        }
    }

    private fun eligible(v: NativeVehicle): Boolean =
        v.walkAwaySuggest && !v.bluetoothAutoStart && !v.hasParking

    /**
     * Names the specific condition that stopped a window from opening. Every
     * one of these is a legitimate "nothing to do", not a fault — which is
     * exactly why saying so matters: without it, a correct decline and a broken
     * detector produce the same empty log.
     */
    private fun declineReason(label: String, linked: List<NativeVehicle>): String {
        if (linked.isEmpty()) {
            return "disconnect from \"$label\" ignored — no vehicle is linked to this device"
        }
        val why = linked.joinToString("; ") { v ->
            when {
                !v.walkAwaySuggest    -> "${v.name}: \"הצע חניה עם ניתוק\" is off for this vehicle"
                v.bluetoothAutoStart  -> "${v.name}: auto-start is on, so a parking is saved outright instead"
                v.hasParking          -> "${v.name}: it already has an active parking"
                else                  -> "${v.name}: eligible"
            }
        }
        return "disconnect from \"$label\" — no walk-away window opened ($why)"
    }

    /**
     * Raises the suggestion: persists it for JS to turn into a confirmation on
     * its next resume, and posts a notification that can answer it from the
     * shade without opening the app at all. Returns the notification's id (0
     * if it could not be shown) so the caller can store it on the window for a
     * possible later [abort].
     *
     * Never saves a parking itself — like GPS's SuggestEnd, this only ever asks.
     */
    private fun raise(context: Context, window: PendingParkingSuggestionStore.Window): Int {
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
                "Bluetooth disconnected from ${window.vehicleName} — offering immediately to save the parking spot" +
                    if (window.lat == null) " (no location fix captured yet)" else "",
            )
            // Same headless path as every other notification button (CLAUDE.md,
            // "One headless path, not two"). The button carries no coordinates:
            // the location lives in the store entry just written, which both the
            // live path and the killed-app replay read — so the spot saved is
            // where the car actually is, not wherever the user is standing when
            // they tap, and PendingWidgetAction needs no new fields to carry a
            // location through the replay.
            return BackgroundAlertNotifier.show(
                context,
                "🅿️ לשמור את החניה?",
                "${window.vehicleName} — התנתקת מהרכב. לשמור את החניה כאן?",
                listOf(
                    BackgroundAlertNotifier.Action("שמור חניה", WidgetActionReceiver.ACTION_SAVE_AT, window.vehicleId),
                    BackgroundAlertNotifier.Action("לא עכשיו", WidgetActionReceiver.ACTION_DISMISS_WALK, null),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "raise failed (non-fatal)", e)
            return 0
        }
    }

    /**
     * Retracts an already-raised suggestion: cancels its shade notification and
     * clears the pending entry JS would otherwise turn into an in-app modal on
     * its next resume, then closes the window. Called when a location fix
     * proves the car never actually stopped here (WalkAwayEngine's [Abort][
     * com.ohadsam.findmycar.core.WalkAwayDecision.Abort] — the link dropped
     * mid-drive, or the window simply ran out) or the vehicle reconnects (they
     * got back in). Only acts if the pending suggestion is still the one this
     * window raised — it may already have been answered from the shade.
     */
    fun abort(context: Context, window: PendingParkingSuggestionStore.Window, reason: String) {
        try {
            val pending = PendingParkingSuggestionStore.get(context)
            if (pending != null && pending.vehicleId == window.vehicleId && pending.timestamp == window.disconnectedAt) {
                PendingParkingSuggestionStore.clear(context)
                BackgroundAlertNotifier.cancel(context, window.notificationId)
                Log.i(TAG, "withdrew walk-away suggestion for ${window.vehicleName} ($reason)")
                NativeLogStore.add(context, TAG, "WALK", "withdrew the parking suggestion for ${window.vehicleName} — $reason")
            }
            closeWindow(context, reason)
        } catch (e: Exception) {
            Log.w(TAG, "abort failed (non-fatal)", e)
        }
    }

    /** Reconnecting means they got back in — nothing left to ask about. */
    fun cancelOnReconnect(context: Context) {
        val window = PendingParkingSuggestionStore.getWindow(context) ?: return
        abort(context, window, "reconnected to the vehicle")
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
