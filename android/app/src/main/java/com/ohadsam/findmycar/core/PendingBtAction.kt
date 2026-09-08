package com.ohadsam.findmycar.core

/**
 * A Bluetooth-driven action BtDecisionEngine decided on for real, recorded
 * because the WebView was unreachable when the real event fired (see
 * BluetoothClassicPlugin.maybeRecordPendingAction — Stage 5 of the native
 * background-detection migration, CLAUDE.md "Native background detection").
 *
 * Deliberately minimal: this is NOT a full parking record (no address,
 * photo, voice, description) — JS remains the single implementation that
 * ever builds/saves a real Parking object. This only remembers that an
 * action should happen and the best location native could capture at the
 * time, so JS can reconcile ("catch up") the next time it resumes.
 */
data class PendingBtAction(
    val direction: String,   // "connected" | "disconnected"
    val action: String,      // "autoEnd" | "autoStart"
    val vehicleId: String,
    val vehicleName: String,
    val label: String,
    val lat: Double?,
    val lng: Double?,
    val timestamp: Long,
)
