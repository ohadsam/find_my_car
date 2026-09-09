package com.ohadsam.findmycar.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes/parses a list of NativeLogEntry to/from JSON, for
 * NativeLogStore's SharedPreferences-backed persistence — mirrors
 * PendingWidgetActionJson's split (pure data class stays free of org.json;
 * only this layer needs Robolectric to test).
 */
object NativeLogEntryJson {
    fun toJson(entries: List<NativeLogEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("tag", e.tag)
            o.put("message", e.message)
            arr.put(o)
        }
        return arr.toString()
    }

    fun parse(json: String): List<NativeLogEntry> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val message = o.optString("message", "")
                if (message.isBlank()) return@mapNotNull null
                NativeLogEntry(
                    timestamp = o.optLong("timestamp", 0L),
                    tag = o.optString("tag", ""),
                    message = message,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
