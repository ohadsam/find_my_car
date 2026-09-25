package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveVehiclePickerTest {
    // ~333m apart in latitude.
    private val home = ParkedSpot("car-a", "p-a", 31.7767, 35.2345)
    private val work = ParkedSpot("car-b", "p-b", 31.7797, 35.2345)

    @Test
    fun `no parked vehicle means no candidate`() {
        assertNull(DriveVehiclePicker.pick(emptyList(), emptyMap(), 31.7767, 35.2345))
    }

    @Test
    fun `a single parked vehicle is always the candidate, even far away and never seen nearby`() {
        // The v1.42.0 lesson: a drive must never go unattributed because of
        // a gate. The only parked car is the answer, wherever the phone is.
        val picked = DriveVehiclePicker.pick(listOf(home), emptyMap(), 32.5, 35.5)
        assertEquals("car-a", picked?.vehicleId)
    }

    @Test
    fun `the parking the phone was most recently at wins, even if the phone is now nearer another`() {
        var seen = DriveVehiclePicker.recordNear(emptyMap(), listOf(home, work), 31.7767, 35.2345, 150.0, 1_000)
        seen = DriveVehiclePicker.recordNear(seen, listOf(home, work), 31.7797, 35.2345, 150.0, 5_000)
        // Walked to the work car last, then started driving back past home.
        val picked = DriveVehiclePicker.pick(listOf(home, work), seen, 31.7768, 35.2345)
        assertEquals("car-b", picked?.vehicleId)
    }

    @Test
    fun `without any nearby fix, the parking nearest the phone is picked`() {
        val picked = DriveVehiclePicker.pick(listOf(home, work), emptyMap(), 31.7795, 35.2345)
        assertEquals("car-b", picked?.vehicleId)
    }

    @Test
    fun `two parkings seen on the same fix are told apart by distance`() {
        val a = ParkedSpot("car-a", "p-a", 31.77670, 35.2345)
        val b = ParkedSpot("car-b", "p-b", 31.77690, 35.2345) // ~22m from a
        val seen = DriveVehiclePicker.recordNear(emptyMap(), listOf(a, b), 31.77688, 35.2345, 150.0, 1_000)
        assertEquals("car-b", DriveVehiclePicker.pick(listOf(a, b), seen, 31.78, 35.24)?.vehicleId)
    }

    @Test
    fun `recordNear forgets parkings that no longer exist`() {
        val seen = DriveVehiclePicker.recordNear(emptyMap(), listOf(home), 31.7767, 35.2345, 150.0, 1_000)
        val after = DriveVehiclePicker.recordNear(seen, listOf(work), 32.0, 35.0, 150.0, 2_000)
        assertTrue(after.isEmpty())
    }

    @Test
    fun `recordNear leaves a parking alone when the fix is outside the radius`() {
        val seen = DriveVehiclePicker.recordNear(emptyMap(), listOf(home), 31.7767, 35.2345, 150.0, 1_000)
        val after = DriveVehiclePicker.recordNear(seen, listOf(home), 31.7797, 35.2345, 150.0, 9_000)
        assertEquals(1_000L, after.getValue("p-a").at)
    }
}
