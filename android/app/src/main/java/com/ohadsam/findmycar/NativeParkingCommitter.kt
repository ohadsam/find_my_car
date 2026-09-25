package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.NativeParkingOp
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Saves and ends parkings natively, at the moment they happen, while the app
 * is not open — the record itself, not a request for JS to decide later.
 *
 * Before this, a background event only queued an *action*, which JS replayed
 * on its next resume: it re-decided, took a live GPS fix and stamped the
 * parking with the time the app was opened. Every one of those described the
 * moment of opening rather than the moment of parking. Now the parking's id,
 * time, location, Bluetooth device and (via NativeGeocoder) address are fixed
 * here, written to NativeParkingStore, and mirrored to the widgets and the
 * parking notification at once; js/app.js adopts the record verbatim.
 *
 * The parking *records* still end up in WebView storage (with photos, voice
 * and history, which only JS handles) — native cannot write there directly,
 * which is why adoption exists. What native owns is the decision, the data and
 * everything the user sees in the meantime.
 */
object NativeParkingCommitter {
    private const val TAG = "FMC-NativeParking"

    data class Vehicle(val id: String, val name: String, val icon: String, val parked: Boolean, val parkingId: String?) {
        val label: String get() = "${icon.ifBlank { "🚗" }} $name".trim()
    }

    /** The mirrored vehicle, or null. A null [vehicleId] means the active one. */
    fun vehicle(context: Context, vehicleId: String?): Vehicle? {
        return try {
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val id = if (vehicleId.isNullOrBlank()) prefs.getString(WidgetDataPlugin.KEY_ACTIVE_VEHICLE_ID, "") ?: "" else vehicleId
            if (id.isBlank()) return null
            val arr = JSONArray(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id", "") != id) continue
                val parkingId = if (o.isNull("parkingId")) null else o.optString("parkingId", "").ifBlank { null }
                return Vehicle(id, o.optString("name", ""), o.optString("icon", "🚗"), o.optBoolean("hasParking", false), parkingId)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves a new parking for [vehicleId] (null = active) at the given spot.
     * Refuses (returns null) if that vehicle is already parked — the same rule
     * performWidgetAction('save') applies; a swap ends first, explicitly.
     */
    fun commitStart(
        context: Context, vehicleId: String?, lat: Double, lng: Double, accuracy: Double?,
        source: String, btDevice: String? = null,
    ): Vehicle? {
        val v = vehicle(context, vehicleId)
        if (v == null) {
            log(context, "save not committed — unknown vehicle ($vehicleId)")
            return null
        }
        if (v.parked) {
            log(context, "save not committed for ${v.name} — it already has an active parking")
            return null
        }
        val now = System.currentTimeMillis()
        val op = NativeParkingOp(
            opId = UUID.randomUUID().toString(),
            type = NativeParkingOp.START,
            vehicleId = v.id,
            parkingId = UUID.randomUUID().toString(),
            at = now,
            source = source,
            lat = lat, lng = lng, accuracy = accuracy,
            btDevice = btDevice,
        )
        NativeParkingStore.add(context, op)
        WidgetMirror.applyQueuedAction(
            context, "save", v.id, lat, lng,
            parkingId = op.parkingId, timestamp = iso(now), opId = op.opId,
        )
        val spot = String.format(Locale.US, "%.5f, %.5f", lat, lng)
        log(context, "parking SAVED natively for ${v.name} ($source) at $spot")
        return v
    }

    /** Ends [vehicleId]'s (null = active) current parking. Returns null if it had none. */
    fun commitEnd(context: Context, vehicleId: String?, source: String, btDevice: String? = null): Vehicle? {
        val v = vehicle(context, vehicleId) ?: return null
        if (!v.parked) {
            log(context, "end not committed for ${v.name} — it has no active parking")
            return null
        }
        NativeParkingStore.add(
            context,
            NativeParkingOp(
                opId = UUID.randomUUID().toString(),
                type = NativeParkingOp.END,
                vehicleId = v.id,
                parkingId = v.parkingId,
                at = System.currentTimeMillis(),
                source = source,
                btDevice = btDevice,
            ),
        )
        WidgetMirror.applyQueuedAction(context, "end", v.id)
        log(context, "parking ENDED natively for ${v.name} ($source)")
        return v
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun log(context: Context, message: String) {
        Log.i(TAG, message)
        NativeLogStore.add(context, TAG, "WIDGET", message)
    }
}
