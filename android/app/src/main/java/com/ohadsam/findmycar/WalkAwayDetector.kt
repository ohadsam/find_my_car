package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.DisconnectFixPolicy
import com.ohadsam.findmycar.core.DisconnectSpot
import com.ohadsam.findmycar.core.ParkSpotDecision
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
    // Upper bound on how long a window may stay open however the tracker's own
    // rolling expiry is going (a long drive after a mid-trip drop extends it).
    private const val HARD_CAP_MS = 2 * 60 * 60 * 1000L

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

            // The spot is sampled NOW, at the disconnect — the notification is
            // answered later, from wherever the user has walked to, and what
            // gets saved is this, never the position at the tap. A cached fix is
            // only trusted when recent: the system's cache can be hours old and
            // from elsewhere, and saving that silently is worse than asking
            // again. With no usable cache the first location update after the
            // disconnect fills it in (see recordSpotFromUpdate).
            val cached = LastKnownLocation.getFix(context)
            val now = System.currentTimeMillis()
            val usable = cached?.takeIf { DisconnectFixPolicy.acceptCached(now - it.time) }
            val lat = usable?.lat
            val lng = usable?.lng
            when {
                usable != null -> NativeLogStore.add(
                    context, TAG, "WALK",
                    "spot sampled at the disconnect: cached fix ${(now - usable.time) / 1000}s old, accuracy ${usable.accuracy.toInt()}m",
                )
                cached != null -> NativeLogStore.add(
                    context, TAG, "WALK",
                    "cached location is ${(now - cached.time) / 60000} min old — NOT used as the parking spot; waiting for a fresh fix",
                )
                else -> NativeLogStore.add(context, TAG, "WALK", "no cached location at the disconnect — waiting for a fresh fix")
            }
            val draft = PendingParkingSuggestionStore.Window(
                vehicleId = vehicle.id,
                vehicleName = vehicle.name,
                label = label,
                lat = lat,
                lng = lng,
                disconnectedAt = now,
            )
            // The previous spot for this vehicle is superseded by this
            // disconnect, whether or not a new fix is known yet. The new one is
            // remembered independently of the notification, so the app can still
            // offer it after the question is ignored or withdrawn.
            DisconnectSpotStore.remove(context, vehicle.id)
            if (usable != null) rememberSpot(context, draft, usable.accuracy.toDouble(), "disconnect")
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
    private fun raise(context: Context, window: PendingParkingSuggestionStore.Window, body: String? = null): Int {
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
                (if (body == null) "Bluetooth disconnected from ${window.vehicleName} — offering immediately to save the parking spot"
                else "asking again about ${window.vehicleName} at the point where the car stopped") +
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
                "🅿️ ${window.vehicleName} — לשמור את החניה?",
                body ?: "התנתקת מ-${window.vehicleName}. לשמור את החניה שלו כאן?",
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
     * Offers a location update to the open window as the parking spot. Called
     * for every update while a window is open; [DisconnectFixPolicy] adopts only
     * the first good one received soon after the disconnect — the user is still
     * next to the car — and nothing after that, since later updates are them
     * walking away. Writes the spot to BOTH the window and the pending
     * suggestion: the latter is what "שמור חניה" actually reads, and updating
     * only the window (what the old first-update fallback did) left a
     * suggestion raised with no fix permanently without one.
     *
     * @return the window to use from here on (updated when the update was adopted)
     */
    fun recordSpotFromUpdate(
        context: Context, window: PendingParkingSuggestionStore.Window,
        lat: Double, lng: Double, accuracyM: Float, now: Long,
    ): PendingParkingSuggestionStore.Window {
        try {
            val since = now - window.disconnectedAt
            val hasSpot = window.lat != null && window.lng != null
            if (!DisconnectFixPolicy.adoptUpdate(hasSpot, window.fixFresh, since, accuracyM)) return window

            val updated = window.copy(lat = lat, lng = lng, fixFresh = DisconnectFixPolicy.isFresh(accuracyM))
            PendingParkingSuggestionStore.openWindow(context, updated)
            val pending = PendingParkingSuggestionStore.get(context)
            // Only the suggestion this window raised — it may already have been
            // answered or replaced by a newer disconnect.
            if (pending != null && pending.vehicleId == window.vehicleId && pending.timestamp == window.disconnectedAt) {
                PendingParkingSuggestionStore.set(context, pending.copy(lat = lat, lng = lng))
            }
            rememberSpot(context, updated, accuracyM.toDouble(), "disconnect")
            NativeLogStore.add(
                context, TAG, "WALK",
                "parking spot for ${window.vehicleName} taken from the first fix ${since / 1000}s after the disconnect " +
                    "(accuracy ${accuracyM.toInt()}m)" + if (hasSpot) " — replaces the cached one" else "",
            )
            return updated
        } catch (e: Exception) {
            Log.w(TAG, "recordSpotFromUpdate failed (non-fatal)", e)
            return window
        }
    }

    private fun rememberSpot(context: Context, w: PendingParkingSuggestionStore.Window, accuracy: Double?, source: String, at: Long = w.disconnectedAt) {
        val lat = w.lat ?: return
        val lng = w.lng ?: return
        DisconnectSpotStore.upsert(
            context, DisconnectSpot(w.vehicleId, w.vehicleName, w.label, lat, lng, accuracy, at, source),
        )
    }

    private fun withdrawQuestion(context: Context, window: PendingParkingSuggestionStore.Window): Boolean {
        val pending = PendingParkingSuggestionStore.get(context)
        // Only the suggestion this window raised — it may already have been
        // answered from the shade or replaced by a newer disconnect.
        if (pending == null || pending.vehicleId != window.vehicleId || pending.timestamp != window.disconnectedAt) return false
        PendingParkingSuggestionStore.clear(context)
        BackgroundAlertNotifier.cancel(context, window.notificationId)
        return true
    }

    /**
     * The phone moved at vehicle speed after the disconnect: the question asked
     * at the disconnect ("save the parking here?") is wrong, and so is the spot.
     * Withdraws the notification and forgets the spot, but — unlike [abort] —
     * leaves the window OPEN. The link may have dropped mid-drive (a fault,
     * Bluetooth switched off, a tunnel); the car will still park somewhere, and
     * ParkSpotTracker keeps looking for where. Closing here is what used to
     * lose that parking altogether.
     */
    fun retract(context: Context, window: PendingParkingSuggestionStore.Window, reason: String) {
        try {
            if (withdrawQuestion(context, window)) {
                Log.i(TAG, "withdrew walk-away suggestion for ${window.vehicleName} ($reason)")
                NativeLogStore.add(context, TAG, "WALK", "withdrew the parking suggestion for ${window.vehicleName} — $reason; still watching for where it stops")
            } else {
                NativeLogStore.add(context, TAG, "WALK", "${window.vehicleName} is moving at vehicle speed — $reason; still watching for where it stops")
            }
            DisconnectSpotStore.remove(context, window.vehicleId)
            PendingParkingSuggestionStore.openWindow(context, window.copy(notificationId = 0, lat = null, lng = null, fixFresh = true))
        } catch (e: Exception) {
            Log.w(TAG, "retract failed (non-fatal)", e)
        }
    }

    /**
     * Nothing conclusive within the allowed time. The shade notification and the
     * pending in-app question are taken back (a question hours later is about a
     * decision already made), but the remembered spot is KEPT: not getting round
     * to answering is not the same as the car having moved, and the app still
     * offers "save it where Bluetooth disconnected" — the whole point of keeping it.
     */
    fun giveUp(context: Context, window: PendingParkingSuggestionStore.Window, reason: String) {
        try {
            if (withdrawQuestion(context, window)) {
                Log.i(TAG, "withdrew walk-away suggestion for ${window.vehicleName} ($reason)")
                NativeLogStore.add(context, TAG, "WALK", "withdrew the parking suggestion for ${window.vehicleName} — $reason (the spot stays available in the app)")
            }
            closeWindow(context, reason)
        } catch (e: Exception) {
            Log.w(TAG, "giveUp failed (non-fatal)", e)
        }
    }

    /**
     * The user walked away from a spot where the car stopped. Three outcomes:
     *  - the vehicle opted in to automatic saving: save there now;
     *  - the spot is the disconnect fix: the question already showing is right,
     *    nothing to add;
     *  - the car kept moving first (the old question was withdrawn): ask again,
     *    now at the real stopping point, by opening a fresh window there.
     */
    fun onParked(context: Context, window: PendingParkingSuggestionStore.Window, decision: ParkSpotDecision.Parked) {
        try {
            val vehicle = VehicleJsonParser.parse(
                context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                    .getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]",
            ).firstOrNull { it.id == window.vehicleId }
            if (vehicle == null || vehicle.hasParking) {
                giveUp(context, window, "the vehicle is gone or already parked")
                return
            }
            if (vehicle.walkAwayAuto) {
                autoSave(context, window, decision)
                return
            }
            if (!decision.reanchored) {
                // The disconnect spot was right all along.
                closeWindow(context, "walking away confirmed — the suggestion is already showing")
                return
            }
            val fresh = PendingParkingSuggestionStore.Window(
                vehicleId = window.vehicleId, vehicleName = window.vehicleName, label = window.label,
                lat = decision.lat, lng = decision.lng,
                disconnectedAt = System.currentTimeMillis(), fixFresh = true,
            )
            val notifId = raise(context, fresh, "זוהתה חניה ב${window.vehicleName}. לשמור אותה?")
            PendingParkingSuggestionStore.openWindow(context, fresh.copy(notificationId = notifId))
            rememberSpot(context, fresh, null, "stop", at = decision.at)
            NativeLogStore.add(context, TAG, "WALK", "the car stopped and the user walked away — asking again at the stopping point")
        } catch (e: Exception) {
            Log.w(TAG, "onParked failed (non-fatal)", e)
        }
    }

    private fun autoSave(context: Context, window: PendingParkingSuggestionStore.Window, decision: ParkSpotDecision.Parked) {
        // Pending/notification first: whatever happens next, the question is answered.
        withdrawQuestion(context, window)
        val saved = NativeParkingCommitter.commitStart(
            context, window.vehicleId, decision.lat, decision.lng, null, "walkAway",
            btDevice = window.label.ifBlank { null }, at = decision.at,
        )
        closeWindow(context, if (saved != null) "parking saved automatically" else "automatic save was refused")
        if (saved == null) return
        NativeLogStore.add(
            context, TAG, "WALK",
            "automatically saved the parking for ${saved.name} after the user walked away from where " +
                (if (decision.reanchored) "the car stopped" else "Bluetooth disconnected"),
        )
        BackgroundAlertNotifier.show(
            context, "🅿️ ${saved.label}",
            "החניה נשמרה אוטומטית אחרי שהתרחקת מהרכב. טעות? אפשר לבטל.",
            listOf(BackgroundAlertNotifier.Action("בטל", "end", saved.id)),
        )
    }

    /**
     * Retracts an already-raised suggestion and closes the window: cancels its
     * shade notification and clears the pending entry JS would otherwise turn
     * into an in-app modal. Used when the vehicle reconnects (they got back in).
     * Only acts if the pending suggestion is still the one this window raised.
     */
    fun abort(context: Context, window: PendingParkingSuggestionStore.Window, reason: String) {
        try {
            if (withdrawQuestion(context, window)) {
                Log.i(TAG, "withdrew walk-away suggestion for ${window.vehicleName} ($reason)")
                NativeLogStore.add(context, TAG, "WALK", "withdrew the parking suggestion for ${window.vehicleName} — $reason")
            }
            closeWindow(context, reason)
        } catch (e: Exception) {
            Log.w(TAG, "abort failed (non-fatal)", e)
        }
    }

    /**
     * Reconnecting means they got back in — nothing left to ask, and the
     * remembered spot is stale: the car is about to move.
     */
    fun cancelOnReconnect(context: Context, label: String) {
        DisconnectSpotStore.removeByLabel(context, label)
        val window = PendingParkingSuggestionStore.getWindow(context) ?: return
        abort(context, window, "reconnected to the vehicle")
    }

    /**
     * Safety net for a window nobody closed: the expiry in ParkSpotTracker is
     * evaluated per location fix, and a phone indoors may deliver none, which
     * would keep the location watch (and its battery cost) alive indefinitely.
     */
    fun expireStale(context: Context, now: Long = System.currentTimeMillis()) {
        val window = PendingParkingSuggestionStore.getWindow(context) ?: return
        if (now - window.disconnectedAt >= HARD_CAP_MS) giveUp(context, window, "no conclusion within ${HARD_CAP_MS / 3_600_000L} hours")
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
