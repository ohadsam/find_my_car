package com.ohadsam.findmycar

import org.json.JSONArray

/** One vehicle's parking status as shown on the once-daily status notification. */
data class DailyStatusVehicle(
    val name: String,
    val icon: String,
    val hasParking: Boolean,
    val address: String,
)

/**
 * Parses the subset of js/widget-bridge.js's syncVehicles() payload
 * DailyStatusReceiver needs — filtered to vehicles with their own
 * dailyStatusEnabled (default true, matching the JS-side default for
 * vehicles created before this field existed — see js/vehicles.js).
 * Independent, minimal parsing of the same WidgetDataPlugin.KEY_VEHICLES_JSON
 * mirror VehicleJsonParser/ParkedVehicles already parse for their own
 * (different) purposes — same established pattern of multiple independent
 * readers, each parsing only what they need, rather than a shared model
 * everyone must agree on.
 */
object DailyStatusVehicles {
    fun parse(json: String): List<DailyStatusVehicle> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.optBoolean("dailyStatusEnabled", true)) return@mapNotNull null
                DailyStatusVehicle(
                    name = o.optString("name", ""),
                    icon = o.optString("icon", "🚗"),
                    hasParking = o.optBoolean("hasParking", false),
                    address = o.optString("address", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
