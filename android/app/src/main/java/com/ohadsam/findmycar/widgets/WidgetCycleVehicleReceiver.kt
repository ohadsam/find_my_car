package com.ohadsam.findmycar.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ohadsam.findmycar.WidgetDataPlugin

/**
 * Advances which vehicle a single small "חניה פעילה"/"מפה מוקטנת" widget
 * instance displays, when 2+ vehicles have simultaneously active parking
 * but the widget hasn't been resized large enough to show more than one
 * (see ActiveParkingWidgetProvider/MiniMapWidgetProvider). Pure widget-local
 * UI state — unlike WidgetActionReceiver, this never touches parking
 * business logic or WebView reachability, so it stays a separate, simpler
 * receiver rather than another branch bolted onto that one.
 */
class WidgetCycleVehicleReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_WIDGET_TYPE = "widgetType"
        const val WIDGET_TYPE_ACTIVE = "active"
        const val WIDGET_TYPE_MINIMAP = "minimap"

        fun selectedIndexKey(widgetType: String, appWidgetId: Int) = "cycle_${widgetType}_$appWidgetId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val widgetType = intent.getStringExtra(EXTRA_WIDGET_TYPE) ?: return
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (appWidgetId == -1) return

        val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]"
        val count = ParkedVehicles.parse(json).size
        if (count == 0) return

        val key = selectedIndexKey(widgetType, appWidgetId)
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, (current + 1) % count).apply()

        val mgr = AppWidgetManager.getInstance(context)
        if (widgetType == WIDGET_TYPE_ACTIVE) {
            ActiveParkingWidgetProvider.updateOne(context, mgr, appWidgetId)
        } else {
            MiniMapWidgetProvider.updateOne(context, mgr, appWidgetId)
        }
    }
}
