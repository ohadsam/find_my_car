package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DisconnectSpotJsonTest {
    private val a = DisconnectSpot("v1", "peugeot", "CK-5", 32.0853, 34.7818, 12.0, 1700000000000L)
    private val b = DisconnectSpot("v2", "citroen", "CAR", 32.1, 34.8, null, 1700000500000L, "stop")

    @Test
    fun `round-trips a list, accuracy and source included`() {
        assertEquals(listOf(a, b), DisconnectSpotJson.parseList(DisconnectSpotJson.toJson(listOf(a, b))))
    }

    @Test
    fun `missing accuracy stays null, never NaN or zero`() {
        val parsed = DisconnectSpotJson.parseList(DisconnectSpotJson.toJson(listOf(b))).single()
        assertNull(parsed.accuracy)
    }

    @Test
    fun `empty and malformed json parse to an empty list`() {
        assertTrue(DisconnectSpotJson.parseList("").isEmpty())
        assertTrue(DisconnectSpotJson.parseList("[]").isEmpty())
        assertTrue(DisconnectSpotJson.parseList("{nope").isEmpty())
    }

    @Test
    fun `entries without a vehicle or a usable location are dropped`() {
        val json = """[{"vehicleId":"","lat":1,"lng":2,"at":1},{"vehicleId":"x","at":1},{"vehicleId":"ok","lat":1.5,"lng":2.5,"at":7}]"""
        val parsed = DisconnectSpotJson.parseList(json)
        assertEquals(listOf("ok"), parsed.map { it.vehicleId })
    }
}
