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
class PendingWidgetActionJsonTest {
    @Test
    fun `round-trips an action with a vehicleId`() {
        val original = listOf(PendingWidgetAction("swap", "v1", 1000L))
        val json = PendingWidgetActionJson.toJson(original)
        assertEquals(original, PendingWidgetActionJson.parse(json))
    }

    @Test
    fun `round-trips an action with no vehicleId (QuickSave main tap)`() {
        val original = listOf(PendingWidgetAction("save", null, 1000L))
        val json = PendingWidgetActionJson.toJson(original)
        val parsed = PendingWidgetActionJson.parse(json)
        assertNull(parsed[0].vehicleId)
        assertEquals(original, parsed)
    }

    @Test
    fun `round-trips multiple queued actions in order`() {
        val original = listOf(
            PendingWidgetAction("save", null, 1000L),
            PendingWidgetAction("end", "v2", 2000L),
        )
        val json = PendingWidgetActionJson.toJson(original)
        assertEquals(original, PendingWidgetActionJson.parse(json))
    }

    @Test
    fun `empty list round-trips to empty list`() {
        assertEquals(emptyList<PendingWidgetAction>(), PendingWidgetActionJson.parse(PendingWidgetActionJson.toJson(emptyList())))
    }

    @Test
    fun `malformed json returns empty list instead of throwing`() {
        assertEquals(emptyList<PendingWidgetAction>(), PendingWidgetActionJson.parse("not json"))
    }

    @Test
    fun `entry with blank action is skipped`() {
        val json = """[{"action":"","vehicleId":"v1","timestamp":1}]"""
        assertEquals(emptyList<PendingWidgetAction>(), PendingWidgetActionJson.parse(json))
    }
}
