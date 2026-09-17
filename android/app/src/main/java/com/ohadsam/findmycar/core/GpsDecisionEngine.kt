package com.ohadsam.findmycar.core

/**
 * Mirrors js/app.js's GPS detection state — per vehicle, since each vehicle has
 * its own parking session and its own "already suggested this session" flag.
 * Immutable so GpsDecisionEngine can stay a pure function of (state, inputs) ->
 * (new state, decision).
 *
 * [speedAccumMs] is **accumulated** time observed at or above the vehicle-speed
 * threshold, not a "continuously since" timestamp. A continuous timer is reset
 * by every red light, so requiring a meaningful sustained duration would make
 * the speed trigger nearly unreachable in city driving. Accumulating instead
 * means a genuine drive keeps making progress toward the threshold while a
 * walk — which never produces a single sample above it — makes none.
 *
 * [lastAboveThresholdAt] is what lets that evidence expire: without it, one
 * ride early in a parking session leaves the distance trigger armed for the
 * rest of it, so a plain walk hours later fires the suggestion.
 */
data class GpsDecisionState(
    val speedAccumMs: Long = 0L,
    val lastSampleAt: Long? = null,
    val lastAboveThresholdAt: Long? = null,
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
 * **Real, previously-shipped false positive (v1.40.0)**: walking produced the
 * "your car seems to have moved" suggestion. The speed trigger was never
 * involved — walking is ~1.4 m/s and the threshold was 7 m/s. The culprit was
 * [checkDistance], which had no speed condition *at all*: being 300m from the
 * parked car was sufficient, however you got there. It was written that way
 * deliberately, to catch movement the speed check would miss on devices with
 * unreliable `coords.speed` — but "no speed condition" also means "on foot
 * counts". Both halves are fixed below.
 *
 * **Second round (v1.41.0)**: evidence accumulated once never decayed, so a
 * ride earlier in the parking session left the distance trigger armed for a
 * plain walk hours later. [checkSpeed] therefore expires evidence that has
 * gone stale (evidenceTtlMs).
 *
 * **Third round (v1.42.0) — the threshold was set from intuition, not data,
 * and it broke real detection.** v1.40.0 raised the vehicle-speed bar to
 * 13.9 m/s (50 km/h), and v1.41.0 additionally required the first such sample
 * to occur near the parked car. Production diagnostic-log evidence from a real
 * drive killed both ideas:
 *
 * ```
 * 18:06:30  dist=17m     gpsFixes=43  vehEvid=0s   ← got into the car
 * 18:11:30  dist=1031m   gpsFixes=74  vehEvid=0s   ← a full km driven, no evidence
 * 18:16:30  dist=1982m   gpsFixes=70  vehEvid=11s  ← only now, 2km out
 * ```
 *
 * 1031m in five minutes is a 12 km/h average — ordinary city traffic, where
 * 50 km/h is barely touched. So the distance trigger stayed disarmed while the
 * driver was already a kilometre away, and the suggestion finally fired ten
 * minutes late. The v1.41.0 departure anchor would have made it *permanent*:
 * the first 50 km/h sample happened well past 1km from the car, far outside any
 * sane radius, so the trigger would never have armed at all.
 *
 * The bar is therefore back to 7 m/s (25 km/h) — comfortably above walking
 * (~1.4 m/s) and running (~3-5 m/s), which is all it ever had to exclude — and
 * the departure anchor is gone. **A gate that can permanently disarm detection
 * is the wrong trade for this app**: a missed real departure is the failure
 * users actually report, repeatedly, while the false positive it guarded
 * against (commuting by train from a parked car) is rarer and self-limiting
 * thanks to the TTL.
 */
object GpsDecisionEngine {
    /**
     * Best available speed in m/s, or null if genuinely unknown.
     *
     * The platform's own reported speed is preferred, but it is absent or zero
     * on plenty of real devices — which is exactly why [checkDistance] used to
     * have no speed condition. Deriving speed from the distance and time
     * between consecutive fixes removes that dependency, so requiring speed
     * evidence no longer risks disabling detection on those devices.
     *
     * @param distanceFromPrevMeters straight-line distance from the previous
     *   fix; the caller owns the geo math (GpsMath/Utils.distance).
     * @param minIntervalMs shortest interval a speed may be derived over.
     *   Consecutive fixes seconds apart are dominated by GPS jitter — a 20m
     *   error over 1s reads as 20 m/s, well past any vehicle threshold — so a
     *   derivation over too short an interval is reported as unknown rather
     *   than as fabricated vehicle evidence. The caller must correspondingly
     *   keep (not advance) its previous-fix baseline while the interval is
     *   still shorter than this, or the interval can never grow past it.
     */
    fun effectiveSpeed(
        reportedSpeed: Double?,
        distanceFromPrevMeters: Double?,
        msSincePrev: Long?,
        minIntervalMs: Long,
    ): Double? {
        if (reportedSpeed != null && !reportedSpeed.isNaN() && reportedSpeed > 0.0) return reportedSpeed
        if (distanceFromPrevMeters == null || msSincePrev == null) return null
        if (msSincePrev <= 0L || msSincePrev < minIntervalMs) return null
        if (distanceFromPrevMeters.isNaN() || distanceFromPrevMeters < 0.0) return null
        return distanceFromPrevMeters / (msSincePrev / 1000.0)
    }

    /**
     * @param speedThreshold m/s at or above which travel is taken to be
     *   vehicular. Set above walking and running — which is all it has to
     *   exclude — and NOT at a "proper driving speed": city traffic spends most
     *   of its time well below that, and a bar set there disarms the distance
     *   trigger for an entire drive (see the class doc's v1.42.0 evidence).
     * @param requiredAccumMs total accumulated time above [speedThreshold]
     *   before suggesting on speed alone.
     * @param sampleCapMs ceiling on how much time a single sample may
     *   contribute. Without it, a long gap between fixes (app suspended, no
     *   updates while parked) followed by one fast sample would dump the whole
     *   gap into the accumulator and fire immediately.
     * @param evidenceTtlMs how long accumulated evidence stays valid with no
     *   further above-threshold sample. Without expiry, one ride early in a
     *   parking session leaves the distance trigger armed for the rest of it —
     *   so a plain walk hours later fires the suggestion, which is the original
     *   false positive arriving by a different route.
     * @param now current time in epoch millis — supplied by the caller
     *   (rather than read internally) so tests are fully deterministic.
     */
    fun checkSpeed(
        state: GpsDecisionState,
        hasCurrentParking: Boolean,
        gpsAutoEndEnabled: Boolean,
        speed: Double?,
        speedThreshold: Double,
        requiredAccumMs: Long,
        sampleCapMs: Long,
        evidenceTtlMs: Long,
        now: Long,
    ): Pair<GpsDecisionState, GpsDecision?> {
        if (!hasCurrentParking || state.endSuggested || !gpsAutoEndEnabled) return state to null

        // Expire stale evidence FIRST, before the unknown-speed return below:
        // evidence going stale is a function of elapsed time, not of whether
        // this particular fix happened to carry a usable speed.
        val lastAbove = state.lastAboveThresholdAt
        val fresh = if (lastAbove != null && now - lastAbove >= evidenceTtlMs) {
            state.copy(speedAccumMs = 0L, lastAboveThresholdAt = null)
        } else state

        // An unknown speed is not evidence of anything — including not evidence
        // of having been stationary — so it must leave lastSampleAt alone as
        // well. Advancing it here would shrink the interval credited to the
        // next KNOWN sample: when speed is derived (every few fixes, once the
        // interval is long enough), the elapsed time it represents is the time
        // since the last known reading, not since the last fix of any kind.
        if (speed == null || speed.isNaN()) return fresh to null

        val delta = when (val last = fresh.lastSampleAt) {
            null -> 0L // first sample of the session has no interval behind it
            else -> (now - last).coerceIn(0L, sampleCapMs)
        }

        // Deliberately NOT reset when a sample falls below the threshold: this
        // is accumulated vehicle time for the whole parking session, and a stop
        // at a traffic light does not make the preceding driving un-happen.
        if (speed < speedThreshold) return fresh.copy(lastSampleAt = now) to null

        val accum = fresh.speedAccumMs + delta
        val advanced = fresh.copy(
            speedAccumMs = accum,
            lastSampleAt = now,
            lastAboveThresholdAt = now,
        )

        return if (accum >= requiredAccumMs) suggestEnd(advanced) else advanced to null
    }

    /**
     * Second, independent signal alongside speed — it fires far sooner than
     * [checkSpeed]'s accumulated duration on a normal drive, so it remains the
     * trigger that actually catches most real departures.
     *
     * @param vehicleEvidenceMs accumulated time above the vehicle-speed
     *   threshold required before distance alone may suggest — and, since
     *   v1.41.0, that evidence must also have *begun near the parked car* and
     *   not gone stale (see [checkSpeed]). This is the fix
     *   for the walking false positive: distance says *how far*, never *how*,
     *   so it needs corroboration that a vehicle was involved. A short
     *   requirement (seconds, not minutes) keeps this firing promptly on a real
     *   drive while remaining unreachable on foot, and tolerates a single
     *   spurious GPS speed spike better than a bare "any sample above
     *   threshold" check would.
     */
    fun checkDistance(
        state: GpsDecisionState,
        hasCurrentParking: Boolean,
        gpsAutoEndEnabled: Boolean,
        distanceMeters: Double,
        distanceThreshold: Double,
        vehicleEvidenceMs: Long,
    ): Pair<GpsDecisionState, GpsDecision?> {
        if (!hasCurrentParking || state.endSuggested || !gpsAutoEndEnabled) return state to null
        if (distanceMeters < distanceThreshold) return state to null
        if (state.speedAccumMs < vehicleEvidenceMs) return state to null
        return suggestEnd(state)
    }

    private fun suggestEnd(state: GpsDecisionState): Pair<GpsDecisionState, GpsDecision?> {
        // Race guard, mirrors js/app.js's #suggestGpsEnd(): speed and
        // distance can both cross their thresholds on the same position
        // update, and only the first should ever produce a decision.
        if (state.endSuggested) return state to null
        return state.copy(endSuggested = true) to GpsDecision.SuggestEnd
    }
}
