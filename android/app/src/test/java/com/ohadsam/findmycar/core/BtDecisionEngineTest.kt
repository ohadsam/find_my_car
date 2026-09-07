package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit — no Robolectric needed, BtDecisionEngine has zero Android
 * framework dependency. Each test mirrors one branch of js/app.js's
 * #onBtConnected/#onBtDisconnected; keep these in sync with that file if
 * its decision logic ever changes.
 */
class BtDecisionEngineTest {
    private fun vehicle(
        id: String = "v1",
        name: String = "Car",
        device: String? = "MyCar",
        autoEnd: Boolean = false,
        autoStart: Boolean = false,
    ) = NativeVehicle(id, name, "🚗", device, autoEnd, autoStart, true)

    // ── onConnected ──────────────────────────────────────────────

    @Test
    fun `no vehicle linked to the device label produces no decisions`() {
        val vehicles = listOf(vehicle(device = "OtherCar"))
        val result = BtDecisionEngine.onConnected(vehicles, "MyCar") { true }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matched vehicle with no active parking is ignored`() {
        val vehicles = listOf(vehicle())
        val result = BtDecisionEngine.onConnected(vehicles, "MyCar") { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matched vehicle with autoEnd and active parking auto-ends`() {
        val v = vehicle(autoEnd = true)
        val result = BtDecisionEngine.onConnected(listOf(v), "MyCar") { true }
        assertEquals(listOf(BtConnectDecision.AutoEnd(v)), result)
    }

    @Test
    fun `matched vehicle without autoEnd and active parking suggests end`() {
        val v = vehicle(autoEnd = false)
        val result = BtDecisionEngine.onConnected(listOf(v), "MyCar") { true }
        assertEquals(listOf(BtConnectDecision.SuggestEnd(v)), result)
    }

    @Test
    fun `only one suggestion is ever produced even with multiple matching vehicles`() {
        val v1 = vehicle(id = "v1", autoEnd = false)
        val v2 = vehicle(id = "v2", autoEnd = false)
        val result = BtDecisionEngine.onConnected(listOf(v1, v2), "MyCar") { true }
        assertEquals(listOf(BtConnectDecision.SuggestEnd(v1)), result)
    }

    @Test
    fun `multiple autoEnd vehicles all get decisions`() {
        val v1 = vehicle(id = "v1", autoEnd = true)
        val v2 = vehicle(id = "v2", autoEnd = true)
        val result = BtDecisionEngine.onConnected(listOf(v1, v2), "MyCar") { true }
        assertEquals(listOf(BtConnectDecision.AutoEnd(v1), BtConnectDecision.AutoEnd(v2)), result)
    }

    @Test
    fun `autoEnd vehicles are not blocked by an already-claimed suggestion slot`() {
        val v1 = vehicle(id = "v1", autoEnd = false)
        val v2 = vehicle(id = "v2", autoEnd = true)
        val result = BtDecisionEngine.onConnected(listOf(v1, v2), "MyCar") { true }
        assertEquals(listOf(BtConnectDecision.SuggestEnd(v1), BtConnectDecision.AutoEnd(v2)), result)
    }

    // ── onDisconnected ───────────────────────────────────────────

    @Test
    fun `disconnect ignored when autoStart is off`() {
        val v = vehicle(autoStart = false)
        val result = BtDecisionEngine.onDisconnected(listOf(v), "MyCar") { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `disconnect ignored when vehicle already has active parking`() {
        val v = vehicle(autoStart = true)
        val result = BtDecisionEngine.onDisconnected(listOf(v), "MyCar") { true }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `disconnect with autoStart on and no active parking auto-starts`() {
        val v = vehicle(autoStart = true)
        val result = BtDecisionEngine.onDisconnected(listOf(v), "MyCar") { false }
        assertEquals(listOf(BtDisconnectDecision.AutoStart(v)), result)
    }

    @Test
    fun `unmatched device label produces no disconnect decisions`() {
        val v = vehicle(device = "OtherCar", autoStart = true)
        val result = BtDecisionEngine.onDisconnected(listOf(v), "MyCar") { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `vehicle with null bluetoothDevice never matches any label`() {
        val v = vehicle(device = null, autoStart = true)
        val result = BtDecisionEngine.onDisconnected(listOf(v), "MyCar") { false }
        assertTrue(result.isEmpty())
    }
}
