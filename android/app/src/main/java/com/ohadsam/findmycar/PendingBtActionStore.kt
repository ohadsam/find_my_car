package com.ohadsam.findmycar

import android.content.Context
import com.ohadsam.findmycar.core.PendingBtAction
import com.ohadsam.findmycar.core.PendingBtActionJson

/**
 * SharedPreferences-backed persistence for PendingBtAction entries — the
 * "ParkingStateStore" CLAUDE.md's migration plan describes for Stage 5:
 * deliberately minimal (no full parking record), just enough for JS to
 * reconcile on next resume. Not unit-tested directly (needs a live
 * Context) — PendingBtActionJson (the serialization it delegates to) is
 * the tested layer, same split as WidgetDataPlugin/VehicleJsonParser.
 */
object PendingBtActionStore {
    private const val PREFS = "findmycar_pending_bt_actions"
    private const val KEY_JSON = "pending_json"

    @Synchronized
    fun add(context: Context, entry: PendingBtAction) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = PendingBtActionJson.parse(prefs.getString(KEY_JSON, "[]") ?: "[]")
        val updated = current + entry
        prefs.edit().putString(KEY_JSON, PendingBtActionJson.toJson(updated)).apply()
    }

    @Synchronized
    fun getAll(context: Context): List<PendingBtAction> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PendingBtActionJson.parse(prefs.getString(KEY_JSON, "[]") ?: "[]")
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, "[]").apply()
    }
}
