package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uses org.json, which throws "not mocked" on the plain JVM unit test
 * classpath without Robolectric providing a real implementation.
 */
@RunWith(RobolectricTestRunner::class)
class PendingGpsSuggestionJsonTest {
    @Test
    fun `round-trips a suggestion correctly`() {
        val original = PendingGpsSuggestion("v1", "Tesla", 12345L)
        val json = PendingGpsSuggestionJson.toJson(original)
        assertEquals(original, PendingGpsSuggestionJson.parse(json))
    }

    @Test
    fun `null suggestion serializes to the JSON null literal`() {
        assertEquals("null", PendingGpsSuggestionJson.toJson(null))
    }

    @Test
    fun `the JSON null literal parses back to null`() {
        assertNull(PendingGpsSuggestionJson.parse("null"))
    }

    @Test
    fun `blank string parses to null instead of throwing`() {
        assertNull(PendingGpsSuggestionJson.parse(""))
    }

    @Test
    fun `malformed json returns null instead of throwing`() {
        assertNull(PendingGpsSuggestionJson.parse("not json"))
    }

    @Test
    fun `entry with blank vehicleId parses to null`() {
        val json = """{"vehicleId":"","vehicleName":"x","timestamp":1}"""
        assertNull(PendingGpsSuggestionJson.parse(json))
    }
}
