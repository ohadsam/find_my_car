package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit — WalkAwayEngine has zero Android framework dependency, same as
 * GpsDecisionEngine. Keep these in sync with js/app.js if the decision logic
 * ever changes on either side.
 */
class WalkAwayEngineTest {
    private val walkMin = 0.5          // m/s, matches CFG.walkMinSpeed
    private val walkMax = 3.0          // m/s, matches CFG.walkMaxSpeed
    private val abortSpeed = 6.0       // m/s, matches CFG.walkAbortSpeed
    private val requiredWalk = 8_000L  // matches CFG.walkRequiredMs
    private val minDisplacement = 30.0 // matches CFG.walkMinDisplacement
    private val window = 600_000L      // matches CFG.walkWindowMs
    private val sampleCap = 15_000L    // matches CFG.gpsSpeedSampleCapMs

    private val disconnectedAt = 1_000L

    private fun check(
        state: WalkAwayState,
        speed: Double?,
        now: Long,
        distanceFromDisconnect: Double = 100.0,
    ) = WalkAwayEngine.check(
        state, speed, distanceFromDisconnect, disconnectedAt,
        walkMin, walkMax, abortSpeed, requiredWalk, minDisplacement,
        window, sampleCap, now,
    )

    // ── the happy path ───────────────────────────────────────────

    @Test
    fun `sustained walking away suggests starting a parking`() {
        var state = WalkAwayState()
        var now = disconnectedAt
        var decision: WalkAwayDecision? = null
        repeat(5) {
            val (next, d) = check(state, 1.4, now)
            state = next
            if (d != null && decision == null) decision = d
            now += 3000L
        }
        assertEquals(WalkAwayDecision.SuggestStart, decision)
        assertTrue(state.suggested)
    }

    @Test
    fun `the first sample of the window accumulates nothing but records the clock`() {
        val (state, decision) = check(WalkAwayState(), 1.4, now = disconnectedAt)
        assertEquals(0L, state.walkAccumMs)
        assertEquals(disconnectedAt, state.lastSampleAt)
        assertNull(decision)
    }

    @Test
    fun `walking long enough but not far enough does not suggest`() {
        // Pacing beside the car while loading shopping is walking, but it is
        // not leaving — this is what the displacement requirement is for, and
        // what makes a permissive lower speed bound safe.
        var state = WalkAwayState()
        var now = disconnectedAt
        repeat(6) {
            val (next, decision) = check(state, 1.4, now, distanceFromDisconnect = 5.0)
            assertNull(decision)
            state = next
            now += 3000L
        }
        assertTrue(state.walkAccumMs >= requiredWalk)
    }

    @Test
    fun `stationary GPS jitter never suggests on its own`() {
        // Jitter can nudge the accumulator — deliberately tolerated — but it
        // cannot move anyone 30 metres, so nothing is ever suggested from it.
        var state = WalkAwayState()
        var now = disconnectedAt
        repeat(20) {
            val (next, decision) = check(state, 0.9, now, distanceFromDisconnect = 4.0)
            assertNull(decision)
            state = next
            now += 5000L
        }
    }

    @Test
    fun `moving far enough but not walking long enough does not suggest yet`() {
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt, distanceFromDisconnect = 200.0)
        val (s2, decision) = check(s1, 1.4, now = disconnectedAt + 3000L, distanceFromDisconnect = 200.0)
        assertEquals(3000L, s2.walkAccumMs)
        assertNull(decision)
    }

    @Test
    fun `displacement is re-checked at the moment the walk threshold is crossed`() {
        // Enough walking accrues while still beside the car, then the user
        // actually leaves: the suggestion fires on the sample where both hold.
        var state = WalkAwayState()
        var now = disconnectedAt
        repeat(6) {
            val (next, _) = check(state, 1.4, now, distanceFromDisconnect = 5.0)
            state = next
            now += 3000L
        }
        val (_, decision) = check(state, 1.4, now, distanceFromDisconnect = 80.0)
        assertEquals(WalkAwayDecision.SuggestStart, decision)
    }

    @Test
    fun `sitting in the car for a while before getting out still suggests`() {
        // A real pattern the window has to survive: engine off, phone still,
        // a few minutes of paperwork or a phone call, then walk away.
        var state = WalkAwayState()
        var now = disconnectedAt
        repeat(20) {
            val (next, d) = check(state, 0.1, now, distanceFromDisconnect = 2.0)
            assertNull(d)
            state = next
            now += 10_000L
        }
        var decision: WalkAwayDecision? = null
        repeat(5) {
            val (next, d) = check(state, 1.4, now, distanceFromDisconnect = 60.0)
            state = next
            if (d != null && decision == null) decision = d
            now += 3000L
        }
        assertEquals(WalkAwayDecision.SuggestStart, decision)
    }

    // ── aborting ─────────────────────────────────────────────────

    @Test
    fun `vehicular speed right after the disconnect aborts the window`() {
        // The link dropped in a tunnel or at a light — the car never stopped.
        val (_, decision) = check(WalkAwayState(), 15.0, now = disconnectedAt + 2000L)
        assertEquals(WalkAwayDecision.Abort, decision)
    }

    @Test
    fun `speed exactly at the abort threshold aborts`() {
        val (_, decision) = check(WalkAwayState(), abortSpeed, now = disconnectedAt + 2000L)
        assertEquals(WalkAwayDecision.Abort, decision)
    }

    @Test
    fun `a brisk run does not abort the window`() {
        // 4.5 m/s is a run, not a vehicle — it must not kill the window, and
        // it is above the walking band so it accumulates nothing either.
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt)
        val (s2, decision) = check(s1, 4.5, now = disconnectedAt + 4000L)
        assertNull(decision)
        assertEquals(0L, s2.walkAccumMs)
    }

    @Test
    fun `abort wins even after walking evidence has accumulated`() {
        // Walked from the car to a bus stop, then rode off: whatever this is,
        // it is no longer a parking spot worth asking about.
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt)
        val (s2, _) = check(s1, 1.4, now = disconnectedAt + 3000L)
        val (_, decision) = check(s2, 20.0, now = disconnectedAt + 6000L)
        assertEquals(WalkAwayDecision.Abort, decision)
    }

    @Test
    fun `the window expires`() {
        val (_, decision) = check(WalkAwayState(), 1.4, now = disconnectedAt + window)
        assertEquals(WalkAwayDecision.Abort, decision)
    }

    @Test
    fun `an expired window expires even on a fix with no usable speed`() {
        // Expiry is a function of elapsed time, not of what this fix carried.
        val (_, decision) = check(WalkAwayState(), null, now = disconnectedAt + window)
        assertEquals(WalkAwayDecision.Abort, decision)
    }

    // ── speeds that are neither walking nor vehicular ─────────────

    @Test
    fun `an in-between speed neither accumulates nor destroys existing evidence`() {
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt)
        val (s2, _) = check(s1, 1.4, now = disconnectedAt + 4000L)
        assertEquals(4000L, s2.walkAccumMs)
        val (s3, decision) = check(s2, 4.5, now = disconnectedAt + 7000L)
        assertEquals(4000L, s3.walkAccumMs)
        assertEquals(disconnectedAt + 7000L, s3.lastSampleAt)
        assertNull(decision)
    }

    @Test
    fun `an unknown speed is ignored entirely, clock included`() {
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt)
        val (s2, _) = check(s1, 1.4, now = disconnectedAt + 4000L)
        val (s3, decision) = check(s2, null, now = disconnectedAt + 6000L)
        assertEquals(s2, s3)
        assertNull(decision)
        val (s4, _) = check(s3, Double.NaN, now = disconnectedAt + 7000L)
        assertEquals(s2, s4)
    }

    @Test
    fun `a single sample cannot contribute more than the sample cap`() {
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt, distanceFromDisconnect = 5.0)
        val (s2, _) = check(s1, 1.4, now = disconnectedAt + 120_000L, distanceFromDisconnect = 5.0)
        assertEquals(sampleCap, s2.walkAccumMs)
    }

    @Test
    fun `a clock that jumps backwards contributes nothing rather than a negative`() {
        val (s1, _) = check(WalkAwayState(), 1.4, now = disconnectedAt + 10_000L, distanceFromDisconnect = 5.0)
        val (s2, _) = check(s1, 1.4, now = disconnectedAt + 9000L, distanceFromDisconnect = 5.0)
        assertEquals(0L, s2.walkAccumMs)
    }

    // ── idempotency ──────────────────────────────────────────────

    @Test
    fun `once suggested the window never suggests again`() {
        val state = WalkAwayState(walkAccumMs = requiredWalk, suggested = true)
        val (newState, decision) = check(state, 1.4, now = disconnectedAt + 20_000L)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `a suggested window is not re-aborted by later vehicular movement`() {
        // Driving away after the suggestion was already raised must not
        // produce a second decision for the caller to act on.
        val state = WalkAwayState(walkAccumMs = requiredWalk, suggested = true)
        val (_, decision) = check(state, 25.0, now = disconnectedAt + 60_000L)
        assertNull(decision)
    }
}
