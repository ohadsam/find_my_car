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
 */
data class PendingWidgetAction(
    val action: String,
    val vehicleId: String?,
    val timestamp: Long,
)
