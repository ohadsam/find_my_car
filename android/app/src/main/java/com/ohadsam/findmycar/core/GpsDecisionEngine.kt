package com.ohadsam.findmycar.core

/**
 * Mirrors js/app.js's #state.gpsSpeedSince / #state.gpsEndSuggested — per
 * vehicle, since each vehicle has its own parking session and its own
 * "already suggested this session" flag. Immutable so GpsDecisionEngine can
 * stay a pure function of (state, inputs) -> (new state, decision).
 */
data class GpsDecisionState(
    val speedSince: Long? = null,
    val endSuggested: Boolean = false,
)

/** Mirrors js/app.js's #suggestGpsEnd() — the only decision GPS ever makes. */
sealed class GpsDecision {
    object SuggestEnd : GpsDecision()
}

/**
 * Pure, framework-independent port of js/app.js's #checkGpsSpeed/
 * #checkGpsDistance/#suggestGpsEnd decision logic — NOT their side effects
 * (opening the confirmation modal, showing a background notification).
 * Given explicit state in, explicit state out, and the current time as a
 * parameter (never reads the wall clock itself), so it's deterministic and
 * testable with plain JUnit and a fake clock — no Android framework, no
 * Robolectric, no emulator.
 *
 * This is Stage 3 of the native background-detection migration (see
 * CLAUDE.md's "Native background detection" section) — a pure engine only,
 * matching Stage 1's BtDecisionEngine pattern: not yet wired into a real
 * location watch. That wiring (a FusedLocationProviderClient/LocationManager
 * watch running inside ParkingForegroundService, shadow mode like Stage 2)
 * is a separate follow-up stage, split out for the same reason BT's engine
 * and its real-event wiring were two separate stages rather than one.
 */
object GpsDecisionEngine {
    /**
     * @param now current time in epoch millis — supplied by the caller
     *   (rather than read internally) so tests are fully deterministic.
     */
    fun checkSpeed(
        state: GpsDecisionState,
        hasCurrentParking: Boolean,
        gpsAutoEndEnabled: Boolean,
        speed: Double?,
        speedThreshold: Double,
        speedDuration: Long,
        now: Long,
    ): Pair<GpsDecisionState, GpsDecision?> {
        if (!hasCurrentParking || state.endSuggested || !gpsAutoEndEnabled) return state to null
        if (speed == null || speed.isNaN() || speed < speedThreshold) {
            return state.copy(speedSince = null) to null
        }
        if (state.speedSince == null) {
            return state.copy(speedSince = now) to null
        }
        return if (now - state.speedSince >= speedDuration) {
            suggestEnd(state.copy(speedSince = null))
        } else {
            state to null
        }
    }

    /**
     * Second, independent signal alongside speed: catches movement that
     * wouldn't cross the speed threshold (e.g. a device that never reports
     * speed, or being driven away slowly in traffic).
     */
    fun checkDistance(
        state: GpsDecisionState,
        hasCurrentParking: Boolean,
        gpsAutoEndEnabled: Boolean,
        distanceMeters: Double,
        distanceThreshold: Double,
    ): Pair<GpsDecisionState, GpsDecision?> {
        if (!hasCurrentParking || state.endSuggested || !gpsAutoEndEnabled) return state to null
        return if (distanceMeters >= distanceThreshold) suggestEnd(state) else state to null
    }

    private fun suggestEnd(state: GpsDecisionState): Pair<GpsDecisionState, GpsDecision?> {
        // Race guard, mirrors js/app.js's #suggestGpsEnd(): speed and
        // distance can both cross their thresholds on the same position
        // update, and only the first should ever produce a decision.
        if (state.endSuggested) return state to null
        return state.copy(endSuggested = true, speedSince = null) to GpsDecision.SuggestEnd
    }
}
