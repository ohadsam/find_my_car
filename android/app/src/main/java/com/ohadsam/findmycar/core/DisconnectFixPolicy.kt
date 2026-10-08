package com.ohadsam.findmycar.core

/**
 * Which location counts as "where the car is" for a walk-away parking
 * suggestion (v1.55.0). Pure: no Android framework, no wall clock.
 *
 * The suggestion's "שמור חניה" is answered minutes later, from the shade, by
 * someone already walking away — so the spot it saves has to be sampled AT the
 * Bluetooth disconnect, never at the tap. Two things go wrong with a naive
 * `getLastKnownLocation()` at that moment:
 *
 *  - it may be hours old and from somewhere else entirely (nothing was
 *    requesting location, so the system's cache is whatever it last held);
 *  - the service's own location watch is not running yet (it starts because
 *    the window opened), so there is nothing fresher to read.
 *
 * So the spot is chosen in two steps: a cached fix is accepted only if it is
 * recent ([acceptCached]); and the first update the watch delivers right after
 * the disconnect — while the user is still next to the car — replaces it when
 * it is good enough, or fills in when there was no usable cache ([adoptUpdate]).
 * After that first fix every later update is the user walking away and is never
 * adopted.
 */
object DisconnectFixPolicy {
    /** A cached fix older than this is somewhere the car was, not where it is. */
    const val CACHED_MAX_AGE_MS = 60_000L

    /**
     * How long after the disconnect an update may still become the spot. At
     * walking pace this bounds the error to a few tens of metres.
     */
    const val FRESH_WINDOW_MS = 45_000L

    /** An update at least this accurate replaces a cached fix. */
    const val FRESH_MAX_ACCURACY_M = 50f

    /** Small negative ages are clock skew, not a fix from the future. */
    private const val CLOCK_SKEW_MS = 5_000L

    fun acceptCached(ageMs: Long): Boolean = ageMs in -CLOCK_SKEW_MS..CACHED_MAX_AGE_MS

    /**
     * Should a location update received [sinceDisconnectMs] after the
     * disconnect become the parking spot?
     *
     * @param hasSpot the window already holds a spot (the accepted cached fix)
     * @param spotIsFresh that spot is itself a post-disconnect update that met
     *   [FRESH_MAX_ACCURACY_M] — nothing later may replace it
     */
    fun adoptUpdate(hasSpot: Boolean, spotIsFresh: Boolean, sinceDisconnectMs: Long, accuracyM: Float): Boolean {
        if (spotIsFresh) return false
        if (sinceDisconnectMs < 0 || sinceDisconnectMs > FRESH_WINDOW_MS) return false
        // With nothing recorded, any fix beats having to refuse the save; with
        // a recent cached fix in hand, only a clearly good one is worth swapping.
        return !hasSpot || accuracyM <= FRESH_MAX_ACCURACY_M
    }

    /** Whether an adopted update is good enough to end the search. */
    fun isFresh(accuracyM: Float): Boolean = accuracyM <= FRESH_MAX_ACCURACY_M
}
