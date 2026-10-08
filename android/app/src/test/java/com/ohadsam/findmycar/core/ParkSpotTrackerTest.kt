package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkSpotTrackerTest {
    private val baseLat = 32.0
    private val baseLng = 34.8
    private val t0 = 1_000_000L

    /** ~1.11 m per 0.00001 degrees of latitude. */
    private fun north(meters: Double) = baseLat + meters / 111_000.0

    /** Feeds samples (meters north of the base, speed, seconds after t0), stopping at the first decision. */
    private fun run(
        samples: List<Triple<Double, Double?, Int>>,
        anchored: Boolean = true,
        state0: ParkSpotState = ParkSpotState(),
    ): Pair<ParkSpotState, List<ParkSpotDecision>> {
        var s = state0
        val decisions = mutableListOf<ParkSpotDecision>()
        for ((m, speed, sec) in samples) {
            val (n, d) = ParkSpotTracker.check(
                s, north(m), baseLng, speed,
                if (anchored) baseLat else null, if (anchored) baseLng else null,
                t0, t0 + sec * 1000L,
            )
            s = n
            if (d != null) decisions += d
        }
        return s to decisions
    }

    private fun walkAway(fromSec: Int, startMeters: Double, seconds: Int, mps: Double = 1.4) =
        (0..seconds step 3).map { Triple(startMeters + mps * it, mps, fromSec + it) }

    @Test
    fun `walking away from the disconnect spot confirms it, unchanged question`() {
        val (_, d) = run(walkAway(0, 0.0, 40))
        val parked = d.single() as ParkSpotDecision.Parked
        assertFalse(parked.reanchored)
        assertEquals(t0, parked.at)
        assertEquals(baseLat, parked.lat, 1e-9)
    }

    @Test
    fun `pacing beside the car is not leaving it`() {
        val pacing = (0..30 step 3).map { Triple(if (it % 6 == 0) 0.0 else 4.0, 1.2, it) }
        val (_, d) = run(pacing)
        assertTrue(d.isEmpty())
    }

    @Test
    fun `first vehicle-speed sample retracts, only once`() {
        val (s, d) = run(listOf(Triple(0.0, 12.0, 3), Triple(40.0, 12.0, 6), Triple(80.0, 12.0, 9)))
        assertEquals(listOf<ParkSpotDecision>(ParkSpotDecision.Retract), d)
        assertTrue(s.sawVehicle)
    }

    @Test
    fun `mid-drive drop - the car parks later and the spot is where it last moved`() {
        val drive = listOf(Triple(0.0, 12.0, 3), Triple(500.0, 12.0, 40), Triple(900.0, 8.0, 70), Triple(930.0, 3.0, 76))
        // Stopped, then walks off from ~930 m.
        val walk = walkAway(100, 932.0, 60)
        val (_, d) = run(drive + walk)
        assertEquals(ParkSpotDecision.Retract, d[0])
        val parked = d[1] as ParkSpotDecision.Parked
        assertTrue(parked.reanchored)
        // The last fix faster than walking pace was the one at 930 m.
        assertEquals(north(930.0), parked.lat, 1e-9)
        assertEquals(t0 + 76_000L, parked.at)
    }

    @Test
    fun `crawling in a jam never reads as parking`() {
        val moving = listOf(Triple(0.0, 12.0, 3))
        // 2.4 m/s for 40 s, then a burst above walking pace resets everything.
        val crawl = (0..40 step 3).map { Triple(10.0 + 2.4 * it, 2.4, 6 + it) }
        val burst = listOf(Triple(130.0, 3.5, 49))
        val more = (0..12 step 3).map { Triple(135.0 + 2.4 * it, 2.4, 52 + it) }
        val (_, d) = run(moving + crawl + burst + more)
        assertEquals(listOf<ParkSpotDecision>(ParkSpotDecision.Retract), d)
    }

    @Test
    fun `a short slow crawl is not enough either`() {
        val crawl = (0..9 step 3).map { Triple(10.0 + 2.0 * it, 2.0, 6 + it) }
        val (_, d) = run(listOf(Triple(0.0, 12.0, 3)) + crawl)
        assertEquals(listOf<ParkSpotDecision>(ParkSpotDecision.Retract), d)
    }

    @Test
    fun `standing still does not pre-load walking time`() {
        val still = (0..60 step 3).map { Triple(0.0, 0.0, it) }
        // One walking sample right after a long pause credits nothing.
        val (s, d) = run(still + listOf(Triple(2.0, 1.4, 63)))
        assertTrue(d.isEmpty())
        assertEquals(0L, s.walkAccumMs)
    }

    @Test
    fun `a spot is required at the disconnect - without one nothing is confirmed`() {
        val (_, d) = run(walkAway(0, 0.0, 60), anchored = false)
        assertTrue(d.isEmpty())
    }

    @Test
    fun `unknown speed is ignored`() {
        val (s, d) = run(listOf(Triple(0.0, null, 3), Triple(0.0, Double.NaN, 6)))
        assertTrue(d.isEmpty())
        assertEquals(ParkSpotState(), s)
    }

    @Test
    fun `window expires after the quiet period, measured from the last vehicle sample`() {
        val (_, quiet) = run(listOf(Triple(0.0, 0.0, 601)))
        assertEquals(listOf<ParkSpotDecision>(ParkSpotDecision.GiveUp), quiet)

        // Driving until minute 20 keeps the watch alive at minute 25...
        val alive = ParkSpotState(sawVehicle = true, lastVehicleAt = t0 + 20 * 60_000L)
        val (_, d1) = run(listOf(Triple(0.0, 0.0, 25 * 60)), state0 = alive)
        assertTrue(d1.isEmpty())
        // ...but not at minute 31.
        val (_, d2) = run(listOf(Triple(0.0, 0.0, 31 * 60)), state0 = alive)
        assertEquals(listOf<ParkSpotDecision>(ParkSpotDecision.GiveUp), d2)
    }

    @Test
    fun `after a decision nothing more is reported`() {
        val (s, _) = run(walkAway(0, 0.0, 40))
        assertTrue(s.decided)
        val (_, again) = run(walkAway(60, 100.0, 40), state0 = s)
        assertNull(again.firstOrNull())
    }
}
