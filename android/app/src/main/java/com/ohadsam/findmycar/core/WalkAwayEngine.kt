package com.ohadsam.findmycar.core

/**
 * State of one "did they park and walk away?" window, opened by a real
 * Bluetooth disconnect from a vehicle whose `bluetoothAutoStart` is OFF.
 *
 * [walkAccumMs] is accumulated time at pedestrian speed, for the same reason
 * GpsDecisionState accumulates rather than timing continuously: crossing a
 * road, waiting at a light or digging keys out of a pocket produces gaps, and
 * a "continuously since" timer would restart at each one.
 */
data class WalkAwayState(
    val walkAccumMs: Long = 0L,
    val lastSampleAt: Long? = null,
    val suggested: Boolean = false,
)

sealed class WalkAwayDecision {
    /** Ask whether to save a parking, at the location captured on disconnect. */
    object SuggestStart : WalkAwayDecision()

    /**
     * Close the window without asking: either movement is clearly still
     * vehicular (the link dropped mid-drive, the car never stopped) or the
     * window has simply run out.
     */
    object Abort : WalkAwayDecision()
}

/**
 * Pure decision logic for the walk-away parking suggestion — the counterpart
 * to GpsDecisionEngine, which decides when a parking has *ended*. Same shape:
 * an explicit (state, inputs) -> (state, decision) reducer with the current
 * time passed in and never read internally, so tests are deterministic with no
 * Android framework at all.
 *
 * **Why this exists**: `bluetoothAutoStart` saves a parking the instant the
 * Bluetooth link drops. That is right for people who always park where they
 * disconnect, but wrong every time the link drops at a red light, in a tunnel
 * or while the car is still moving — and wrong for anyone who does not want a
 * parking saved without being asked. With auto-start OFF, a disconnect
 * currently produces nothing at all. This fills that gap using the one signal
 * that genuinely separates "parked and walked off" from "the link dropped":
 * the phone then moves at *walking* speed, away from where it disconnected.
 *
 * **The location is captured at disconnect, not when this fires.** By the time
 * walking is confirmed the user is tens of metres away, so the suggestion
 * carries the disconnect fix. Saving where they are standing when they tap
 * would be worse than not asking.
 *
 * **Deliberately biased toward asking (the v1.42.0 lesson).** Every threshold
 * here is set so the realistic failure is "asks when you would rather it
 * didn't" — a single dismissible prompt — never "silently never asks", which
 * is the failure mode that has actually cost this project weeks. In
 * particular [walkMinSpeed] sits low enough that GPS jitter can nudge the
 * accumulator; that is fine, because [minDisplacementMeters] is what actually
 * proves the user left, and jitter does not move anyone 30 metres.
 */
object WalkAwayEngine {
    /**
     * @param speed best available speed in m/s, or null when genuinely unknown
     *   (GpsDecisionEngine.effectiveSpeed supplies this on both sides).
     * @param distanceFromDisconnectMeters how far the phone has moved from
     *   where the link dropped — the caller owns the geo math.
     * @param walkMinSpeed lower edge of the pedestrian band.
     * @param walkMaxSpeed upper edge of the pedestrian band.
     * @param abortSpeed speed above which the phone is unambiguously still in
     *   a vehicle. Set well above a brisk run, and well below the vehicle bar
     *   GpsDecisionEngine uses: a single clearly-vehicular sample right after a
     *   disconnect is enough to conclude the car never stopped here.
     * @param requiredWalkMs accumulated pedestrian-band time before suggesting.
     * @param minDisplacementMeters how far from the disconnect point the user
     *   must actually have got. Time alone is not enough — pacing beside the
     *   car while loading shopping is walking, but it is not leaving. This is
     *   also what makes a permissive [walkMinSpeed] safe.
     * @param windowMs how long after the disconnect this may still fire. A
     *   suggestion an hour later is about a decision already made.
     * @param sampleCapMs ceiling on any single sample's contribution, so one
     *   fix after a long gap cannot fill the accumulator at once (same
     *   reasoning as GpsDecisionEngine.checkSpeed's own cap).
     */
    fun check(
        state: WalkAwayState,
        speed: Double?,
        distanceFromDisconnectMeters: Double,
        disconnectedAt: Long,
        walkMinSpeed: Double,
        walkMaxSpeed: Double,
        abortSpeed: Double,
        requiredWalkMs: Long,
        minDisplacementMeters: Double,
        windowMs: Long,
        sampleCapMs: Long,
        now: Long,
    ): Pair<WalkAwayState, WalkAwayDecision?> {
        if (state.suggested) return state to null

        // Window expiry is checked first, and before the unknown-speed return
        // below, for the same reason GpsDecisionEngine expires evidence first:
        // it is a function of elapsed time, not of whether this particular fix
        // happened to carry a usable speed.
        if (now - disconnectedAt >= windowMs) return state to WalkAwayDecision.Abort

        if (speed == null || speed.isNaN()) return state to null

        // Still moving like a vehicle: the link dropped mid-drive. Give up
        // rather than wait for the eventual walk, which will happen at a
        // destination this disconnect fix knows nothing about.
        if (speed >= abortSpeed) return state to WalkAwayDecision.Abort

        val delta = when (val last = state.lastSampleAt) {
            null -> 0L // first sample of the window has no interval behind it
            else -> (now - last).coerceIn(0L, sampleCapMs)
        }

        if (speed < walkMinSpeed || speed > walkMaxSpeed) {
            // Stationary, or between the walking and vehicular bands. Not
            // evidence of walking, but not evidence against it either — the
            // accumulator is deliberately left intact, exactly as a red light
            // does not undo driving in GpsDecisionEngine.
            return state.copy(lastSampleAt = now) to null
        }

        val accum = state.walkAccumMs + delta
        val advanced = state.copy(walkAccumMs = accum, lastSampleAt = now)

        val walkedEnough = accum >= requiredWalkMs
        val movedEnough = distanceFromDisconnectMeters >= minDisplacementMeters
        return if (walkedEnough && movedEnough) {
            advanced.copy(suggested = true) to WalkAwayDecision.SuggestStart
        } else {
            advanced to null
        }
    }
}
