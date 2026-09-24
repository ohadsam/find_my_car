package com.ohadsam.findmycar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Best-effort "where is the phone right now" using only what the system has
 * already cached — never a fresh fix request, which would cost a GPS warm-up in
 * a broadcast receiver that has milliseconds to work with.
 *
 * Shared by BtPendingActionRecorder (Stage 5's auto-start location) and
 * WalkAwayDetector (the disconnect fix a walk-away suggestion is about) rather
 * than duplicated in each — same anti-drift reasoning as BackgroundAlertNotifier
 * and OemSettingsIntents: two copies of a permission check are two places to
 * forget to update one.
 */
object LastKnownLocation {
    data class Fix(val lat: Double, val lng: Double, val accuracy: Double, val time: Long)

    /** (lat, lng), or (null, null) when there is no usable cached fix. */
    fun get(context: Context): Pair<Double?, Double?> {
        val f = getFix(context) ?: return null to null
        return f.lat to f.lng
    }

    /**
     * The newest cached fix across GPS and network, with its age — a cached
     * fix can be hours old and from somewhere else entirely, so a caller that
     * saves a parking from it must be able to tell.
     */
    fun getFix(context: Context): Fix? {
        return try {
            val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!fineGranted) return null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val loc = listOfNotNull(
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            ).maxByOrNull { it.time } ?: return null
            Fix(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time)
        } catch (e: Exception) {
            null
        }
    }
}
