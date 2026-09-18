package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Touches org.json, so it needs Robolectric — same as the other *Json tests. */
@RunWith(RobolectricTestRunner::class)
class PendingParkingSuggestionJsonTest {

    @Test
    fun `round-trips a suggestion that captured a location`() {
        val s = PendingParkingSuggestion("v1", "peugeot", "CK-5", 32.0853, 34.7818, 1700000000000L)
        assertEquals(s, PendingParkingSuggestionJson.parse(PendingParkingSuggestionJson.toJson(s)))
    }

    @Test
    fun `round-trips a suggestion with no location captured`() {
        // getLastKnownLocation() is best-effort — "no fix" must survive the
        // round trip as null, never as 0,0 or NaN.
        val s = PendingParkingSuggestion("v1", "peugeot", "CK-5", null, null, 1700000000000L)
        val parsed = PendingParkingSuggestionJson.parse(PendingParkingSuggestionJson.toJson(s))
        assertEquals(s, parsed)
        assertNull(parsed!!.lat)
        assertNull(parsed.lng)
    }

    @Test
    fun `round-trips null`() {
        assertEquals("null", PendingParkingSuggestionJson.toJson(null))
        assertNull(PendingParkingSuggestionJson.parse("null"))
    }

    @Test
    fun `blank and malformed json parse to null rather than throwing`() {
        assertNull(PendingParkingSuggestionJson.parse(""))
        assertNull(PendingParkingSuggestionJson.parse("   "))
        assertNull(PendingParkingSuggestionJson.parse("{not json"))
        assertNull(PendingParkingSuggestionJson.parse("[]"))
    }

    @Test
    fun `an entry with no vehicle id parses to null`() {
        assertNull(PendingParkingSuggestionJson.parse("""{"vehicleName":"x","timestamp":1}"""))
    }

    @Test
    fun `a lat with no lng does not produce half a location`() {
        val parsed = PendingParkingSuggestionJson.parse("""{"vehicleId":"v1","lat":32.1}""")
        assertEquals(32.1, parsed!!.lat!!, 0.0001)
        assertNull(parsed.lng)
    }
}
