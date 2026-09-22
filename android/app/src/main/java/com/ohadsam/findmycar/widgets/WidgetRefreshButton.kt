package com.ohadsam.findmycar.widgets

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetActionReceiver

/**
 * Wires the ↻ button that all three widgets carry.
 *
 * Shared rather than repeated in each provider for the same reason
 * BackgroundAlertNotifier and OemSettingsIntents are shared: three copies of a
 * PendingIntent recipe are three places to get the flags or the request code
 * wrong, and a wrong request code here is the silent kind of bug — Android
 * reuses one PendingIntent across widget instances and every button then acts
 * on whichever was created last.
 *
 * The 600000 offset keeps these clear of the request codes already in use:
 * `id` (widget body), `id + 200000`/`+ 300000` (the ⋮ popup) and
 * `id + 400000`/`+ 500000` (the 🔁 cycle button).
 */
object WidgetRefreshButton {
    private const val REQUEST_CODE_BASE = 600000

    fun bind(context: Context, views: RemoteViews, appWidgetId: Int) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            putExtra(QuickSaveWidgetProvider.EXTRA_ACTION, WidgetActionReceiver.ACTION_REFRESH)
        }
        views.setOnClickPendingIntent(
            R.id.widget_refresh_btn,
            PendingIntent.getBroadcast(context, appWidgetId + REQUEST_CODE_BASE, intent, piFlags),
        )
    }
}
