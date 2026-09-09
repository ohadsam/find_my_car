package com.ohadsam.findmycar.core

/**
 * One native-only event NativeLogStore records for later merging into
 * js/diag-log.js's DiagLog — either a background-machinery lifecycle
 * transition (foreground service start/stop, GPS watch start/stop, raw
 * Bluetooth ACL broadcast receipt; `category = "SERVICE"`) or a native<->JS
 * Capacitor plugin message-bus event (a `@PluginMethod` call received from
 * JS, or a `notifyListeners()` call sent to JS; `category = "BRIDGE"`).
 * Both kinds share this same store/merge mechanism because both answer the
 * same underlying question — "was the background machinery (or the message
 * that was supposed to reach/leave it) actually alive/sent, and when" —
 * from inside the app, without adb. `category` is what
 * js/app.js's #reconcileNativeLog() files each entry under in DiagLog,
 * instead of a single hardcoded category, so the two kinds stay
 * distinguishable in the UI filter exactly like every other DiagLog
 * category pair (e.g. BT vs BT-SHADOW) — see NativeLogStore and CLAUDE.md's
 * "Native background service log" / "Native<->JS message bus log".
 */
data class NativeLogEntry(
    val timestamp: Long,
    val tag: String,
    val category: String,
    val message: String,
)
