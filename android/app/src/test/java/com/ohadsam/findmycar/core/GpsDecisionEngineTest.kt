package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit — no Robolectric needed, GpsDecisionEngine has zero Android
 * framework dependency. Each test mirrors one branch of js/app.js's
 * #checkGpsSpeed/#checkGpsDistance/#suggestGpsEnd; keep these in sync with
 * that file if its decision logic ever changes.
 */
class GpsDecisionEngineTest {
    private val threshold = 7.0   // m/s, matches CFG.gpsSpeedThreshold
    private val duration  = 8000L // ms, matches CFG.gpsSpeedDuration
    private val distanceThreshold = 300.0 // meters, matches CFG.gpsDistanceThreshold

    // ── checkSpeed ───────────────────────────────────────────────

    @Test
    fun `no current parking produces no decision and leaves state untouched`() {
        val state = GpsDecisionState(speedSince = 111L)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = false, gpsAutoEndEnabled = true,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `setting disabled produces no decision and leaves state untouched`() {
        val state = GpsDecisionState()
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = false,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `already suggested this session produces no decision`() {
        val state = GpsDecisionState(endSuggested = true)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `speed below threshold resets speedSince to null`() {
        val state = GpsDecisionState(speedSince = 500L)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = 1.0, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertNull(newState.speedSince)
        assertNull(decision)
    }

    @Test
    fun `null speed resets speedSince to null`() {
        val state = GpsDecisionState(speedSince = 500L)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = null, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertNull(newState.speedSince)
        assertNull(decision)
    }

    @Test
    fun `NaN speed resets speedSince to null`() {
        val state = GpsDecisionState(speedSince = 500L)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = Double.NaN, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertNull(newState.speedSince)
        assertNull(decision)
    }

    @Test
    fun `first sample above threshold starts timing with no decision yet`() {
        val state = GpsDecisionState()
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L,
        )
        assertEquals(1000L, newState.speedSince)
        assertNull(decision)
    }

    @Test
    fun `sustained above threshold but under duration produces no decision yet`() {
        val state = GpsDecisionState(speedSince = 1000L)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L + duration - 1,
        )
        assertEquals(1000L, newState.speedSince)
        assertNull(decision)
    }

    @Test
    fun `sustained above threshold for the full duration suggests end`() {
        val state = GpsDecisionState(speedSince = 1000L)
        val (newState, decision) = GpsDecisionEngine.checkSpeed(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L + duration,
        )
        assertEquals(GpsDecisionState(speedSince = null, endSuggested = true), newState)
        assertEquals(GpsDecision.SuggestEnd, decision)
    }

    // ── checkDistance ────────────────────────────────────────────

    @Test
    fun `distance below threshold produces no decision`() {
        val state = GpsDecisionState()
        val (newState, decision) = GpsDecisionEngine.checkDistance(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            distanceMeters = 100.0, distanceThreshold = distanceThreshold,
        )
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `distance at or above threshold suggests end`() {
        val state = GpsDecisionState()
        val (newState, decision) = GpsDecisionEngine.checkDistance(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            distanceMeters = 300.0, distanceThreshold = distanceThreshold,
        )
        assertEquals(GpsDecisionState(speedSince = null, endSuggested = true), newState)
        assertEquals(GpsDecision.SuggestEnd, decision)
    }

    @Test
    fun `distance check ignores no current parking`() {
        val state = GpsDecisionState()
        val (newState, decision) = GpsDecisionEngine.checkDistance(
            state, hasCurrentParking = false, gpsAutoEndEnabled = true,
            distanceMeters = 500.0, distanceThreshold = distanceThreshold,
        )
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `distance check honors the already-suggested race guard`() {
        val state = GpsDecisionState(endSuggested = true)
        val (newState, decision) = GpsDecisionEngine.checkDistance(
            state, hasCurrentParking = true, gpsAutoEndEnabled = true,
            distanceMeters = 500.0, distanceThreshold = distanceThreshold,
        )
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `speed and distance both crossing on the same update only suggests once`() {
        // Mirrors the real caller: distance is checked first, its resulting
        // state (endSuggested=true) is what the next checkSpeed call sees.
        val initial = GpsDecisionState(speedSince = 1000L)
        val (afterDistance, distanceDecision) = GpsDecisionEngine.checkDistance(
            initial, hasCurrentParking = true, gpsAutoEndEnabled = true,
            distanceMeters = 500.0, distanceThreshold = distanceThreshold,
        )
        assertEquals(GpsDecision.SuggestEnd, distanceDecision)

        val (afterSpeed, speedDecision) = GpsDecisionEngine.checkSpeed(
            afterDistance, hasCurrentParking = true, gpsAutoEndEnabled = true,
            speed = 20.0, speedThreshold = threshold, speedDuration = duration, now = 1000L + duration,
        )
        assertNull(speedDecision)
        assertEquals(afterDistance, afterSpeed)
    }
}
