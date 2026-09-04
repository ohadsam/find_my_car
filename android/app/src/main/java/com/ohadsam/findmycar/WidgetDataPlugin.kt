package com.ohadsam.findmycar

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.ohadsam.findmycar.widgets.ActiveParkingWidgetProvider
import com.ohadsam.findmycar.widgets.MiniMapWidgetProvider

/**
 * Bridge for js/widget-bridge.js: mirrors the active-parking snapshot into
 * SharedPreferences (widgets run as RemoteViews in a separate process and
 * can't read the WebView's localStorage) and triggers an AppWidgetManager
 * refresh. Also keeps ParkingForegroundService alive while a parking
 * session is active, so GPS-speed auto-end keeps working in the background.
 */
@CapacitorPlugin(name = "WidgetData")
class WidgetDataPlugin : Plugin() {

    companion object {
        const val PREFS            = "findmycar_widget_data"
        const val KEY_HAS_PARKING  = "has_parking"
        const val KEY_ADDRESS      = "address"
        const val KEY_LAT          = "lat"
        const val KEY_LNG          = "lng"
        const val KEY_TIMESTAMP    = "timestamp"
        const val KEY_VEHICLE_ICON = "vehicle_icon"
        const val KEY_VEHICLE_NAME = "vehicle_name"
    }

    @PluginMethod
    fun update(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_HAS_PARKING, true)
            .putString(KEY_ADDRESS, call.getString("address", ""))
            .putFloat(KEY_LAT, (call.getDouble("lat") ?: 0.0).toFloat())
            .putFloat(KEY_LNG, (call.getDouble("lng") ?: 0.0).toFloat())
            .putString(KEY_TIMESTAMP, call.getString("timestamp", ""))
            .putString(KEY_VEHICLE_ICON, call.getString("vehicleIcon", "🚗"))
            .putString(KEY_VEHICLE_NAME, call.getString("vehicleName", ""))
            .apply()
        ParkingForegroundService.setReasonActive(context, "parking", true)
        refreshWidgets()
        call.resolve()
    }

    @PluginMethod
    fun clear(call: PluginCall) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_PARKING, false).apply()
        ParkingForegroundService.setReasonActive(context, "parking", false)
        refreshWidgets()
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
