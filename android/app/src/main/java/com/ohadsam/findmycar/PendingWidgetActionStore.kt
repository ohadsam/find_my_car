package com.ohadsam.findmycar

import android.content.Context
import com.ohadsam.findmycar.core.PendingWidgetAction
import com.ohadsam.findmycar.core.PendingWidgetActionJson

/**
 * SharedPreferences-backed persistence for PendingWidgetAction entries —
 * Stage 8's widget-action counterpart to PendingBtActionStore. Not unit-
 * tested directly (needs a live Context) — PendingWidgetActionJson (the
 * serialization it delegates to) is the tested layer, same split as the
 * other Pending*Store classes.
 */
object PendingWidgetActionStore {
    private const val PREFS = "findmycar_pending_widget_actions"
    private const val KEY_JSON = "pending_json"

    @Synchronized
    fun add(context: Context, entry: PendingWidgetAction) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = PendingWidgetActionJson.parse(prefs.getString(KEY_JSON, "[]") ?: "[]")
        val updated = current + entry
        prefs.edit().putString(KEY_JSON, PendingWidgetActionJson.toJson(updated)).apply()
    }

    @Synchronized
    fun getAll(context: Context): List<PendingWidgetAction> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PendingWidgetActionJson.parse(prefs.getString(KEY_JSON, "[]") ?: "[]")
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, "[]").apply()
    }
}
