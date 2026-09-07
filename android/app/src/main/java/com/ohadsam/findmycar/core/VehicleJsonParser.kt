package com.ohadsam.findmycar.core

import org.json.JSONArray

/**
 * Parses the vehicle list JSON mirrored from JS (WidgetDataPlugin's
 * KEY_VEHICLES_JSON, written by js/widget-bridge.js's syncVehicles() call)
 * into NativeVehicle. Kept separate from NativeVehicle/BtDecisionEngine so
 * the pure decision logic has zero org.json dependency and doesn't need
 * Robolectric to test — only this parsing layer does.
 */
object VehicleJsonParser {
    fun parse(json: String): List<NativeVehicle> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isBlank()) return@mapNotNull null
                NativeVehicle(
                    id = id,
                    name = o.optString("name", ""),
                    icon = o.optString("icon", "🚗"),
                    bluetoothDevice = o.optString("bluetoothDevice", "").ifBlank { null },
                    bluetoothAutoEnd = o.optBoolean("bluetoothAutoEnd", false),
                    bluetoothAutoStart = o.optBoolean("bluetoothAutoStart", false),
                    bluetoothStartPopup = o.optBoolean("bluetoothStartPopup", true),
                    hasParking = o.optBoolean("hasParking", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
