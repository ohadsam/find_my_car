package com.ohadsam.findmycar.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.ohadsam.findmycar.MainActivity
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetDataPlugin

/** "מפה מוקטנת" widget — static OSM tile snapshot centered on the parking pin. */
class MiniMapWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        val hasParking = prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false)

        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_mini_map)

            val launchIntent = Intent(context, MainActivity::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            views.setOnClickPendingIntent(
                R.id.widget_mini_map_root,
                PendingIntent.getActivity(context, 0, launchIntent, flags)
            )

            if (!hasParking) {
                views.setViewVisibility(R.id.widget_mini_map_image, View.GONE)
                views.setViewVisibility(R.id.widget_mini_map_empty, View.VISIBLE)
                mgr.updateAppWidget(id, views)
                continue
            }

            views.setViewVisibility(R.id.widget_mini_map_image, View.VISIBLE)
            views.setViewVisibility(R.id.widget_mini_map_empty, View.GONE)
            mgr.updateAppWidget(id, views) // show layout immediately; tile image fills in async

            val lat = prefs.getFloat(WidgetDataPlugin.KEY_LAT, 0f).toDouble()
            val lng = prefs.getFloat(WidgetDataPlugin.KEY_LNG, 0f).toDouble()
            MapTileFetcher.fetchAsync(lat, lng) { bitmap ->
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_mini_map_image, bitmap)
                    mgr.updateAppWidget(id, views)
                }
            }
        }
    }
}
