package com.ohadsam.findmycar.core

import org.json.JSONObject

/**
 * Serializes/parses a single (nullable) PendingGpsSuggestion to/from JSON,
 * for PendingGpsSuggestionStore's SharedPreferences-backed persistence.
 * Kept separate from PendingGpsSuggestion itself so the pure data class has
 * zero org.json dependency — mirrors PendingBtActionJson's split for the
 * same reason (only this layer needs Robolectric to test). A plain JSON
 * object (not an array, unlike PendingBtActionJson) since at most one
 * suggestion is ever outstanding at a time.
 */
object PendingGpsSuggestionJson {
    fun toJson(suggestion: PendingGpsSuggestion?): String {
        if (suggestion == null) return "null"
        val o = JSONObject()
        o.put("vehicleId", suggestion.vehicleId)
        o.put("vehicleName", suggestion.vehicleName)
        o.put("timestamp", suggestion.timestamp)
        return o.toString()
    }

    fun parse(json: String): PendingGpsSuggestion? {
        return try {
            if (json.isBlank() || json == "null") return null
            val o = JSONObject(json)
            val vehicleId = o.optString("vehicleId", "")
            if (vehicleId.isBlank()) return null
            PendingGpsSuggestion(
                vehicleId = vehicleId,
                vehicleName = o.optString("vehicleName", ""),
                timestamp = o.optLong("timestamp", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}
