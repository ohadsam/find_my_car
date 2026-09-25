package com.ohadsam.findmycar.core

import org.json.JSONObject

/**
 * Parses a Nominatim reverse-geocoding response into the display string the
 * widgets and the parking notification show. The rules mirror js/geocoder.js
 * exactly, so an address resolved natively (while the app is closed) reads the
 * same as the one JS writes once the app is opened.
 */
object NominatimAddressJson {
    sealed class Result {
        data class Found(val address: NativeAddress) : Result()
        /** Answered, but there is no address here (open field, forest). */
        object None : Result()
        /** Not a real answer — worth retrying, never "no address". */
        object Failed : Result()
    }

    fun parse(json: String): Result {
        val o = try { JSONObject(json) } catch (e: Exception) { return Result.Failed }
        if (!o.has("address") && !o.has("error")) return Result.Failed
        if (o.has("error")) return Result.None
        val a = o.optJSONObject("address") ?: return Result.Failed
        fun first(vararg keys: String) = keys.map { a.optString(it, "") }.firstOrNull { it.isNotBlank() }
        val street = first("road", "pedestrian", "footway", "path")
        val houseNumber = first("house_number")
        val city = first("city", "town", "village", "hamlet", "municipality")
        val neighborhood = first("suburb", "neighbourhood", "quarter")
        if (street == null && city == null) return Result.None
        val display = listOfNotNull(street, houseNumber, city).joinToString(" ")
        return Result.Found(NativeAddress(display, street, houseNumber, city, neighborhood))
    }
}
