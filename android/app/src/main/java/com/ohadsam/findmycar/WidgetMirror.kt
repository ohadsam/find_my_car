package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.widgets.WidgetStatusRefresher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies a parking-state change to the native mirror immediately, at the
 * moment an action is *accepted* rather than when JS eventually gets round to
 * replaying it.
 *
 * Why this exists. The widgets and the persistent parking notification render
 * exclusively from WidgetDataPlugin's SharedPreferences, and until now only
 * js/widget-bridge.js ever wrote to it. That is correct while the page is
 * alive, and silently wrong the moment it is not: an action queued to
 * PendingBtActionStore/PendingWidgetActionStore is performed for real only on
 * the next app open, so between the two the widget kept showing the state the
 * action had already changed. A real report: "end" tapped from the shade at
 * 16:13, replayed at 16:35 when the app was next opened, and for those 22
 * minutes every widget still showed the car as parked, with nothing anywhere
 * saying otherwise.
 *
 * Native cannot read the WebView's localStorage — the real parking records live
 * there and JS remains the only thing that ever writes one (see CLAUDE.md). But
 * the mirror is not a parking record: it is a display cache that native already
 * owns and writes. So the fix is to keep that cache honest at the instant the
 * decision is made, and let the replay reconcile it with the real records
 * later. The two always converge, because the replay ends in a full
 * WidgetBridge.sync().
 *
 * What is deliberately NOT done here: fabricating anything JS would have to
 * believe. An optimistic "save" carries the last known fix and a blank address
 * — exactly what js/app.js itself writes before reverse geocoding resolves —
 * never an invented address. And KEY_PENDING_SYNC_AT marks the mirror as
 * "applied locally, not yet confirmed", which the widget shows as ⏳ rather
 * than presenting a guess as a fact (same discipline as the liveness dots'
 * four states and the setup guide's "cannot verify" badge).
 *
 * Suggestions (GPS drive-away, walk-away) never come through here: they change
 * no state until the user answers them.
 */
object WidgetMirror {
    private const val TAG = "FMC-WidgetMirror"

    /** When set, the mirror holds a change JS has not yet confirmed. */
    const val KEY_PENDING_SYNC_AT = "pending_sync_at"

    /**
     * @param action one of "end" / "save" / "swap" / "saveAt". Anything else is
     *   ignored — an unknown action must leave the mirror untouched rather than
     *   guess at its effect.
     * @param vehicleId the vehicle the action targets, or null for "whichever
     *   is active" (Quick Save's main tap never names one).
     */
    fun applyQueuedAction(context: Context, action: String, vehicleId: String?) {
        try {
            val parked = when (action) {
                "end" -> false
                "save", "swap", "saveAt" -> true
                else -> {
                    Log.i(TAG, "no mirror effect defined for action=$action — leaving it untouched")
                    return
                }
            }
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val activeId = prefs.getString(WidgetDataPlugin.KEY_ACTIVE_VEHICLE_ID, "") ?: ""
            val targetId = if (vehicleId.isNullOrBlank()) activeId else vehicleId
            if (targetId.isBlank()) return

            val (lat, lng) = if (parked) LastKnownLocation.get(context) else (null to null)
            val updated = applyToVehiclesJson(
                prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]",
                targetId, parked, lat, lng,
            )

            val edit = prefs.edit()
                .putString(WidgetDataPlugin.KEY_VEHICLES_JSON, updated.json)
                .putLong(KEY_PENDING_SYNC_AT, System.currentTimeMillis())

            // The single-vehicle snapshot (and with it the persistent parking
            // notification) only ever describes the ACTIVE vehicle, so it must
            // not be touched when some other vehicle's parking changed.
            if (targetId == activeId) {
                edit.putBoolean(WidgetDataPlugin.KEY_HAS_PARKING, parked)
                if (parked) {
                    edit.putString(WidgetDataPlugin.KEY_ADDRESS, "")
                        .putFloat(WidgetDataPlugin.KEY_LAT, (lat ?: 0.0).toFloat())
                        .putFloat(WidgetDataPlugin.KEY_LNG, (lng ?: 0.0).toFloat())
                        .putString(WidgetDataPlugin.KEY_TIMESTAMP, "")
                    if (updated.name.isNotBlank()) edit.putString(WidgetDataPlugin.KEY_VEHICLE_NAME, updated.name)
                    if (updated.icon.isNotBlank()) edit.putString(WidgetDataPlugin.KEY_VEHICLE_ICON, updated.icon)
                }
            }
            edit.apply()

            if (targetId == activeId) {
                // Keep the shade consistent with the widgets — a parking
                // notification left standing after the parking was ended from
                // that very notification is the same staleness by another route.
                if (parked) WidgetDataPlugin.showParkingNotification(context, "")
                else WidgetDataPlugin.cancelParkingNotification(context)
            }
            // Safe to call from a broadcast receiver: Android grants a
            // short-lived foreground-service-start exemption when the user has
            // just acted on a widget or a notification button, which is the
            // only way to reach here — and setReasonActive() has its own
            // try/catch for the case where it doesn't.
            ParkingForegroundService.setReasonActive(context, "parking", anyParked(updated.json))

            WidgetDataPlugin.refreshDataWidgets(context)
            WidgetStatusRefresher.refreshAll(context)
            NativeLogStore.add(
                context, TAG, "WIDGET",
                "mirror updated locally for queued action=\"$action\" (vehicle=$targetId, parked=$parked) — " +
                    "widgets now match what was tapped; JS reconciles on next open",
            )
        } catch (e: Exception) {
            // A display cache must never take down the action it describes.
            Log.w(TAG, "applyQueuedAction failed (non-fatal)", e)
        }
    }

    /**
     * Called from every JS-driven write (syncVehicles/update/clear) — once the
     * real state has been pushed, the mirror is confirmed and the ⏳ marker has
     * to go, whether or not this particular sync was the replay of that action.
     */
    fun clearPendingSync(context: Context) {
        try {
            context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_SYNC_AT).apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearPendingSync failed (non-fatal)", e)
        }
    }

    fun hasPendingSync(context: Context): Boolean = try {
        context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PENDING_SYNC_AT, 0L) > 0L
    } catch (e: Exception) {
        false
    }

    private data class Updated(val json: String, val name: String, val icon: String)

    /**
     * Rewrites one vehicle's parking fields in the mirrored list, leaving every
     * other vehicle and every unrelated field exactly as JS last wrote them —
     * this is a patch, never a regeneration, because native does not know most
     * of what is in here (BT settings, daily-status opt-in, walk-away opt-in).
     */
    private fun applyToVehiclesJson(
        json: String, vehicleId: String, parked: Boolean, lat: Double?, lng: Double?,
    ): Updated {
        val arr = JSONArray(json)
        var name = ""
        var icon = ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != vehicleId) continue
            name = o.optString("name", "")
            icon = o.optString("icon", "🚗")
            o.put("hasParking", parked)
            if (parked) {
                // Blank address on purpose: the real one only exists once
                // reverse geocoding has run, which needs JS. The widget already
                // renders a blank address as "מיקום נשמר".
                o.put("address", "")
                o.put("lat", lat ?: JSONObject.NULL)
                o.put("lng", lng ?: JSONObject.NULL)
                o.put("timestamp", JSONObject.NULL)
            }
            break
        }
        return Updated(arr.toString(), name, icon)
    }

    /** True while any mirrored vehicle still has a parking — the foreground
     *  service's "parking" reason tracks that, not just the active vehicle. */
    private fun anyParked(json: String): Boolean = try {
        val arr = JSONArray(json)
        (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("hasParking", false) == true }
    } catch (e: Exception) {
        true // never tear the watch down on a parse failure
    }
}
