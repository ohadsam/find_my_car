package com.ohadsam.findmycar.core

/**
 * Which native log entries to keep (v1.52.0).
 *
 * A plain "last N" cap let the 5-minute heartbeat — about 290 lines a day —
 * push everything else out: a real report's log was nothing but heartbeats,
 * so whether a Bluetooth event arrived that morning could not be told from
 * it. Heartbeats now have their own, smaller allowance, and only the oldest
 * of THEM are dropped for it; order is preserved.
 */
object NativeLogRetention {
    fun isHeartbeat(e: NativeLogEntry): Boolean = e.message.startsWith("heartbeat")

    fun trim(entries: List<NativeLogEntry>, maxTotal: Int, maxHeartbeats: Int): List<NativeLogEntry> {
        val heartbeats = entries.count(::isHeartbeat)
        var dropBeats = (heartbeats - maxHeartbeats).coerceAtLeast(0)
        val kept = entries.filter { e ->
            if (dropBeats > 0 && isHeartbeat(e)) { dropBeats--; false } else true
        }
        return kept.takeLast(maxTotal)
    }
}
