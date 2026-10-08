package com.ohadsam.findmycar.core

/**
 * Where a vehicle was when its Bluetooth link last dropped (or, after a drive,
 * where it was last seen stopping) — kept so the app can offer to save the
 * parking THERE later, even if the notification that asked was ignored,
 * swiped away or expired (v1.56.0).
 *
 * Unlike [PendingParkingSuggestion] this is not tied to an open question: the
 * suggestion is withdrawn after a few minutes, but a parking the user simply
 * did not get round to saving is still where it was. Always has a location —
 * an entry is only created once one is known. One per vehicle.
 *
 * [source] is "disconnect" for the spot sampled at the Bluetooth drop and
 * "stop" for one found after the car had kept moving.
 */
data class DisconnectSpot(
    val vehicleId: String,
    val vehicleName: String,
    val label: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val at: Long,
    val source: String = "disconnect",
)
