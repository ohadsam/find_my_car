package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLogRetentionTest {
    private fun beat(t: Long) = NativeLogEntry(t, "FMC-FgService", "SERVICE", "heartbeat — alive")
    private fun event(t: Long) = NativeLogEntry(t, "FMC-FgService", "SERVICE", "ACL broadcast: DISCONNECTED label=car")

    @Test
    fun `heartbeats never push real events out`() {
        val entries = listOf(event(0)) + (1L..500L).map(::beat)
        val kept = NativeLogRetention.trim(entries, maxTotal = 400, maxHeartbeats = 100)
        assertEquals(101, kept.size)
        assertEquals(event(0), kept.first())
        assertEquals(500L, kept.last().timestamp)
    }

    @Test
    fun `order is preserved and the newest heartbeats are the ones kept`() {
        val entries = listOf(beat(1), event(2), beat(3), beat(4))
        val kept = NativeLogRetention.trim(entries, maxTotal = 10, maxHeartbeats = 2)
        assertEquals(listOf(event(2), beat(3), beat(4)), kept)
    }

    @Test
    fun `the total cap still applies to real events`() {
        val entries = (1L..10L).map(::event)
        assertEquals((6L..10L).map(::event), NativeLogRetention.trim(entries, maxTotal = 5, maxHeartbeats = 5))
    }
}
