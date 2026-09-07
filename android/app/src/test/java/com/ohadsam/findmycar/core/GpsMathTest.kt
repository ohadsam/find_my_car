package com.ohadsam.findmycar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GpsMathTest {
    @Test
    fun `distance between identical points is zero`() {
        val d = GpsMath.distanceMeters(31.7767, 35.2345, 31.7767, 35.2345)
        assertEquals(0.0, d, 0.0001)
    }

    @Test
    fun `distance is symmetric`() {
        val a = GpsMath.distanceMeters(31.7767, 35.2345, 31.7800, 35.2400)
        val b = GpsMath.distanceMeters(31.7800, 35.2400, 31.7767, 35.2345)
        assertEquals(a, b, 0.0001)
    }

    @Test
    fun `known distance is within tolerance`() {
        // ~0.003 degrees latitude apart at this latitude is roughly 333m —
        // a coarse sanity check, not a precision requirement.
        val d = GpsMath.distanceMeters(31.7767, 35.2345, 31.7797, 35.2345)
        assertTrue("expected ~333m, got $d", abs(d - 333.6) < 5.0)
    }

    @Test
    fun `distance well below the parking threshold is small`() {
        // ~10m apart (roughly 0.0001 degrees latitude).
        val d = GpsMath.distanceMeters(31.7767, 35.2345, 31.7768, 35.2345)
        assertTrue("expected a small distance, got $d", d < 15.0)
    }
}
