package com.ohadsam.findmycar.core

/**
 * A GPS end-parking suggestion GpsDecisionEngine decided on for real,
 * recorded because the WebView was unreachable when the threshold crossed
 * (see ParkingForegroundService.maybeRecordPendingGpsSuggestion — Stage 7
 * of the native background-detection migration, CLAUDE.md "Native
 * background detection").
 *
 * Unlike Bluetooth's AutoEnd, a GPS suggestion is NEVER auto-performed —
 * GpsDecisionEngine's only decision (SuggestEnd) always requires user
 * confirmation via gpsEndModal. This only remembers that a suggestion
 * should be shown (for whichever vehicle was active at the time) so JS can
 * open that same confirmation modal the next time it resumes — it never
 * ends a parking by itself. At most one suggestion is ever outstanding at
 * a time (GPS end-suggestion only ever concerns the active vehicle), unlike
 * PendingBtAction which can have several (one per linked vehicle).
 */
data class PendingGpsSuggestion(
    val vehicleId: String,
    val vehicleName: String,
    val timestamp: Long,
)
