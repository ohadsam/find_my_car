package com.ohadsam.findmycar.widgets

import org.json.JSONArray

/** One vehicle with a currently active parking session, as shown on a widget. */
data class ParkedVehicle(
    val id: String,
    val name: String,
    val icon: String,
    val address: String,
    val lat: Double,
    val lng: Double,
)

/**
 * Parses the subset of js/widget-bridge.js's syncVehicles() payload that
 * ActiveParkingWidgetProvider/MiniMapWidgetProvider need to show more than
 * one simultaneously-parked vehicle — filters to vehicles with
 * hasParking=true. Independent, minimal parsing of the same
 * WidgetDataPlugin.KEY_VEHICLES_JSON mirror WidgetQuickActionsActivity
 * already parses for its own (different) purposes — same established
 * pattern of multiple independent readers, each parsing only what they
 * need, rather than a shared model everyone must agree on.
 */
object ParkedVehicles {
    fun parse(json: String): List<ParkedVehicle> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.optBoolean("hasParking", false)) return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isBlank()) return@mapNotNull null
                ParkedVehicle(
                    id = id,
                    name = o.optString("name", ""),
                    icon = o.optString("icon", "🚗"),
                    address = o.optString("address", ""),
                    lat = o.optDouble("lat", 0.0),
                    lng = o.optDouble("lng", 0.0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
