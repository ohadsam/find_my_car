package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit — no Robolectric needed, GpsDecisionEngine has zero Android
 * framework dependency. Each test mirrors one branch of js/app.js's
 * #checkGpsSpeed/#checkGpsDistance/#suggestGpsEnd; keep these in sync with
 * that file if its decision logic ever changes.
 */
class GpsDecisionEngineTest {
    private val threshold = 13.9    // m/s ≈ 50 km/h, matches CFG.gpsSpeedThreshold
    private val duration = 120_000L // ms accumulated, matches CFG.gpsSpeedDuration
    private val sampleCap = 15_000L // matches CFG.gpsSpeedSampleCapMs
    private val evidence = 10_000L  // matches CFG.gpsVehicleEvidenceMs
    private val distanceThreshold = 300.0 // meters, matches CFG.gpsDistanceThreshold
    private val minInterval = 5000L // matches CFG.gpsDerivedSpeedMinIntervalMs

    private fun effective(reported: Double?, moved: Double?, elapsed: Long?) =
        GpsDecisionEngine.effectiveSpeed(reported, moved, elapsed, minInterval)

    private val departureRadius = 150.0 // matches CFG.gpsDepartureRadius
    private val evidenceTtl = 600_000L  // matches CFG.gpsEvidenceTtlMs

    /** Default distance is inside the departure radius — i.e. "just left the car". */
    private fun speed(
        state: GpsDecisionState,
        speed: Double?,
        now: Long,
        hasCurrentParking: Boolean = true,
        gpsAutoEndEnabled: Boolean = true,
        distanceFromParking: Double = 10.0,
    ) = GpsDecisionEngine.checkSpeed(
        state, hasCurrentParking, gpsAutoEndEnabled,
        speed, threshold, duration, sampleCap,
        distanceFromParking, departureRadius, evidenceTtl, now,
    )

    private fun distance(
        state: GpsDecisionState,
        distanceMeters: Double,
        hasCurrentParking: Boolean = true,
        gpsAutoEndEnabled: Boolean = true,
    ) = GpsDecisionEngine.checkDistance(
        state, hasCurrentParking, gpsAutoEndEnabled,
        distanceMeters, distanceThreshold, evidence,
    )

    // ── effectiveSpeed ───────────────────────────────────────────

    @Test
    fun `reported speed is preferred when the platform supplies one`() {
        assertEquals(18.0, effective(18.0, 1.0, 1000L)!!, 0.0001)
    }

    @Test
    fun `speed is derived from distance over time when the platform reports none`() {
        // 300m in 20s = 15 m/s — this is the whole point: plenty of real
        // devices never populate coords.speed / Location.hasSpeed().
        assertEquals(15.0, effective(null, 300.0, 20_000L)!!, 0.0001)
    }

    @Test
    fun `zero reported speed still falls back to derivation`() {
        // Some devices report a hard 0.0 rather than "unknown".
        assertEquals(10.0, effective(0.0, 100.0, 10_000L)!!, 0.0001)
    }

    @Test
    fun `NaN reported speed falls back to derivation`() {
        assertEquals(5.0, effective(Double.NaN, 50.0, 10_000L)!!, 0.0001)
    }

    @Test
    fun `no previous fix means speed is genuinely unknown`() {
        assertNull(effective(null, null, null))
        assertNull(effective(null, 100.0, null))
        assertNull(effective(null, null, 10_000L))
    }

    @Test
    fun `non-positive or NaN interval cannot derive a speed`() {
        assertNull(effective(null, 100.0, 0L))
        assertNull(effective(null, 100.0, -5L))
        assertNull(effective(null, Double.NaN, 10_000L))
        assertNull(effective(null, -1.0, 10_000L))
    }

    @Test
    fun `too short an interval is unknown rather than jitter-derived speed`() {
        // 20m of GPS jitter one second apart would read as 20 m/s — past the
        // vehicle threshold — if this guard weren't here.
        assertNull(effective(null, 20.0, 1000L))
        assertNull(effective(null, 20.0, minInterval - 1))
        assertEquals(4.0, effective(null, 20.0, minInterval)!!, 0.0001)
    }

    @Test
    fun `a short interval never suppresses a speed the platform itself reported`() {
        assertEquals(20.0, effective(20.0, 20.0, 1000L)!!, 0.0001)
    }

    // ── checkSpeed ───────────────────────────────────────────────

    @Test
    fun `no current parking produces no decision and leaves state untouched`() {
        val state = GpsDecisionState(speedAccumMs = 111L)
        val (newState, decision) = speed(state, 20.0, now = 1000L, hasCurrentParking = false)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `setting disabled produces no decision and leaves state untouched`() {
        val state = GpsDecisionState()
        val (newState, decision) = speed(state, 20.0, now = 1000L, gpsAutoEndEnabled = false)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `already suggested this session produces no decision`() {
        val state = GpsDecisionState(endSuggested = true)
        val (newState, decision) = speed(state, 20.0, now = 1000L)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `first sample of the session accumulates nothing but records the clock`() {
        val (newState, decision) = speed(GpsDecisionState(), 20.0, now = 1000L)
        assertEquals(0L, newState.speedAccumMs)
        assertEquals(1000L, newState.lastSampleAt)
        assertNull(decision)
    }

    @Test
    fun `walking speed never accumulates any vehicle evidence`() {
        // ~1.4 m/s over ten minutes of walking — the real-world false positive.
        var state = GpsDecisionState()
        var now = 0L
        repeat(120) {
            val (next, decision) = speed(state, 1.4, now)
            assertNull(decision)
            state = next
            now += 5000L
        }
        assertEquals(0L, state.speedAccumMs)
    }

    @Test
    fun `sustained driving accumulates the interval between samples`() {
        var state = GpsDecisionState()
        val (s1, _) = speed(state, 20.0, now = 0L)
        val (s2, d2) = speed(s1, 20.0, now = 5000L)
        assertEquals(5000L, s2.speedAccumMs)
        assertNull(d2)
        val (s3, d3) = speed(s2, 20.0, now = 10_000L)
        assertEquals(10_000L, s3.speedAccumMs)
        assertNull(d3)
        state = s3
        assertEquals(10_000L, state.lastSampleAt)
    }

    @Test
    fun `a below-threshold sample does not reset accumulated evidence`() {
        // A red light must not undo the driving that preceded it — this is
        // exactly why the state is accumulated rather than "sustained since".
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, _) = speed(s1, 20.0, now = 6000L)
        assertEquals(6000L, s2.speedAccumMs)
        val (s3, decision) = speed(s2, 0.5, now = 12_000L)
        assertEquals(6000L, s3.speedAccumMs)
        assertEquals(12_000L, s3.lastSampleAt)
        assertNull(decision)
    }

    @Test
    fun `an unknown speed sample is ignored entirely, clock included`() {
        // Unknown is not evidence of being stationary either — and advancing
        // the sample clock here would shrink the interval credited to the next
        // known reading, which is derived over the time since the last KNOWN
        // one, not since the last fix of any kind.
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, _) = speed(s1, 20.0, now = 6000L)
        assertEquals(6000L, s2.speedAccumMs)
        val (s3, _) = speed(s2, null, now = 9000L)
        assertEquals(s2, s3)
        val (s4, _) = speed(s3, Double.NaN, now = 12_000L)
        assertEquals(s2, s4)
        // The next known sample is credited the full interval since s2.
        val (s5, _) = speed(s4, 20.0, now = 13_000L)
        assertEquals(13_000L, s5.speedAccumMs)
    }

    @Test
    fun `a single sample cannot contribute more than the sample cap`() {
        // A long gap with no fixes (app suspended while parked) followed by one
        // fast sample must not dump the whole gap into the accumulator. Kept
        // under evidenceTtl so this isolates the cap from the expiry rule.
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, decision) = speed(s1, 20.0, now = 60_000L)
        assertEquals(sampleCap, s2.speedAccumMs)
        assertNull(decision)
    }

    // ── departure anchoring (v1.41.0) ────────────────────────────

    @Test
    fun `vehicle speed starting far from the car is not this car departing`() {
        // Walk to a station, then ride a train: real vehicle speed, but the
        // departure never began near the parked car, so it is a commute.
        var state = GpsDecisionState()
        var now = 0L
        repeat(30) {
            val (next, decision) = speed(state, 30.0, now, distanceFromParking = 400.0)
            assertNull(decision)
            state = next
            now += 10_000L
        }
        assertEquals(0L, state.speedAccumMs)
        assertFalse(state.departureStarted)
    }

    @Test
    fun `a commute that never counted leaves the distance trigger disarmed`() {
        // The end-to-end shape of the false positive: train ride far from the
        // car, then 500m away on foot — still nothing, because no evidence
        // was ever credited.
        val (afterRide, _) = speed(GpsDecisionState(), 30.0, now = 0L, distanceFromParking = 400.0)
        val (afterRide2, _) = speed(afterRide, 30.0, now = 20_000L, distanceFromParking = 900.0)
        assertEquals(0L, afterRide2.speedAccumMs)
        val (_, decision) = distance(afterRide2, 900.0)
        assertNull(decision)
    }

    @Test
    fun `vehicle speed starting at the car counts and keeps counting once away`() {
        // Getting in and driving off: the first fast sample is metres from the
        // spot, and the rest of the drive keeps accruing well beyond the radius.
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L, distanceFromParking = 20.0)
        assertTrue(s1.departureStarted)
        val (s2, _) = speed(s1, 20.0, now = 10_000L, distanceFromParking = 250.0)
        assertEquals(10_000L, s2.speedAccumMs)
        val (s3, _) = speed(s2, 20.0, now = 20_000L, distanceFromParking = 900.0)
        assertEquals(20_000L, s3.speedAccumMs)
    }

    @Test
    fun `a sample exactly at the departure radius still counts`() {
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L, distanceFromParking = departureRadius)
        assertTrue(s1.departureStarted)
    }

    // ── evidence expiry (v1.41.0) ────────────────────────────────

    @Test
    fun `evidence expires after the TTL with no further above-threshold sample`() {
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, _) = speed(s1, 20.0, now = 12_000L)
        assertEquals(12_000L, s2.speedAccumMs)

        // Much later, a walking sample: the ride has gone stale.
        val (s3, decision) = speed(s2, 1.4, now = 12_000L + evidenceTtl)
        assertEquals(0L, s3.speedAccumMs)
        assertNull(s3.lastAboveThresholdAt)
        assertNull(decision)
    }

    @Test
    fun `expired evidence disarms the distance trigger for a later walk`() {
        // THE regression test for the v1.41.0 half of the fix: a ride earlier
        // in the parking session must not leave 300m-on-foot armed forever.
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, _) = speed(s1, 20.0, now = 12_000L)
        assertEquals(GpsDecision.SuggestEnd, distance(s2, 500.0).second) // armed right now

        val (s3, _) = speed(s2, 1.4, now = 12_000L + evidenceTtl)
        val (_, decision) = distance(s3, 500.0)
        assertNull(decision)
    }

    @Test
    fun `an unknown-speed sample still expires stale evidence`() {
        // Expiry is a function of elapsed time, not of whether this particular
        // fix happened to carry a usable speed.
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, _) = speed(s1, 20.0, now = 12_000L)
        val (s3, _) = speed(s2, null, now = 12_000L + evidenceTtl)
        assertEquals(0L, s3.speedAccumMs)
    }

    @Test
    fun `a drive longer than the TTL never expires its own evidence`() {
        // Each sample is well inside the TTL, so a drive whose TOTAL span far
        // exceeds it still never self-expires — expiry measures the gap since
        // the last above-threshold sample, not how long the drive has run.
        var state = GpsDecisionState()
        var now = 0L
        var decision: GpsDecision? = null
        repeat(20) {
            val (next, d) = speed(state, 20.0, now)
            state = next
            if (d != null && decision == null) decision = d
            now += 60_000L
        }
        assertTrue(now > evidenceTtl) // the drive outlasted the TTL
        assertEquals(GpsDecision.SuggestEnd, decision)
    }

    @Test
    fun `evidence surviving a stop shorter than the TTL keeps the trigger armed`() {
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L)
        val (s2, _) = speed(s1, 20.0, now = 12_000L)
        // Stationary in traffic for half the TTL, then still armed.
        val (s3, _) = speed(s2, 0.0, now = 12_000L + evidenceTtl / 2)
        assertEquals(12_000L, s3.speedAccumMs)
        assertEquals(GpsDecision.SuggestEnd, distance(s3, 500.0).second)
    }

    @Test
    fun `departureStarted survives expiry so a real departure is never blocked`() {
        // Stuck at a light within the radius for longer than the TTL, then
        // driving off: the accumulator restarts, but the departure is still
        // recognised as this car's even once past the radius.
        val (s1, _) = speed(GpsDecisionState(), 20.0, now = 0L, distanceFromParking = 20.0)
        val (s2, _) = speed(s1, 0.0, now = evidenceTtl + 1000L, distanceFromParking = 30.0)
        assertEquals(0L, s2.speedAccumMs)
        assertTrue(s2.departureStarted)
        val (s3, _) = speed(s2, 20.0, now = evidenceTtl + 20_000L, distanceFromParking = 800.0)
        assertTrue(s3.speedAccumMs > 0L)
    }

    @Test
    fun `accumulated time reaching the required duration suggests end`() {
        var state = GpsDecisionState()
        var now = 0L
        var decision: GpsDecision? = null
        // 10s per sample, capped at 15s, so 12 intervals = 120s exactly.
        repeat(13) {
            val (next, d) = speed(state, 20.0, now)
            state = next
            if (d != null) decision = d
            now += 10_000L
        }
        assertEquals(GpsDecision.SuggestEnd, decision)
        assertTrue(state.endSuggested)
        assertTrue(state.speedAccumMs >= duration)
    }

    @Test
    fun `just short of the required duration produces no decision yet`() {
        val state = GpsDecisionState(speedAccumMs = duration - 5000L, lastSampleAt = 0L)
        val (newState, decision) = speed(state, 20.0, now = 4000L)
        assertEquals(duration - 1000L, newState.speedAccumMs)
        assertNull(decision)
    }

    @Test
    fun `a clock that jumps backwards contributes nothing rather than a negative`() {
        val state = GpsDecisionState(speedAccumMs = 5000L, lastSampleAt = 10_000L)
        val (newState, _) = speed(state, 20.0, now = 9000L)
        assertEquals(5000L, newState.speedAccumMs)
        assertEquals(9000L, newState.lastSampleAt)
    }

    // ── checkDistance ────────────────────────────────────────────

    @Test
    fun `distance below threshold produces no decision`() {
        val state = GpsDecisionState(speedAccumMs = duration)
        val (newState, decision) = distance(state, 100.0)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `walking past the distance threshold no longer suggests end`() {
        // THE regression test for v1.40.0: distance says how far, never how.
        // Without corroborating vehicle-speed evidence, 300m on foot is just
        // a walk — which is exactly what used to fire this suggestion.
        val state = GpsDecisionState(speedAccumMs = 0L)
        val (newState, decision) = distance(state, 500.0)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `partial vehicle evidence is still not enough for the distance trigger`() {
        val state = GpsDecisionState(speedAccumMs = evidence - 1)
        val (_, decision) = distance(state, 500.0)
        assertNull(decision)
    }

    @Test
    fun `distance past the threshold with vehicle evidence suggests end`() {
        // A real drive: the distance trigger still fires long before the
        // speed trigger's full accumulated duration is reached.
        val state = GpsDecisionState(speedAccumMs = evidence, lastSampleAt = 5000L)
        val (newState, decision) = distance(state, 300.0)
        assertEquals(GpsDecision.SuggestEnd, decision)
        assertTrue(newState.endSuggested)
        assertEquals(evidence, newState.speedAccumMs)
    }

    @Test
    fun `distance check ignores no current parking`() {
        val state = GpsDecisionState(speedAccumMs = duration)
        val (newState, decision) = distance(state, 500.0, hasCurrentParking = false)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `distance check ignores the setting being disabled`() {
        val state = GpsDecisionState(speedAccumMs = duration)
        val (newState, decision) = distance(state, 500.0, gpsAutoEndEnabled = false)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `distance check honors the already-suggested race guard`() {
        val state = GpsDecisionState(speedAccumMs = duration, endSuggested = true)
        val (newState, decision) = distance(state, 500.0)
        assertEquals(state, newState)
        assertNull(decision)
    }

    @Test
    fun `speed and distance both crossing on the same update only suggests once`() {
        // Mirrors the real caller: speed is checked first, its resulting state
        // (both the fresh evidence and endSuggested) is what checkDistance sees.
        val initial = GpsDecisionState(speedAccumMs = duration - 5000L, lastSampleAt = 0L)
        val (afterSpeed, speedDecision) = speed(initial, 20.0, now = 6000L)
        assertEquals(GpsDecision.SuggestEnd, speedDecision)

        val (afterDistance, distanceDecision) = distance(afterSpeed, 500.0)
        assertNull(distanceDecision)
        assertEquals(afterSpeed, afterDistance)
    }
}
