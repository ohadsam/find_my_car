package com.ohadsam.findmycar.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Repaints every placed widget's status dots on a timer, so the colors reflect
 * what is true *now* rather than whatever was true at the last parking change.
 *
 * Why an alarm at all: `updatePeriodMillis` in a widget's `-info.xml` is the
 * normal way to self-refresh, but Android silently floors it at 30 minutes —
 * far too coarse for "is the background service alive right now."
 *
 * **Honest limitation**: the requested cadence is 2 minutes and that is what is
 * scheduled, but `setExactAndAllowWhileIdle` is granted roughly once per 9-15
 * minutes per app while the device is in Doze (screen off, stationary). So on a
 * phone in someone's pocket the real cadence degrades to that, and the dots
 * show state from up to ~15 minutes ago. This is fine by construction — the
 * dots describe a condition that changes on the scale of app launches and
 * reboots, not seconds — and it is why nothing here claims a freshness it
 * cannot deliver. The same Doze reality already governs the service heartbeat
 * (see CLAUDE.md "the native heartbeat needs AlarmManager, not a Handler").
 *
 * The alarm only exists while at least one widget is actually placed: waking
 * the CPU every couple of minutes for a widget nobody has on their home screen
 * would be pure battery cost. [scheduleOrCancel] is called from every provider's
 * `onUpdate`/`onEnabled`/`onDisabled` and re-counts placed widgets each time, so
 * removing the last widget of any type stops it and adding one back starts it —
 * without needing the providers to agree on who owns the alarm.
 */
object WidgetStatusRefresher {
    private const val TAG = "FMC-WidgetStatus"
    const val ACTION_REFRESH = "com.ohadsam.findmycar.ACTION_WIDGET_STATUS_REFRESH"
    private const val REQUEST_CODE = 910000
    private const val INTERVAL_MS = 2 * 60 * 1000L

    private val PROVIDERS = listOf(
        ActiveParkingWidgetProvider::class.java,
        QuickSaveWidgetProvider::class.java,
        MiniMapWidgetProvider::class.java,
    )

    /** Total widgets placed across all three types. */
    private fun placedCount(context: Context): Int {
        val mgr = AppWidgetManager.getInstance(context) ?: return 0
        return PROVIDERS.sumOf { cls ->
            try {
                mgr.getAppWidgetIds(ComponentName(context, cls))?.size ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    fun scheduleOrCancel(context: Context) {
        try {
            if (placedCount(context) == 0) cancel(context) else schedule(context)
        } catch (e: Exception) {
            // A status indicator must never be able to break the widget it
            // decorates — worst case the dots just stop refreshing on a timer
            // and still update on every real parking change.
            Log.w(TAG, "scheduleOrCancel failed (non-fatal)", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetStatusRefreshReceiver::class.java).setAction(ACTION_REFRESH)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pendingIntent(context))
        } else {
            am.set(AlarmManager.RTC, at, pendingIntent(context))
        }
    }

    private fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    /** Repaints every placed widget of every type. */
    fun refreshAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        for (cls in PROVIDERS) {
            val ids = try {
                mgr.getAppWidgetIds(ComponentName(context, cls)) ?: continue
            } catch (e: Exception) {
                continue
            }
            for (id in ids) {
                try {
                    when (cls) {
                        ActiveParkingWidgetProvider::class.java -> ActiveParkingWidgetProvider.updateOne(context, mgr, id)
                        QuickSaveWidgetProvider::class.java     -> QuickSaveWidgetProvider.updateOne(context, mgr, id)
                        MiniMapWidgetProvider::class.java       -> MiniMapWidgetProvider.updateOne(context, mgr, id)
                    }
                } catch (e: Exception) {
                    // One widget failing to render must not stop the rest.
                    Log.w(TAG, "refresh of widget $id failed (non-fatal)", e)
                }
            }
        }
    }
}

/**
 * Fires on [WidgetStatusRefresher.ACTION_REFRESH], repaints the dots, and
 * re-arms the next tick. Re-arming here (rather than using a repeating alarm)
 * mirrors the service heartbeat's shape and means a single missed/denied alarm
 * can't permanently end the cadence — the next real widget update reschedules
 * it via `scheduleOrCancel`.
 */
class WidgetStatusRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetStatusRefresher.ACTION_REFRESH) return
        WidgetStatusRefresher.refreshAll(context)
        WidgetStatusRefresher.scheduleOrCancel(context)
    }
}
