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
class PendingBtActionJsonTest {
    private fun action(
        direction: String = "connected",
        action: String = "autoEnd",
        vehicleId: String = "v1",
        lat: Double? = 31.7767,
        lng: Double? = 35.2345,
    ) = PendingBtAction(direction, action, vehicleId, "Tesla", "CarBT", lat, lng, 1000L)

    @Test
    fun `round-trips a single action with a location fix`() {
        val original = listOf(action())
        val json = PendingBtActionJson.toJson(original)
        val parsed = PendingBtActionJson.parse(json)
        assertEquals(original, parsed)
    }

    @Test
    fun `round-trips an action with no location fix`() {
        val original = listOf(action(lat = null, lng = null))
        val json = PendingBtActionJson.toJson(original)
        val parsed = PendingBtActionJson.parse(json)
        assertNull(parsed[0].lat)
        assertNull(parsed[0].lng)
        assertEquals(original, parsed)
    }

    @Test
    fun `round-trips multiple actions in order`() {
        val original = listOf(
            action(vehicleId = "v1", direction = "connected", action = "autoEnd"),
            action(vehicleId = "v2", direction = "disconnected", action = "autoStart"),
        )
        val json = PendingBtActionJson.toJson(original)
        val parsed = PendingBtActionJson.parse(json)
        assertEquals(original, parsed)
    }

    @Test
    fun `empty list round-trips to empty list`() {
        val json = PendingBtActionJson.toJson(emptyList())
        assertEquals(emptyList<PendingBtAction>(), PendingBtActionJson.parse(json))
    }

    @Test
    fun `malformed json returns empty list instead of throwing`() {
        assertEquals(emptyList<PendingBtAction>(), PendingBtActionJson.parse("not json"))
    }

    @Test
    fun `entry with blank vehicleId is skipped`() {
        val json = """[{"direction":"connected","action":"autoEnd","vehicleId":"","vehicleName":"x","label":"x","timestamp":1}]"""
        assertEquals(emptyList<PendingBtAction>(), PendingBtActionJson.parse(json))
    }
}
