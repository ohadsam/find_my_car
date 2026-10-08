package com.ohadsam.findmycar.core

/**
 * State of one watch window opened by a Bluetooth disconnect (v1.56.0).
 *
 * [sawVehicle] flips once the phone is seen moving at vehicle speed after the
 * disconnect: from then on the disconnect fix is void (the link dropped
 * mid-drive, or the user simply drove off) and the candidate spot is the LAST
 * point at which the phone was still moving ([spotLat]/[spotLng]/[spotAt]) —
 * the car cannot have stopped before it.
 */
data class ParkSpotState(
    val sawVehicle: Boolean = false,
    val spotLat: Double? = null,
    val spotLng: Double? = null,
    val spotAt: Long? = null,
    val lastVehicleAt: Long? = null,
    val walkAccumMs: Long = 0L,
    val lastSampleAt: Long? = null,
    val decided: Boolean = false,
)

sealed class ParkSpotDecision {
    /**
     * The phone is moving like a vehicle for the first time since the
     * disconnect, so the question asked at the disconnect ("save the parking
     * here?") was wrong — withdraw it, but keep watching for where the car
     * actually stops.
     */
    object Retract : ParkSpotDecision()

    /**
     * The user walked away from a spot where the car stopped. [reanchored] is
     * false when that spot is the disconnect fix itself (the question already
     * showing is the right one) and true when it is a later stop point found
     * after the car had kept moving (the old question was withdrawn, a new one
     * is needed). [at] is when the car reached that spot.
     */
    data class Parked(val lat: Double, val lng: Double, val at: Long, val reanchored: Boolean) : ParkSpotDecision()

    /** Nothing conclusive within the allowed time — stop watching. */
    object GiveUp : ParkSpotDecision()
}

/**
 * Thresholds, kept in one place. These live only in Kotlin (there is no JS
 * counterpart: walk detection is native by necessity), so unlike the GPS
 * constants there is no hand-kept JS<->Kotlin parity to maintain.
 */
data class ParkSpotParams(
    /** Lower edge of walking pace; below it the phone is standing still. */
    val walkMinSpeed: Double = 0.5,
    /**
     * Upper edge of walking pace (~9 km/h). Deliberately below the old 3.0:
     * a brisk walk is ~2 m/s, while a car crawling in a jam spends its time
     * above this.
     */
    val walkMaxSpeed: Double = 2.5,
    /** At or above this the phone is unambiguously in a vehicle. */
    val vehicleSpeed: Double = 6.0,
    /** Walking time / distance needed when the spot is the disconnect fix. */
    val walkMs: Long = 8_000L,
    val walkDistM: Double = 30.0,
    /**
     * Stricter evidence once the car has been moving since the disconnect:
     * the spot is a guess (where it last moved), and the failure to avoid is
     * "saved a parking in the middle of a traffic jam".
     */
    val reWalkMs: Long = 25_000L,
    val reWalkDistM: Double = 40.0,
    /**
     * Upper edge of walking pace once re-anchored (~8 km/h, a brisk walk).
     * Tighter than [walkMaxSpeed] on purpose: a car in a jam crawls at 2-3 m/s,
     * and there the cost of a wrong answer is a parking saved mid-road.
     */
    val reWalkMaxSpeed: Double = 2.2,
    /** How long without a vehicle-speed sample the watch stays open. */
    val windowMs: Long = 600_000L,
    val sampleCapMs: Long = 15_000L,
)

/**
 * Pure decision logic for "did they park, and where?" after a Bluetooth
 * disconnect — an explicit (state, inputs) -> (state, decision) reducer with
 * the current time passed in, like GpsDecisionEngine, so tests are
 * deterministic and need no Android framework.
 *
 * It replaces WalkAwayEngine's role in the service. That engine could only
 * answer "give up" when the car turned out to still be moving, which threw the
 * parking away entirely in exactly the cases that matter: the link dropped on
 * the road (a fault, Bluetooth switched off, a tunnel) and the car then really
 * parked a few minutes later. Here "still moving" only voids the *spot*, and
 * the search continues for the point where the vehicle-speed run ended.
 *
 * Two modes, one reducer:
 *  - **At the disconnect spot** (no vehicle speed seen yet): the spot is the
 *    caller-supplied anchor. Walking away from it is confirmed by [ParkSpotParams.walkMs]
 *    at walking pace and [ParkSpotParams.walkDistM] of displacement.
 *  - **Re-anchored** (vehicle speed seen): the spot is the last fix that was
 *    moving faster than walking pace, so it follows the car all the way to
 *    where it stops. Any faster sample resets the walking accumulator, and the
 *    bar is higher ([ParkSpotParams.reWalkMs]/[ParkSpotParams.reWalkDistM]).
 *
 * Stationary samples never credit walking time (the next walking sample starts
 * from zero), so standing at a red light cannot pre-load the accumulator.
 */
object ParkSpotTracker {
    fun check(
        state: ParkSpotState,
        lat: Double,
        lng: Double,
        speed: Double?,
        anchorLat: Double?,
        anchorLng: Double?,
        disconnectedAt: Long,
        now: Long,
        p: ParkSpotParams = ParkSpotParams(),
    ): Pair<ParkSpotState, ParkSpotDecision?> {
        if (state.decided) return state to null

        // Rolling expiry: measured from the last time the phone moved like a
        // vehicle, so a long drive after a mid-trip drop does not time out.
        // Checked before the unknown-speed return — it depends on elapsed time,
        // not on whether this fix happened to carry a usable speed.
        val lastEvent = state.lastVehicleAt ?: disconnectedAt
        if (now - lastEvent >= p.windowMs) return state to ParkSpotDecision.GiveUp

        if (speed == null || speed.isNaN()) return state to null

        if (speed >= p.vehicleSpeed) {
            val first = !state.sawVehicle
            val next = state.copy(
                sawVehicle = true,
                spotLat = lat, spotLng = lng, spotAt = now,
                lastVehicleAt = now,
                walkAccumMs = 0L,
                lastSampleAt = null,
            )
            return next to (if (first) ParkSpotDecision.Retract else null)
        }

        val reanchored = state.sawVehicle
        val maxWalk = if (reanchored) p.reWalkMaxSpeed else p.walkMaxSpeed

        if (speed > maxWalk) {
            // Faster than walking, slower than clearly a vehicle: a jog, a bike
            // — or a car pulling in. Once the car has been moving this is part
            // of the same run (the spot follows it); before that it is not
            // evidence either way.
            return if (reanchored) {
                state.copy(
                    spotLat = lat, spotLng = lng, spotAt = now,
                    lastVehicleAt = now, walkAccumMs = 0L, lastSampleAt = null,
                ) to null
            } else {
                state.copy(lastSampleAt = null) to null
            }
        }

        if (speed < p.walkMinSpeed) {
            // Standing still. Clearing lastSampleAt means the pause is not
            // credited to the walking accumulator by the next walking sample.
            return state.copy(lastSampleAt = null) to null
        }

        val delta = state.lastSampleAt?.let { (now - it).coerceIn(0L, p.sampleCapMs) } ?: 0L
        val accum = state.walkAccumMs + delta
        val advanced = state.copy(walkAccumMs = accum, lastSampleAt = now)

        val spotLat = if (reanchored) state.spotLat else anchorLat
        val spotLng = if (reanchored) state.spotLng else anchorLng
        // No spot to measure from (no fix was captured at the disconnect):
        // nothing can be confirmed, though retraction above still works.
        if (spotLat == null || spotLng == null) return advanced to null

        val needMs = if (reanchored) p.reWalkMs else p.walkMs
        val needDist = if (reanchored) p.reWalkDistM else p.walkDistM
        val dist = GpsMath.distanceMeters(lat, lng, spotLat, spotLng)
        return if (accum >= needMs && dist >= needDist) {
            advanced.copy(decided = true) to ParkSpotDecision.Parked(
                lat = spotLat, lng = spotLng,
                at = if (reanchored) (state.spotAt ?: now) else disconnectedAt,
                reanchored = reanchored,
            )
        } else {
            advanced to null
        }
    }
}
