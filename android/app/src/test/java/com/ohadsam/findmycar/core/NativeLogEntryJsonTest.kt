package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uses org.json, which throws "not mocked" on the plain JVM unit test
 * classpath without Robolectric providing a real implementation.
 */
@RunWith(RobolectricTestRunner::class)
class NativeLogEntryJsonTest {
    @Test
    fun `round-trips a single entry`() {
        val original = listOf(NativeLogEntry(1000L, "FMC-FgService", "onCreate succeeded"))
        val json = NativeLogEntryJson.toJson(original)
        assertEquals(original, NativeLogEntryJson.parse(json))
    }

    @Test
    fun `round-trips multiple entries in order`() {
        val original = listOf(
            NativeLogEntry(1000L, "FMC-FgService", "onCreate succeeded"),
            NativeLogEntry(2000L, "FMC-FgService", "GPS shadow watch started (provider=gps)"),
        )
        val json = NativeLogEntryJson.toJson(original)
        assertEquals(original, NativeLogEntryJson.parse(json))
    }

    @Test
    fun `empty list round-trips to empty list`() {
        assertEquals(emptyList<NativeLogEntry>(), NativeLogEntryJson.parse(NativeLogEntryJson.toJson(emptyList())))
    }

    @Test
    fun `malformed json returns empty list instead of throwing`() {
        assertEquals(emptyList<NativeLogEntry>(), NativeLogEntryJson.parse("not json"))
    }

    @Test
    fun `entry with blank message is skipped`() {
        val json = """[{"timestamp":1,"tag":"FMC-FgService","message":""}]"""
        assertEquals(emptyList<NativeLogEntry>(), NativeLogEntryJson.parse(json))
    }
}
