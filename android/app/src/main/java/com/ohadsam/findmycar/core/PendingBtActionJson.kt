package com.ohadsam.findmycar.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes/parses a list of PendingBtAction to/from JSON, for
 * PendingBtActionStore's SharedPreferences-backed persistence. Kept
 * separate from PendingBtAction itself so the pure data class has zero
 * org.json dependency — mirrors VehicleJsonParser's split for the same
 * reason (only this layer needs Robolectric to test).
 */
object PendingBtActionJson {
    fun toJson(actions: List<PendingBtAction>): String {
        val arr = JSONArray()
        for (a in actions) {
            val o = JSONObject()
            o.put("direction", a.direction)
            o.put("action", a.action)
            o.put("vehicleId", a.vehicleId)
            o.put("vehicleName", a.vehicleName)
            o.put("label", a.label)
            if (a.lat != null) o.put("lat", a.lat) else o.put("lat", JSONObject.NULL)
            if (a.lng != null) o.put("lng", a.lng) else o.put("lng", JSONObject.NULL)
            o.put("timestamp", a.timestamp)
            arr.put(o)
        }
        return arr.toString()
    }

    fun parse(json: String): List<PendingBtAction> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val vehicleId = o.optString("vehicleId", "")
                if (vehicleId.isBlank()) return@mapNotNull null
                PendingBtAction(
                    direction = o.optString("direction", ""),
                    action = o.optString("action", ""),
                    vehicleId = vehicleId,
                    vehicleName = o.optString("vehicleName", ""),
                    label = o.optString("label", ""),
                    lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                    lng = if (o.isNull("lng")) null else o.optDouble("lng"),
                    timestamp = o.optLong("timestamp", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
