package com.ohadsam.findmycar

import android.content.Context
import com.ohadsam.findmycar.core.PendingGpsSuggestion
import com.ohadsam.findmycar.core.PendingGpsSuggestionJson

/**
 * SharedPreferences-backed persistence for a single (at most one
 * outstanding) PendingGpsSuggestion — Stage 7's GPS counterpart to
 * PendingBtActionStore. Not unit-tested directly (needs a live Context) —
 * PendingGpsSuggestionJson (the serialization it delegates to) is the
 * tested layer, same split as PendingBtActionStore/PendingBtActionJson.
 */
object PendingGpsSuggestionStore {
    private const val PREFS = "findmycar_pending_gps_suggestion"
    private const val KEY_JSON = "pending_json"

    @Synchronized
    fun set(context: Context, entry: PendingGpsSuggestion) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, PendingGpsSuggestionJson.toJson(entry)).apply()
    }

    @Synchronized
    fun get(context: Context): PendingGpsSuggestion? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, "null") ?: "null"
        return PendingGpsSuggestionJson.parse(json)
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, "null").apply()
    }
}
