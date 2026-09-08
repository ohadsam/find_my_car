package com.ohadsam.findmycar.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.ohadsam.findmycar.MainActivity
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetDataPlugin

/**
 * "חניה פעילה" widget — current parking address + vehicle, tap opens the
 * app. Shows a second simultaneously-parked vehicle's row too once the
 * widget has been resized tall enough (see LARGE_MIN_HEIGHT_DP); otherwise,
 * if 2+ vehicles are parked at once, a small cycle button lets the user
 * switch which single vehicle this instance shows (WidgetCycleVehicleReceiver).
 */
class ActiveParkingWidgetProvider : AppWidgetProvider() {
    companion object {
        // A single row is ~60dp (the widget's declared minHeight); two rows
        // need roughly double that plus padding — chosen to reliably miss
        // the default single-cell size while catching a genuine resize.
        private const val LARGE_MIN_HEIGHT_DP = 110

        fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_active_parking)
            val parked = ParkedVehicles.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            val isLarge = (mgr.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0) >=
                LARGE_MIN_HEIGHT_DP

            when {
                parked.isEmpty() -> {
                    views.setTextViewText(R.id.widget_title, "אין חניה פעילה")
                    views.setTextViewText(R.id.widget_subtitle, "")
                    views.setTextViewText(R.id.widget_icon_badge, "🅿️")
                    views.setViewVisibility(R.id.widget_row2, View.GONE)
                    views.setViewVisibility(R.id.widget_cycle_btn, View.GONE)
                }
                parked.size == 1 || !isLarge -> {
                    val index = if (parked.size == 1) 0 else {
                        val prefsKey = WidgetCycleVehicleReceiver.selectedIndexKey(WidgetCycleVehicleReceiver.WIDGET_TYPE_ACTIVE, id)
                        prefs.getInt(prefsKey, 0).mod(parked.size)
                    }
                    val v = parked[index]
                    views.setTextViewText(R.id.widget_title, "${v.name} חונה כאן".trim())
                    views.setTextViewText(R.id.widget_subtitle, v.address.ifBlank { "מיקום נשמר" })
                    views.setTextViewText(R.id.widget_icon_badge, v.icon)
                    views.setViewVisibility(R.id.widget_row2, View.GONE)
                    if (parked.size >= 2) {
                        views.setViewVisibility(R.id.widget_cycle_btn, View.VISIBLE)
                        views.setOnClickPendingIntent(R.id.widget_cycle_btn, cyclePendingIntent(context, id))
                    } else {
                        views.setViewVisibility(R.id.widget_cycle_btn, View.GONE)
                    }
                }
                else -> { // large enough, 2+ parked — show the first two
                    val v1 = parked[0]
                    val v2 = parked[1]
                    views.setTextViewText(R.id.widget_title, "${v1.name} חונה כאן".trim())
                    views.setTextViewText(R.id.widget_subtitle, v1.address.ifBlank { "מיקום נשמר" })
                    views.setTextViewText(R.id.widget_icon_badge, v1.icon)
                    views.setTextViewText(R.id.widget_title2, "${v2.name} חונה כאן".trim())
                    views.setTextViewText(R.id.widget_subtitle2, v2.address.ifBlank { "מיקום נשמר" })
                    views.setTextViewText(R.id.widget_icon_badge2, v2.icon)
                    views.setViewVisibility(R.id.widget_row2, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_cycle_btn, View.GONE)
                }
            }

            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

            val launchIntent = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, id, launchIntent, piFlags)
            )

            // Separate target (WidgetQuickActionsActivity, not MainActivity) on
            // the "⋮" button — a real widget can't intercept long-press (the
            // launcher reserves that for move/resize/remove), so this tap-to-open
            // popup is the closest equivalent to a widget context menu.
            val actionsIntent = Intent(context, WidgetQuickActionsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widget_quick_actions_btn,
                PendingIntent.getActivity(context, id + 200000, actionsIntent, piFlags)
            )

            mgr.updateAppWidget(id, views)
        }

        private fun cyclePendingIntent(context: Context, id: Int): PendingIntent {
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            val intent = Intent(context, WidgetCycleVehicleReceiver::class.java).apply {
                putExtra(WidgetCycleVehicleReceiver.EXTRA_WIDGET_TYPE, WidgetCycleVehicleReceiver.WIDGET_TYPE_ACTIVE)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            return PendingIntent.getBroadcast(context, id + 400000, intent, piFlags)
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, mgr, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) {
        updateOne(context, mgr, appWidgetId)
    }
}
