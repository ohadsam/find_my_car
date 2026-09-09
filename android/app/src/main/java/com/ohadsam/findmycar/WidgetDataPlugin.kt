package com.ohadsam.findmycar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.ohadsam.findmycar.core.GpsDecision
import com.ohadsam.findmycar.core.NativeLogEntryJson
import com.ohadsam.findmycar.core.PendingGpsSuggestionJson
import com.ohadsam.findmycar.core.PendingWidgetActionJson
import com.ohadsam.findmycar.widgets.ActiveParkingWidgetProvider
import com.ohadsam.findmycar.widgets.MiniMapWidgetProvider

/**
 * Bridge for js/widget-bridge.js: mirrors the active-parking snapshot into
 * SharedPreferences (widgets run as RemoteViews in a separate process and
 * can't read the WebView's localStorage) and triggers an AppWidgetManager
 * refresh. Also keeps ParkingForegroundService alive while a parking
 * session is active, so GPS-speed auto-end keeps working in the background.
 *
 * Also implements GpsShadowEventBus.Listener: ParkingForegroundService's
 * location watch (Stage 4 of the native background-detection migration —
 * shadow mode only) isn't itself a Capacitor Plugin and has no
 * notifyListeners() of its own, so it emits through that in-process bus and
 * this plugin relays it to JS — mirrors how BluetoothClassicPlugin relays
 * BtEventBus.
 */
@CapacitorPlugin(name = "WidgetData")
class WidgetDataPlugin : Plugin(), GpsShadowEventBus.Listener {

    companion object {
        const val PREFS            = "findmycar_widget_data"
        const val KEY_HAS_PARKING  = "has_parking"
        const val KEY_ADDRESS      = "address"
        const val KEY_LAT          = "lat"
        const val KEY_LNG          = "lng"
        const val KEY_TIMESTAMP    = "timestamp"
        const val KEY_VEHICLE_ICON = "vehicle_icon"
        const val KEY_VEHICLE_NAME = "vehicle_name"
        const val KEY_VEHICLES_JSON     = "vehicles_json"
        const val KEY_ACTIVE_VEHICLE_ID = "active_vehicle_id"
        const val KEY_GPS_AUTO_END_ENABLED = "gps_auto_end_enabled"
        private const val TAG = "FMC-WidgetData"
        private const val PARKING_NOTIF_CHANNEL_ID = "findmycar_parking_active"
        private const val PARKING_NOTIF_ID = 4202
    }

    override fun load() {
        super.load()
        GpsShadowEventBus.addListener(this)
    }

    override fun handleOnDestroy() {
        GpsShadowEventBus.removeListener(this)
        super.handleOnDestroy()
    }

    override fun onGpsShadowDecision(trigger: String, decision: GpsDecision) {
        val data = JSObject()
        data.put("trigger", trigger)
        data.put("decision", "suggestEnd") // the only GpsDecision variant today
        notifyListeners("gpsShadowDecision", data)
    }

    // Mirrors the full vehicle list (BT-relevant fields + hasParking) + active
    // vehicle id + the global GPS auto-end setting, so both BtDecisionEngine
    // (Stage 2) and GpsDecisionEngine (Stage 4) — and the widgets'
    // quick-actions popup — can read real settings without needing the
    // WebView's own localStorage, which a plain native Activity/Service
    // can't access directly.
    @PluginMethod
    fun syncVehicles(call: PluginCall) {
        val vehiclesArray = call.getArray("vehicles")
        val activeId = call.getString("activeVehicleId", "") ?: ""
        val gpsAutoEndEnabled = call.getBoolean("gpsAutoEndEnabled", false) ?: false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_VEHICLES_JSON, vehiclesArray?.toString() ?: "[]")
            .putString(KEY_ACTIVE_VEHICLE_ID, activeId)
            .putBoolean(KEY_GPS_AUTO_END_ENABLED, gpsAutoEndEnabled)
            .apply()
        call.resolve()
    }

    @PluginMethod
    fun update(call: PluginCall) {
        val address = call.getString("address", "") ?: ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_HAS_PARKING, true)
            .putString(KEY_ADDRESS, address)
            .putFloat(KEY_LAT, (call.getDouble("lat") ?: 0.0).toFloat())
            .putFloat(KEY_LNG, (call.getDouble("lng") ?: 0.0).toFloat())
            .putString(KEY_TIMESTAMP, call.getString("timestamp", ""))
            .putString(KEY_VEHICLE_ICON, call.getString("vehicleIcon", "🚗"))
            .putString(KEY_VEHICLE_NAME, call.getString("vehicleName", ""))
            .apply()
        ParkingForegroundService.setReasonActive(context, "parking", true)
        showParkingNotification(address)
        refreshWidgets()
        call.resolve()
    }

    @PluginMethod
    fun clear(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_PARKING, false).apply()
        ParkingForegroundService.setReasonActive(context, "parking", false)
        cancelParkingNotification()
        refreshWidgets()
        call.resolve()
    }

    // Stage 9 of the native background-detection migration (see CLAUDE.md):
    // the persistent "active parking" notification (address + icon) used to
    // be JS/Service-Worker-only (#showParkingNotification in js/app.js) —
    // reliable only while the WebView is alive, so it could go stale (still
    // showing an old address, or not disappearing) exactly when the WebView
    // is reclaimed, the scenario this whole migration exists to fix. Posted
    // here instead, from the SAME choke point (WidgetDataPlugin.update/clear)
    // every real parking-state change already goes through, whether
    // triggered live or replayed on resume (Stages 6/7). js/app.js's own
    // #showParkingNotification/#cancelParkingNotification now skip
    // themselves on native to avoid posting a duplicate — this is native's
    // equivalent, not an addition alongside it. Distinct from
    // ParkingForegroundService's own foreground-service notification (a
    // required, generic "active in background" notice serving a different
    // technical purpose — keeping the process alive — not parking-specific).
    private fun showParkingNotification(address: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(PARKING_NOTIF_CHANNEL_ID, "חניה פעילה", NotificationManager.IMPORTANCE_LOW)
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, PARKING_NOTIF_CHANNEL_ID)
                .setContentTitle("FindMyCar — חניה פעילה 🅿️")
                .setContentText(address)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setColor(0xFF5B8BF5.toInt())
                .setSilent(true)
                .build()
            NotificationManagerCompat.from(context).notify(PARKING_NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "showParkingNotification failed (non-fatal)", e)
        }
    }

    private fun cancelParkingNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(PARKING_NOTIF_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancelParkingNotification failed (non-fatal)", e)
        }
    }

    // Stage 7 of the native background-detection migration (see CLAUDE.md):
    // lets JS read/clear the GPS end-suggestion ParkingForegroundService
    // recorded while the WebView was unreachable, so it can replay it as
    // the same gpsEndModal confirmation on next resume — never auto-ends a
    // parking itself. Returns the raw JSON (via the same tested
    // PendingGpsSuggestionJson used to write it) rather than rebuilding a
    // JSObject by hand here.
    @PluginMethod
    fun getPendingGpsSuggestion(call: PluginCall) {
        val json = PendingGpsSuggestionJson.toJson(PendingGpsSuggestionStore.get(context))
        val ret = JSObject(); ret.put("pendingJson", json); call.resolve(ret)
    }

    @PluginMethod
    fun clearPendingGpsSuggestion(call: PluginCall) {
        PendingGpsSuggestionStore.clear(context)
        call.resolve()
    }

    // Stage 8 of the native background-detection migration (see CLAUDE.md):
    // lets JS read/clear widget quick-actions WidgetActionReceiver recorded
    // while the WebView was unreachable, so it can replay them through the
    // real performWidgetAction() the next time it resumes. Mirrors
    // getPendingActions/clearPendingActions (BluetoothClassic, Stage 5).
    @PluginMethod
    fun getPendingWidgetActions(call: PluginCall) {
        val json = PendingWidgetActionJson.toJson(PendingWidgetActionStore.getAll(context))
        val ret = JSObject(); ret.put("actionsJson", json); call.resolve(ret)
    }

    @PluginMethod
    fun clearPendingWidgetActions(call: PluginCall) {
        PendingWidgetActionStore.clear(context)
        call.resolve()
    }

    // Lets JS read/clear the native-only lifecycle log NativeLogStore
    // accumulates (foreground service start/stop, GPS watch start/stop, raw
    // BT ACL broadcast receipt) — merged into the in-app diagnostic log
    // under the SERVICE category on next resume, so "was the background
    // service actually alive, and when" is provable without adb. Unlike the
    // other getPending*/clearPending* pairs, these aren't actionable
    // decisions to replay — purely informational, read-only from JS's
    // perspective beyond clearing them once merged.
    @PluginMethod
    fun getNativeLog(call: PluginCall) {
        val json = NativeLogEntryJson.toJson(NativeLogStore.getAll(context))
        val ret = JSObject(); ret.put("entriesJson", json); call.resolve(ret)
    }

    @PluginMethod
    fun clearNativeLog(call: PluginCall) {
        NativeLogStore.clear(context)
        call.resolve()
    }

    private fun refreshWidgets() {
        val mgr = AppWidgetManager.getInstance(context)
        for (cls in listOf(ActiveParkingWidgetProvider::class.java, MiniMapWidgetProvider::class.java)) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) continue
            val intent = Intent(context, cls).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
