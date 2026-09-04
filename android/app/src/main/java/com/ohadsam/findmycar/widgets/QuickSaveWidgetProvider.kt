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

/**
 * Single-tap "שמירה מהירה" widget. Launches MainActivity with the same
 * ?action=save deep-link the PWA's own home-screen shortcut already uses
 * (see js/app.js #init(), which reads location.search on load) — no new
 * save-parking code path, just a native launcher for the existing one.
 */
class QuickSaveWidgetProvider : AppWidgetProvider() {
    companion object {
        const val EXTRA_ACTION = "widget_action"
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_save)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_ACTION, "save")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            val pendingIntent = PendingIntent.getActivity(context, id, launchIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_quick_save_root, pendingIntent)
            mgr.updateAppWidget(id, views)
        }
    }
}
