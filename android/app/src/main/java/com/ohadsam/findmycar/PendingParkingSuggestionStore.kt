package com.ohadsam.findmycar

import android.content.Context
import com.ohadsam.findmycar.core.PendingParkingSuggestion
import com.ohadsam.findmycar.core.PendingParkingSuggestionJson

/**
 * SharedPreferences-backed persistence for the walk-away parking suggestion,
 * plus the open detection window it comes from.
 *
 * The **window** has to be persisted, not just held in memory: the whole point
 * is to survive the app being backgrounded or its process restarted between the
 * Bluetooth disconnect and the walk that confirms it, which can be minutes
 * apart. ParkingForegroundService's own `activeReasons` taught this the hard
 * way (see CLAUDE.md) — in-memory state does not survive process death, and
 * every restart path would otherwise silently drop the window.
 *
 * At most one of each is ever outstanding: a disconnect from a second vehicle
 * while a window is already open replaces it, since the user can only be
 * walking away from one car at a time.
 *
 * Not unit-tested directly (needs a live Context) — PendingParkingSuggestionJson
 * is the tested layer, same split as PendingGpsSuggestionStore.
 */
object PendingParkingSuggestionStore {
    private const val PREFS = "findmycar_pending_parking_suggestion"
    private const val KEY_JSON = "pending_json"

    private const val KEY_WINDOW_VEHICLE_ID = "window_vehicle_id"
    private const val KEY_WINDOW_VEHICLE_NAME = "window_vehicle_name"
    private const val KEY_WINDOW_LABEL = "window_label"
    private const val KEY_WINDOW_LAT = "window_lat"
    private const val KEY_WINDOW_LNG = "window_lng"
    private const val KEY_WINDOW_HAS_FIX = "window_has_fix"
    private const val KEY_WINDOW_AT = "window_at"
    private const val KEY_WINDOW_NOTIF_ID = "window_notif_id"

    /**
     * An open "did they park and walk away?" window. The suggestion notification
     * fires immediately when this opens (see WalkAwayDetector.maybeOpenWindow) —
     * this window no longer waits for a confirmed walk before asking. It stays
     * open only so a later fix can retract an already-shown notification if it
     * turns out the car never stopped ([notificationId] is what
     * WalkAwayDetector.abort() cancels).
     */
    data class Window(
        val vehicleId: String,
        val vehicleName: String,
        val label: String,
        val lat: Double?,
        val lng: Double?,
        val disconnectedAt: Long,
        val notificationId: Int = 0,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -- the open window ------------------------------------------------

    @Synchronized
    fun openWindow(context: Context, window: Window) {
        prefs(context).edit()
            .putString(KEY_WINDOW_VEHICLE_ID, window.vehicleId)
            .putString(KEY_WINDOW_VEHICLE_NAME, window.vehicleName)
            .putString(KEY_WINDOW_LABEL, window.label)
            .putFloat(KEY_WINDOW_LAT, (window.lat ?: 0.0).toFloat())
            .putFloat(KEY_WINDOW_LNG, (window.lng ?: 0.0).toFloat())
            .putBoolean(KEY_WINDOW_HAS_FIX, window.lat != null && window.lng != null)
            .putLong(KEY_WINDOW_AT, window.disconnectedAt)
            .putInt(KEY_WINDOW_NOTIF_ID, window.notificationId)
            .apply()
    }

    @Synchronized
    fun getWindow(context: Context): Window? {
        val p = prefs(context)
        val id = p.getString(KEY_WINDOW_VEHICLE_ID, "") ?: ""
        if (id.isBlank()) return null
        val hasFix = p.getBoolean(KEY_WINDOW_HAS_FIX, false)
        return Window(
            vehicleId = id,
            vehicleName = p.getString(KEY_WINDOW_VEHICLE_NAME, "") ?: "",
            label = p.getString(KEY_WINDOW_LABEL, "") ?: "",
            lat = if (hasFix) p.getFloat(KEY_WINDOW_LAT, 0f).toDouble() else null,
            lng = if (hasFix) p.getFloat(KEY_WINDOW_LNG, 0f).toDouble() else null,
            disconnectedAt = p.getLong(KEY_WINDOW_AT, 0L),
            notificationId = p.getInt(KEY_WINDOW_NOTIF_ID, 0),
        )
    }

    @Synchronized
    fun closeWindow(context: Context) {
        prefs(context).edit()
            .remove(KEY_WINDOW_VEHICLE_ID)
            .remove(KEY_WINDOW_VEHICLE_NAME)
            .remove(KEY_WINDOW_LABEL)
            .remove(KEY_WINDOW_LAT)
            .remove(KEY_WINDOW_LNG)
            .remove(KEY_WINDOW_HAS_FIX)
            .remove(KEY_WINDOW_AT)
            .remove(KEY_WINDOW_NOTIF_ID)
            .apply()
    }

    // -- the raised suggestion ------------------------------------------

    @Synchronized
    fun set(context: Context, entry: PendingParkingSuggestion) {
        prefs(context).edit().putString(KEY_JSON, PendingParkingSuggestionJson.toJson(entry)).apply()
    }

    @Synchronized
    fun get(context: Context): PendingParkingSuggestion? {
        val json = prefs(context).getString(KEY_JSON, "null") ?: "null"
        return PendingParkingSuggestionJson.parse(json)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().putString(KEY_JSON, "null").apply()
    }
}
