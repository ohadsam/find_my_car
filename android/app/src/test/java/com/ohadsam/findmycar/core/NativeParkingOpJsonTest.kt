package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeParkingOpJsonTest {
    private fun roundTrip(ops: List<NativeParkingOp>) = NativeParkingOpJson.parse(NativeParkingOpJson.toJson(ops))

    @Test
    fun `start with location, address and bluetooth device round-trips`() {
        val op = NativeParkingOp(
            "o1", NativeParkingOp.START, "v1", "p1", 1000L, "bluetooth",
            32.08, 34.78, 9.0, NativeAddress("הרצל 5 תל אביב", "הרצל", "5", "תל אביב", null), false, "CK-5",
        )
        assertEquals(listOf(op), roundTrip(listOf(op)))
    }

    @Test
    fun `end with no parking id or location round-trips`() {
        val op = NativeParkingOp("o2", NativeParkingOp.END, "v1", null, 2000L, "widget")
        val parsed = roundTrip(listOf(op))[0]
        assertEquals(op, parsed)
        assertNull(parsed.parkingId)
        assertNull(parsed.lat)
    }

    @Test
    fun `open-area start keeps noAddress and no address`() {
        val op = NativeParkingOp("o3", NativeParkingOp.START, "v1", "p3", 3000L, "walkAway", 32.6, 35.2, noAddress = true)
        assertEquals(op, roundTrip(listOf(op))[0])
    }

    @Test
    fun `order is preserved (end then start is a swap)`() {
        val ops = listOf(
            NativeParkingOp("a", NativeParkingOp.END, "v1", "old", 1L, "widget"),
            NativeParkingOp("b", NativeParkingOp.START, "v1", "new", 2L, "widget", 1.0, 2.0),
        )
        assertEquals(ops, roundTrip(ops))
    }

    @Test
    fun `entries with an unknown type or missing ids are skipped`() {
        val json = """[{"opId":"x","type":"teleport","vehicleId":"v1","at":1},{"opId":"","type":"end","vehicleId":"v1","at":1}]"""
        assertEquals(emptyList<NativeParkingOp>(), NativeParkingOpJson.parse(json))
    }

    @Test
    fun `malformed json is an empty list, not a crash`() {
        assertEquals(emptyList<NativeParkingOp>(), NativeParkingOpJson.parse("nope"))
    }
}
