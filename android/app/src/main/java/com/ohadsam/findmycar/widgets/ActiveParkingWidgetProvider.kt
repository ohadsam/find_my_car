package com.ohadsam.findmycar.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.ohadsam.findmycar.MainActivity
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetDataPlugin

/** "חניה פעילה" widget — current parking address + vehicle, tap opens the app. */
class ActiveParkingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, mgr, id)
    }

    private fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        val views = RemoteViews(context.packageName, R.layout.widget_active_parking)

        if (!prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false)) {
            views.setTextViewText(R.id.widget_title, "אין חניה פעילה")
            views.setTextViewText(R.id.widget_subtitle, "")
            views.setTextViewText(R.id.widget_icon_badge, "🅿️")
        } else {
            val icon    = prefs.getString(WidgetDataPlugin.KEY_VEHICLE_ICON, "🚗")
            val name    = prefs.getString(WidgetDataPlugin.KEY_VEHICLE_NAME, "")
            val address = prefs.getString(WidgetDataPlugin.KEY_ADDRESS, "")
            views.setTextViewText(R.id.widget_title, if (name.isNullOrBlank()) "חונה כאן" else "$name חונה כאן")
            views.setTextViewText(R.id.widget_subtitle, if (address.isNullOrBlank()) "מיקום נשמר" else address)
            views.setTextViewText(R.id.widget_icon_badge, icon)
        }

        val launchIntent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        mgr.updateAppWidget(id, views)
    }
}
