package com.ohadsam.findmycar.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * (De)serializes the native parking journal. The field names are the contract
 * with js/app.js's #adoptNativeParkingOps(), which reads this JSON directly.
 */
object NativeParkingOpJson {
    fun toJson(ops: List<NativeParkingOp>): String {
        val arr = JSONArray()
        for (op in ops) arr.put(toObject(op))
        return arr.toString()
    }

    fun toObject(op: NativeParkingOp): JSONObject = JSONObject().apply {
        put("opId", op.opId)
        put("type", op.type)
        put("vehicleId", op.vehicleId)
        put("parkingId", op.parkingId ?: JSONObject.NULL)
        put("at", op.at)
        put("source", op.source)
        put("lat", op.lat ?: JSONObject.NULL)
        put("lng", op.lng ?: JSONObject.NULL)
        put("accuracy", op.accuracy ?: JSONObject.NULL)
        put("address", op.address?.let { addressToObject(it) } ?: JSONObject.NULL)
        put("noAddress", op.noAddress)
        put("btDevice", op.btDevice ?: JSONObject.NULL)
    }

    fun addressToObject(a: NativeAddress): JSONObject = JSONObject().apply {
        put("display", a.display)
        put("street", a.street ?: JSONObject.NULL)
        put("houseNumber", a.houseNumber ?: JSONObject.NULL)
        put("city", a.city ?: JSONObject.NULL)
        put("neighborhood", a.neighborhood ?: JSONObject.NULL)
    }

    fun parse(json: String): List<NativeParkingOp> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val opId = o.optString("opId", "")
                val type = o.optString("type", "")
                val vehicleId = o.optString("vehicleId", "")
                if (opId.isBlank() || vehicleId.isBlank() || (type != NativeParkingOp.START && type != NativeParkingOp.END)) {
                    return@mapNotNull null
                }
                NativeParkingOp(
                    opId = opId,
                    type = type,
                    vehicleId = vehicleId,
                    parkingId = str(o, "parkingId"),
                    at = o.optLong("at", 0L),
                    source = o.optString("source", ""),
                    lat = num(o, "lat"),
                    lng = num(o, "lng"),
                    accuracy = num(o, "accuracy"),
                    address = o.optJSONObject("address")?.let { a ->
                        val display = a.optString("display", "")
                        if (display.isBlank()) null else NativeAddress(
                            display, str(a, "street"), str(a, "houseNumber"), str(a, "city"), str(a, "neighborhood"),
                        )
                    },
                    noAddress = o.optBoolean("noAddress", false),
                    btDevice = str(o, "btDevice"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun str(o: JSONObject, k: String): String? =
        if (!o.has(k) || o.isNull(k)) null else o.optString(k).ifBlank { null }

    private fun num(o: JSONObject, k: String): Double? =
        if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
}
