package com.ohadsam.findmycar

import android.content.Context
import com.ohadsam.findmycar.core.DisconnectSpot
import com.ohadsam.findmycar.core.DisconnectSpotJson

/**
 * SharedPreferences-backed list of the last known disconnect/stop spot per
 * vehicle (see [DisconnectSpot]). Read by JS through WidgetDataPlugin so the
 * app can offer "save the parking where the car was" after an ignored
 * notification.
 *
 * An entry is removed when: that vehicle gets a parking (NativeParkingCommitter
 * or JS), the vehicle's Bluetooth reconnects (they are back in the car), a new
 * disconnect for the same vehicle replaces it, the car is seen moving at
 * vehicle speed (the spot is void), or it is older than [MAX_AGE_MS].
 *
 * Not unit-tested directly (needs a live Context) — DisconnectSpotJson is the
 * tested layer, same split as PendingParkingSuggestionStore.
 */
object DisconnectSpotStore {
    private const val PREFS = "findmycar_disconnect_spots"
    private const val KEY_JSON = "spots_json"

    /** Mirrors CFG.disconnectSpotMaxAgeMs in js/config.js. */
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(context: Context): List<DisconnectSpot> {
        val now = System.currentTimeMillis()
        val all = DisconnectSpotJson.parseList(prefs(context).getString(KEY_JSON, "[]") ?: "[]")
        val fresh = all.filter { now - it.at <= MAX_AGE_MS }
        if (fresh.size != all.size) save(context, fresh)
        return fresh
    }

    @Synchronized
    fun upsert(context: Context, spot: DisconnectSpot) {
        save(context, getAll(context).filter { it.vehicleId != spot.vehicleId } + spot)
    }

    @Synchronized
    fun remove(context: Context, vehicleId: String) {
        val all = getAll(context)
        if (all.any { it.vehicleId == vehicleId }) save(context, all.filter { it.vehicleId != vehicleId })
    }

    @Synchronized
    fun removeByLabel(context: Context, label: String) {
        val all = getAll(context)
        if (all.any { it.label == label }) save(context, all.filter { it.label != label })
    }

    private fun save(context: Context, spots: List<DisconnectSpot>) {
        prefs(context).edit().putString(KEY_JSON, DisconnectSpotJson.toJson(spots)).apply()
    }
}
