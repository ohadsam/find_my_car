package com.ohadsam.findmycar.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectFixPolicyTest {
    @Test
    fun `a recent cached fix is accepted, an old one is not`() {
        assertTrue(DisconnectFixPolicy.acceptCached(10_000))
        assertTrue(DisconnectFixPolicy.acceptCached(DisconnectFixPolicy.CACHED_MAX_AGE_MS))
        assertFalse(DisconnectFixPolicy.acceptCached(DisconnectFixPolicy.CACHED_MAX_AGE_MS + 1))
        // The reported failure: a cache hours old, from somewhere else.
        assertFalse(DisconnectFixPolicy.acceptCached(3 * 60 * 60 * 1000L))
    }

    @Test
    fun `a fix stamped slightly in the future is clock skew, a far-future one is not`() {
        assertTrue(DisconnectFixPolicy.acceptCached(-2_000))
        assertFalse(DisconnectFixPolicy.acceptCached(-60_000))
    }

    @Test
    fun `with no cached spot the first update inside the window is adopted whatever its accuracy`() {
        assertTrue(DisconnectFixPolicy.adoptUpdate(false, false, 5_000, 300f))
    }

    @Test
    fun `a good fresh update replaces a cached spot but a poor one does not`() {
        assertTrue(DisconnectFixPolicy.adoptUpdate(true, false, 5_000, 8f))
        assertFalse(DisconnectFixPolicy.adoptUpdate(true, false, 5_000, 200f))
    }

    @Test
    fun `once a good fresh fix is the spot, later updates are the user walking away`() {
        assertFalse(DisconnectFixPolicy.adoptUpdate(true, true, 20_000, 5f))
    }

    @Test
    fun `an update after the window is never adopted, even with no spot`() {
        assertFalse(DisconnectFixPolicy.adoptUpdate(false, false, DisconnectFixPolicy.FRESH_WINDOW_MS + 1, 5f))
        assertFalse(DisconnectFixPolicy.adoptUpdate(false, false, -1, 5f))
    }

    @Test
    fun `a poor first update is adopted but does not end the search`() {
        assertTrue(DisconnectFixPolicy.adoptUpdate(false, false, 3_000, 120f))
        assertFalse(DisconnectFixPolicy.isFresh(120f))
        // So a better one shortly after may still replace it.
        assertTrue(DisconnectFixPolicy.adoptUpdate(true, false, 9_000, 10f))
        assertTrue(DisconnectFixPolicy.isFresh(10f))
    }
}
