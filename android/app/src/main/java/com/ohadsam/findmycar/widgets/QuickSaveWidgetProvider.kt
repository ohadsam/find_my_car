package com.ohadsam.findmycar.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetActionReceiver

/**
 * Single-tap "שמירה מהירה" widget. Broadcasts straight to
 * WidgetActionReceiver (headless — see that class) instead of launching
 * MainActivity, so tapping it saves a parking spot without ever opening the
 * app. The "⋮" button opens the same quick-actions popup as the other
 * widgets, for swap/end/vehicle-picker.
 */
class QuickSaveWidgetProvider : AppWidgetProvider() {
    companion object {
        const val EXTRA_ACTION = "widget_action"
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_save)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

            val saveIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                putExtra(EXTRA_ACTION, "save")
            }
            views.setOnClickPendingIntent(
                R.id.widget_quick_save_root,
                PendingIntent.getBroadcast(context, id, saveIntent, piFlags)
            )

            val actionsIntent = Intent(context, WidgetQuickActionsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widget_quick_actions_btn,
                PendingIntent.getActivity(context, id + 200000, actionsIntent, piFlags)
            )

            mgr.updateAppWidget(id, views)
        }
    }
}
