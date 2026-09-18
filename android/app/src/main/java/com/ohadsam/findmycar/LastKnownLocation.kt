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
    /** (lat, lng), or (null, null) when there is no usable cached fix. */
    fun get(context: Context): Pair<Double?, Double?> {
        return try {
            val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!fineGranted) return null to null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null to null
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) loc.latitude to loc.longitude else null to null
        } catch (e: Exception) {
            null to null
        }
    }
}
