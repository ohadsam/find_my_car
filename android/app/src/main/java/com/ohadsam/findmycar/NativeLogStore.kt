package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.NativeLogEntry
import com.ohadsam.findmycar.core.NativeLogEntryJson

/**
 * SharedPreferences-backed log of native-only lifecycle events — foreground
 * service start/stop, GPS shadow watch start/stop, raw Bluetooth ACL
 * broadcast receipt — that never generate a Capacitor plugin event of their
 * own, unlike BT/GPS-SHADOW and BT/GPS-PENDING, which already reach JS
 * because they represent an actual decision. The existing Logcat tags
 * (FMC-FgService/FMC-BtPlugin) stay exactly as they are for adb-based
 * debugging — `add()` here is purely additive, called alongside (never
 * instead of) the existing Log.i/Log.w/Log.e call. JS reads/clears this via
 * WidgetDataPlugin.getNativeLog()/.clearNativeLog() and merges it into the
 * in-app diagnostic log under the `SERVICE` category on the next resume, so
 * "was the background service actually alive, and when" is provable from
 * inside the app instead of only via a connected device. Bounded to the
 * most recent MAX_ENTRIES so a chatty period (e.g. a long drive with lots
 * of GPS watch churn) can't grow this unboundedly — these are lifecycle
 * transitions, not per-location-update noise, so this cap is generous
 * relative to how often they actually fire.
 */
object NativeLogStore {
    private const val PREFS = WidgetDataPlugin.PREFS
    private const val KEY_JSON = "native_log_json"
    private const val MAX_ENTRIES = 200

    @Synchronized
    fun add(context: Context, tag: String, message: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = NativeLogEntryJson.parse(prefs.getString(KEY_JSON, "[]") ?: "[]")
            val updated = (current + NativeLogEntry(System.currentTimeMillis(), tag, message))
                .takeLast(MAX_ENTRIES)
            prefs.edit().putString(KEY_JSON, NativeLogEntryJson.toJson(updated)).apply()
        } catch (e: Exception) {
            // Logging itself must never crash the caller.
            Log.w("FMC-NativeLog", "add() failed (non-fatal)", e)
        }
    }

    @Synchronized
    fun getAll(context: Context): List<NativeLogEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NativeLogEntryJson.parse(prefs.getString(KEY_JSON, "[]") ?: "[]")
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, "[]").apply()
    }
}
