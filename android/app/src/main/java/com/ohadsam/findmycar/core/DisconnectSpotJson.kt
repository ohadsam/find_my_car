package com.ohadsam.findmycar.core

import org.json.JSONArray
import org.json.JSONObject

/** (De)serializes the per-vehicle [DisconnectSpot] list for DisconnectSpotStore and the JS bridge. */
object DisconnectSpotJson {
    fun toJson(spots: List<DisconnectSpot>): String {
        val arr = JSONArray()
        spots.forEach { s ->
            val o = JSONObject()
            o.put("vehicleId", s.vehicleId)
            o.put("vehicleName", s.vehicleName)
            o.put("label", s.label)
            o.put("lat", s.lat)
            o.put("lng", s.lng)
            if (s.accuracy != null) o.put("accuracy", s.accuracy)
            o.put("at", s.at)
            o.put("source", s.source)
            arr.put(o)
        }
        return arr.toString()
    }

    fun parseList(json: String): List<DisconnectSpot> {
        return try {
            if (json.isBlank()) return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("vehicleId", "")
                // has() first: optDouble's fallback is NaN, which must never
                // become a coordinate the app would save a parking at.
                if (id.isBlank() || !o.has("lat") || !o.has("lng")) return@mapNotNull null
                val lat = o.optDouble("lat")
                val lng = o.optDouble("lng")
                if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
                val acc = if (o.has("accuracy") && !o.isNull("accuracy")) o.optDouble("accuracy") else null
                DisconnectSpot(
                    vehicleId = id,
                    vehicleName = o.optString("vehicleName", ""),
                    label = o.optString("label", ""),
                    lat = lat, lng = lng,
                    accuracy = if (acc != null && !acc.isNaN()) acc else null,
                    at = o.optLong("at", 0L),
                    source = o.optString("source", "disconnect"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
