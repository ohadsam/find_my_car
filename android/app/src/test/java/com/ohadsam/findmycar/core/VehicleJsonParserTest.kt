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
class VehicleJsonParserTest {
    @Test
    fun `parses a full vehicle correctly`() {
        val json = """[{"id":"v1","name":"Tesla","icon":"🚗","bluetoothDevice":"CarBT","bluetoothAutoEnd":true,"bluetoothAutoStart":true,"bluetoothStartPopup":false}]"""
        val result = VehicleJsonParser.parse(json)
        assertEquals(1, result.size)
        assertEquals(NativeVehicle("v1", "Tesla", "🚗", "CarBT", true, true, false), result[0])
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val json = """[{"id":"v1","name":"Tesla","icon":"🚗"}]"""
        val result = VehicleJsonParser.parse(json)
        assertEquals(NativeVehicle("v1", "Tesla", "🚗", null, false, false, true), result[0])
    }

    @Test
    fun `vehicle with no id is skipped`() {
        val json = """[{"name":"NoId"}]"""
        val result = VehicleJsonParser.parse(json)
        assertEquals(0, result.size)
    }

    @Test
    fun `vehicle with blank id is skipped`() {
        val json = """[{"id":"","name":"BlankId"}]"""
        val result = VehicleJsonParser.parse(json)
        assertEquals(0, result.size)
    }

    @Test
    fun `malformed json returns empty list instead of throwing`() {
        val result = VehicleJsonParser.parse("not json")
        assertEquals(0, result.size)
    }

    @Test
    fun `empty array returns empty list`() {
        val result = VehicleJsonParser.parse("[]")
        assertEquals(0, result.size)
    }

    @Test
    fun `blank bluetoothDevice is treated as null, not an empty string`() {
        val json = """[{"id":"v1","name":"Tesla","icon":"🚗","bluetoothDevice":""}]"""
        val result = VehicleJsonParser.parse(json)
        assertNull(result[0].bluetoothDevice)
    }

    @Test
    fun `parses multiple vehicles in order`() {
        val json = """[{"id":"v1","name":"A","icon":"🚗"},{"id":"v2","name":"B","icon":"🚙"}]"""
        val result = VehicleJsonParser.parse(json)
        assertEquals(listOf("v1", "v2"), result.map { it.id })
    }
}
