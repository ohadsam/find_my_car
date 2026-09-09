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
    fun `round-trips a single SERVICE entry`() {
        val original = listOf(NativeLogEntry(1000L, "FMC-FgService", "SERVICE", "onCreate succeeded"))
        val json = NativeLogEntryJson.toJson(original)
        assertEquals(original, NativeLogEntryJson.parse(json))
    }

    @Test
    fun `round-trips a single BRIDGE entry`() {
        val original = listOf(NativeLogEntry(1000L, "FMC-BtPlugin", "BRIDGE", "← JS: startWatch() called"))
        val json = NativeLogEntryJson.toJson(original)
        assertEquals(original, NativeLogEntryJson.parse(json))
    }

    @Test
    fun `round-trips multiple entries of mixed categories in order`() {
        val original = listOf(
            NativeLogEntry(1000L, "FMC-FgService", "SERVICE", "onCreate succeeded"),
            NativeLogEntry(1500L, "FMC-BtPlugin", "BRIDGE", "← JS: startWatch() called"),
            NativeLogEntry(2000L, "FMC-FgService", "SERVICE", "GPS watch started (provider=gps)"),
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
        val json = """[{"timestamp":1,"tag":"FMC-FgService","category":"SERVICE","message":""}]"""
        assertEquals(emptyList<NativeLogEntry>(), NativeLogEntryJson.parse(json))
    }

    @Test
    fun `entry missing category defaults to SERVICE (pre-BRIDGE-field data)`() {
        val json = """[{"timestamp":1,"tag":"FMC-FgService","message":"onCreate succeeded"}]"""
        val parsed = NativeLogEntryJson.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("SERVICE", parsed[0].category)
    }
}
