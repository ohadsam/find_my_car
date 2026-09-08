package com.ohadsam.findmycar.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes/parses a list of PendingWidgetAction to/from JSON, for
 * PendingWidgetActionStore's SharedPreferences-backed persistence. Kept
 * separate from PendingWidgetAction itself so the pure data class has zero
 * org.json dependency — mirrors PendingBtActionJson's split for the same
 * reason (only this layer needs Robolectric to test). A list (not a single
 * entry like PendingGpsSuggestionJson) since multiple widget taps could
 * queue up across more than one background period before the app reopens.
 */
object PendingWidgetActionJson {
    fun toJson(actions: List<PendingWidgetAction>): String {
        val arr = JSONArray()
        for (a in actions) {
            val o = JSONObject()
            o.put("action", a.action)
            o.put("vehicleId", a.vehicleId ?: JSONObject.NULL)
            o.put("timestamp", a.timestamp)
            arr.put(o)
        }
        return arr.toString()
    }

    fun parse(json: String): List<PendingWidgetAction> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val action = o.optString("action", "")
                if (action.isBlank()) return@mapNotNull null
                PendingWidgetAction(
                    action = action,
                    vehicleId = if (o.isNull("vehicleId")) null else o.optString("vehicleId").ifBlank { null },
                    timestamp = o.optLong("timestamp", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
