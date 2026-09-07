package com.ohadsam.findmycar.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Haversine distance calculation — exact port of js/utils.js's
 * Utils.distance(), used by GpsDecisionEngine.checkDistance. Kept separate
 * so it's trivially testable against known lat/lng pairs, independent of
 * the decision logic itself.
 */
object GpsMath {
    private const val EARTH_RADIUS_METERS = 6371000.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
