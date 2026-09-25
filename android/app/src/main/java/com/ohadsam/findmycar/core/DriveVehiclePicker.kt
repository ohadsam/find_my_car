package com.ohadsam.findmycar.core

/** One parked vehicle, as far as drive-away detection is concerned. */
data class ParkedSpot(
    val vehicleId: String,
    /** Identifies this particular parking, so a new parking of the same vehicle is a new spot. */
    val parkingKey: String,
    val lat: Double,
    val lng: Double,
)

/** The last time a location fix put the phone at this parking. */
data class NearSeen(val at: Long, val distanceM: Double)

/**
 * Decides which parked vehicle a detected drive belongs to (v1.51.0).
 *
 * Drive-away detection used to watch only the active vehicle's parking. With
 * several vehicles parked, the phone's own movement cannot say which car it is
 * in — but where the phone *was* just before the drive can: the car you walked
 * up to. So every fix records which parkings it was near ([recordNear]), and
 * when a drive starts [pick] chooses the parking the phone was most recently
 * at.
 *
 * **Never a gate.** Whenever at least one vehicle is parked, [pick] returns
 * one — falling back to the parking nearest the phone when none was ever seen
 * nearby (no fix arrived while walking to the car, which is common indoors).
 * With a single parked vehicle that fallback is exactly the pre-v1.51.0
 * behaviour. A picker that could return nothing for a real drive would be the
 * "a gate that can permanently disarm detection" mistake of v1.41.0 again.
 *
 * Pure: no Android framework, no wall clock (time is a parameter).
 */
object DriveVehiclePicker {
    fun recordNear(
        seen: Map<String, NearSeen>,
        spots: List<ParkedSpot>,
        lat: Double,
        lng: Double,
        nearRadiusM: Double,
        now: Long,
    ): Map<String, NearSeen> {
        val live = spots.map { it.parkingKey }.toSet()
        val out = seen.filterKeys { it in live }.toMutableMap()
        for (s in spots) {
            val d = GpsMath.distanceMeters(lat, lng, s.lat, s.lng)
            if (d <= nearRadiusM) out[s.parkingKey] = NearSeen(now, d)
        }
        return out
    }

    fun pick(spots: List<ParkedSpot>, seen: Map<String, NearSeen>, lat: Double, lng: Double): ParkedSpot? {
        if (spots.isEmpty()) return null
        val seenSpots = spots.filter { seen.containsKey(it.parkingKey) }
        if (seenSpots.isNotEmpty()) {
            // Most recent first; two parkings seen on the same fix (a shared
            // car park) are told apart by which one that fix was closer to.
            return seenSpots.sortedWith(
                compareByDescending<ParkedSpot> { seen.getValue(it.parkingKey).at }
                    .thenBy { seen.getValue(it.parkingKey).distanceM }
            ).first()
        }
        return spots.minByOrNull { GpsMath.distanceMeters(lat, lng, it.lat, it.lng) }
    }
}
