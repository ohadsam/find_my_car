package com.ohadsam.findmycar.core

/**
 * A "you seem to have parked and walked off — save the spot?" suggestion that
 * WalkAwayEngine raised for real while the app was not in front of the user.
 *
 * [lat]/[lng] are the fix captured **at the moment the Bluetooth link dropped**,
 * not where the user was when walking was confirmed — by then they are tens of
 * metres away, and saving that spot would be worse than not asking. They are
 * nullable because `getLastKnownLocation()` is best-effort: with no cached fix
 * the suggestion is still raised, and JS falls back to a live GPS read when the
 * user accepts (less accurate, but the user is the one choosing).
 *
 * Like PendingGpsSuggestion — and unlike PendingBtAction's AutoEnd/AutoStart —
 * this is NEVER auto-performed. It only ever opens a confirmation.
 */
data class PendingParkingSuggestion(
    val vehicleId: String,
    val vehicleName: String,
    val label: String,
    val lat: Double?,
    val lng: Double?,
    val timestamp: Long,
)
