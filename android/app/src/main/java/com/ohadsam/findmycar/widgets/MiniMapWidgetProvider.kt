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
 * "מפה מוקטנת" widget — static OSM tile snapshot centered on the parking
 * pin. Shows a second simultaneously-parked vehicle's map too once the
 * widget has been resized tall enough (see LARGE_MIN_HEIGHT_DP), stacked
 * below the first; otherwise, if 2+ vehicles are parked at once, a small
 * cycle button lets the user switch which single vehicle this instance
 * shows (WidgetCycleVehicleReceiver).
 */
class MiniMapWidgetProvider : AppWidgetProvider() {
    companion object {
        // Each map slot wants real vertical room to be legible; two stacked
        // slots need roughly double the default 180dp single-map size.
        private const val LARGE_MIN_HEIGHT_DP = 280

        fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_mini_map)
            val parked = ParkedVehicles.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            val isLarge = (mgr.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0) >=
                LARGE_MIN_HEIGHT_DP

            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

            val launchIntent = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_mini_map_root,
                PendingIntent.getActivity(context, id, launchIntent, piFlags)
            )

            val actionsIntent = Intent(context, WidgetQuickActionsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widget_quick_actions_btn,
                PendingIntent.getActivity(context, id + 300000, actionsIntent, piFlags)
            )

            if (parked.isEmpty()) {
                views.setViewVisibility(R.id.widget_mini_map_slot2, View.GONE)
                views.setViewVisibility(R.id.widget_mini_map_image, View.GONE)
                views.setViewVisibility(R.id.widget_mini_map_caption_bg, View.GONE)
                views.setViewVisibility(R.id.widget_mini_map_cycle_btn, View.GONE)
                views.setViewVisibility(R.id.widget_mini_map_empty, View.VISIBLE)
                mgr.updateAppWidget(id, views)
                return
            }
            views.setViewVisibility(R.id.widget_mini_map_empty, View.GONE)

            val showSecond = parked.size >= 2 && isLarge
            views.setViewVisibility(R.id.widget_mini_map_slot2, if (showSecond) View.VISIBLE else View.GONE)

            val firstIndex = if (parked.size == 1 || isLarge) 0 else {
                val key = WidgetCycleVehicleReceiver.selectedIndexKey(WidgetCycleVehicleReceiver.WIDGET_TYPE_MINIMAP, id)
                prefs.getInt(key, 0).mod(parked.size)
            }
            if (parked.size >= 2 && !isLarge) {
                views.setViewVisibility(R.id.widget_mini_map_cycle_btn, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_mini_map_cycle_btn, cyclePendingIntent(context, id))
            } else {
                views.setViewVisibility(R.id.widget_mini_map_cycle_btn, View.GONE)
            }

            renderSlot(views, mgr, id, parked[firstIndex], R.id.widget_mini_map_image, R.id.widget_mini_map_caption_bg, R.id.widget_mini_map_caption)

            views.setViewVisibility(R.id.widget_mini_map_image, View.VISIBLE)
            views.setViewVisibility(R.id.widget_mini_map_caption_bg, View.VISIBLE)
            mgr.updateAppWidget(id, views) // show layout immediately; tile images fill in async

            if (showSecond) {
                renderSlot(views, mgr, id, parked[1], R.id.widget_mini_map_image2, R.id.widget_mini_map_caption_bg2, R.id.widget_mini_map_caption2)
                views.setViewVisibility(R.id.widget_mini_map_image2, View.VISIBLE)
                views.setViewVisibility(R.id.widget_mini_map_caption_bg2, View.VISIBLE)
                mgr.updateAppWidget(id, views)
            }
        }

        private fun renderSlot(
            views: RemoteViews, mgr: AppWidgetManager, id: Int, vehicle: ParkedVehicle,
            imageViewId: Int, captionBgId: Int, captionId: Int,
        ) {
            views.setTextViewText(captionId, vehicle.address.ifBlank { "מיקום נשמר" })
            MapTileFetcher.fetchAsync(vehicle.lat, vehicle.lng) { bitmap ->
                if (bitmap != null) {
                    views.setImageViewBitmap(imageViewId, bitmap)
                    mgr.updateAppWidget(id, views)
                }
            }
        }

        private fun cyclePendingIntent(context: Context, id: Int): PendingIntent {
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            val intent = Intent(context, WidgetCycleVehicleReceiver::class.java).apply {
                putExtra(WidgetCycleVehicleReceiver.EXTRA_WIDGET_TYPE, WidgetCycleVehicleReceiver.WIDGET_TYPE_MINIMAP)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            return PendingIntent.getBroadcast(context, id + 500000, intent, piFlags)
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
