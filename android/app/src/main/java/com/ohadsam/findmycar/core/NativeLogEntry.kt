package com.ohadsam.findmycar.core

/**
 * One native-only lifecycle event (foreground service start/stop, GPS watch
 * start/stop, raw Bluetooth ACL broadcast receipt) that has no Capacitor
 * plugin event of its own to ride along on — unlike BT/GPS-SHADOW and
 * BT/GPS-PENDING, which already reach JS because they represent an actual
 * decision. These exist purely to answer "was the background machinery
 * itself alive, and when" from inside the app, without adb — see
 * NativeLogStore and CLAUDE.md's "Native background service log".
 */
data class NativeLogEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
)
