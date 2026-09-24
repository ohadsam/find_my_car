package com.ohadsam.findmycar.core

/**
 * A widget quick-action (save/swap/end) tapped while the WebView was
 * unreachable — recorded so JS can replay it through the real
 * performWidgetAction(action, vehicleId) the next time it resumes, instead
 * of today's fallback of force-opening the app (WidgetActionReceiver.kt,
 * Stage 8 of the native background-detection migration — CLAUDE.md "Native
 * background detection"). `vehicleId` is nullable: QuickSaveWidgetProvider's
 * main-tap "save" action doesn't specify one (it always targets whichever
 * vehicle is active), matching performWidgetAction's own nullable param.
 *
 * [lat]/[lng]/[accuracy]/[fixTime] are the phone's last known fix at the
 * moment of the tap, recorded for "save"/"swap" only. The replay can happen
 * long after the tap, and a live fix taken then describes where the user is
 * when they open the app, not where they parked — the same wrong-location
 * bug v1.45.0 fixed for Bluetooth. All null when no fix was cached.
 */
data class PendingWidgetAction(
    val action: String,
    val vehicleId: String?,
    val timestamp: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Double? = null,
    val fixTime: Long? = null,
)
