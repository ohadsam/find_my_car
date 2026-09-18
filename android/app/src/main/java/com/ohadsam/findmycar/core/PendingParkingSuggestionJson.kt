package com.ohadsam.findmycar.core

import org.json.JSONObject

/**
 * Serializes/parses a single (nullable) PendingParkingSuggestion for
 * PendingParkingSuggestionStore's SharedPreferences persistence. Split from the
 * pure data class for the same reason as PendingGpsSuggestionJson: only this
 * layer touches org.json, so only this layer needs Robolectric.
 *
 * lat/lng round-trip as genuinely nullable — `JSONObject.optDouble` returns NaN
 * for a missing key rather than null, so a naive reader would turn "no fix
 * captured" into a NaN coordinate and JS would try to save a parking at it.
 */
object PendingParkingSuggestionJson {
    fun toJson(suggestion: PendingParkingSuggestion?): String {
        if (suggestion == null) return "null"
        val o = JSONObject()
        o.put("vehicleId", suggestion.vehicleId)
        o.put("vehicleName", suggestion.vehicleName)
        o.put("label", suggestion.label)
        if (suggestion.lat != null) o.put("lat", suggestion.lat)
        if (suggestion.lng != null) o.put("lng", suggestion.lng)
        o.put("timestamp", suggestion.timestamp)
        return o.toString()
    }

    fun parse(json: String): PendingParkingSuggestion? {
        return try {
            if (json.isBlank() || json == "null") return null
            val o = JSONObject(json)
            val vehicleId = o.optString("vehicleId", "")
            if (vehicleId.isBlank()) return null
            // has() first: optDouble's own fallback is NaN, which is not the
            // same thing as "no location was captured".
            val lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null
            val lng = if (o.has("lng") && !o.isNull("lng")) o.optDouble("lng") else null
            PendingParkingSuggestion(
                vehicleId = vehicleId,
                vehicleName = o.optString("vehicleName", ""),
                label = o.optString("label", ""),
                lat = if (lat != null && !lat.isNaN()) lat else null,
                lng = if (lng != null && !lng.isNaN()) lng else null,
                timestamp = o.optLong("timestamp", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}
