package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JUnit — BtShadowFormatter has zero Android framework dependency. */
class BtShadowFormatterTest {
    private fun vehicle(id: String, name: String) =
        NativeVehicle(id, name, "🚗", "MyCar", false, false, true, true)

    @Test
    fun `empty connect decisions summarize to none`() {
        assertEquals("none", BtShadowFormatter.summarizeConnect(emptyList()))
    }

    @Test
    fun `empty disconnect decisions summarize to none`() {
        assertEquals("none", BtShadowFormatter.summarizeDisconnect(emptyList()))
    }

    @Test
    fun `single autoEnd decision is formatted with the vehicle name`() {
        val v = vehicle("v1", "Tesla")
        val result = BtShadowFormatter.summarizeConnect(listOf(BtConnectDecision.AutoEnd(v)))
        assertEquals("autoEnd(Tesla)", result)
    }

    @Test
    fun `single suggestEnd decision is formatted with the vehicle name`() {
        val v = vehicle("v1", "Tesla")
        val result = BtShadowFormatter.summarizeConnect(listOf(BtConnectDecision.SuggestEnd(v)))
        assertEquals("suggestEnd(Tesla)", result)
    }

    @Test
    fun `multiple connect decisions are joined in order`() {
        val v1 = vehicle("v1", "Tesla")
        val v2 = vehicle("v2", "Civic")
        val result = BtShadowFormatter.summarizeConnect(
            listOf(BtConnectDecision.SuggestEnd(v1), BtConnectDecision.AutoEnd(v2))
        )
        assertEquals("suggestEnd(Tesla), autoEnd(Civic)", result)
    }

    @Test
    fun `single autoStart decision is formatted with the vehicle name`() {
        val v = vehicle("v1", "Tesla")
        val result = BtShadowFormatter.summarizeDisconnect(listOf(BtDisconnectDecision.AutoStart(v)))
        assertEquals("autoStart(Tesla)", result)
    }

    @Test
    fun `multiple disconnect decisions are joined in order`() {
        val v1 = vehicle("v1", "Tesla")
        val v2 = vehicle("v2", "Civic")
        val result = BtShadowFormatter.summarizeDisconnect(
            listOf(BtDisconnectDecision.AutoStart(v1), BtDisconnectDecision.AutoStart(v2))
        )
        assertEquals("autoStart(Tesla), autoStart(Civic)", result)
    }
}
