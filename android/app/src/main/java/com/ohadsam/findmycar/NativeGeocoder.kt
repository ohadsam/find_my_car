package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.NominatimAddressJson
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * Resolves the address of a parking that native queued while the app was
 * closed (a widget save, a Bluetooth auto-start, a walk-away "save"), so the
 * widgets and the parking notification show the street instead of
 * "מיקום נשמר" until the app is next opened.
 *
 * The answer goes to two places: the natively-committed parking record in
 * NativeParkingStore (so JS adopts it with its address already filled in), and
 * the display mirror — the latter only while the mirror is still native's own
 * unconfirmed write (WidgetMirror.hasPendingSync) for the same coordinates.
 * Once JS has adopted/synced, JS owns the address, and a late native answer
 * must not overwrite it.
 */
object NativeGeocoder {
    private const val TAG = "FMC-Geocoder"
    private val RETRY_DELAYS_MS = longArrayOf(0L, 5_000L, 20_000L) // same as CFG.geocodeRetryDelaysMs
    private const val TIMEOUT_MS = 12_000

    /** @param opId the NativeParkingStore record to fill in, when there is one. */
    fun resolveForMirror(context: Context, vehicleId: String, lat: Double, lng: Double, opId: String? = null) {
        val app = context.applicationContext
        Thread { runLookup(app, vehicleId, lat, lng, opId) }.start()
    }

    private fun runLookup(app: Context, vehicleId: String, lat: Double, lng: Double, opId: String?) {
        try {
            for (delay in RETRY_DELAYS_MS) {
                if (delay > 0) Thread.sleep(delay)
                val recordPending = opId != null && NativeParkingStore.contains(app, opId)
                if (!recordPending && !WidgetMirror.hasPendingSync(app)) return // JS took over
                val result = lookup(lat, lng)
                val address = (result as? NominatimAddressJson.Result.Found)?.address
                val text = when (result) {
                    is NominatimAddressJson.Result.Found -> result.address.display
                    NominatimAddressJson.Result.None -> String.format(Locale.US, "%.5f, %.5f", lat, lng)
                    NominatimAddressJson.Result.Failed -> null
                } ?: continue
                if (opId != null) NativeParkingStore.patchAddress(app, opId, address)
                apply(app, vehicleId, lat, lng, text)
                NativeLogStore.add(app, TAG, "WIDGET", "resolved the address of the natively-saved parking: $text")
                return
            }
            NativeLogStore.add(app, TAG, "WIDGET", "native address lookup failed ${RETRY_DELAYS_MS.size} times — the app resolves it when opened")
        } catch (e: Exception) {
            Log.w(TAG, "resolveForMirror failed (non-fatal)", e)
        }
    }

    private fun lookup(lat: Double, lng: Double): NominatimAddressJson.Result {
        return try {
            val url = URL(String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=json&addressdetails=1&zoom=18&lat=%.6f&lon=%.6f",
                lat, lng,
            ))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept-Language", "he,en")
                // Nominatim's usage policy requires an identifying User-Agent.
                setRequestProperty("User-Agent", "FindMyCar-Android (github.com/ohadsam/find_my_car)")
            }
            try {
                if (conn.responseCode !in 200..299) return NominatimAddressJson.Result.Failed
                NominatimAddressJson.parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            NominatimAddressJson.Result.Failed
        }
    }

    @Synchronized
    private fun apply(context: Context, vehicleId: String, lat: Double, lng: Double, text: String) {
        val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        if (!WidgetMirror.hasPendingSync(context)) return
        val edit = prefs.edit()
        val arr = JSONArray(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != vehicleId) continue
            if (abs(o.optDouble("lat", Double.NaN) - lat) > 1e-6 || abs(o.optDouble("lng", Double.NaN) - lng) > 1e-6) break
            o.put("address", text)
            changed = true
            break
        }
        if (changed) edit.putString(WidgetDataPlugin.KEY_VEHICLES_JSON, arr.toString())

        val activeId = prefs.getString(WidgetDataPlugin.KEY_ACTIVE_VEHICLE_ID, "") ?: ""
        // KEY_LAT/KEY_LNG are floats, so compare loosely.
        val sameSpot = abs(prefs.getFloat(WidgetDataPlugin.KEY_LAT, 0f) - lat) < 1e-4 &&
            abs(prefs.getFloat(WidgetDataPlugin.KEY_LNG, 0f) - lng) < 1e-4
        val activeSnapshot = vehicleId == activeId && prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false) && sameSpot
        if (activeSnapshot) edit.putString(WidgetDataPlugin.KEY_ADDRESS, text)
        if (!changed && !activeSnapshot) return
        edit.apply()
        if (activeSnapshot) WidgetDataPlugin.showParkingNotification(context, text)
        WidgetDataPlugin.refreshDataWidgets(context)
    }
}
