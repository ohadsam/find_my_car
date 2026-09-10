package com.ohadsam.findmycar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Schedules/cancels the once-daily "is anything parked, and where" status
 * notification (DailyStatusReceiver) — a separate concern from
 * ParkingForegroundService's own heartbeat: the heartbeat only runs while
 * that service is alive (a parking session active or Bluetooth enabled),
 * but this notification must fire once a day regardless of whether
 * anything is currently parked, so it can't live inside that service.
 *
 * Uses AlarmManager.setExactAndAllowWhileIdle (Doze-aware, no special
 * permission required — see ParkingForegroundService.startHeartbeat()'s own
 * doc comment for why a plain Handler/Timer isn't good enough here either)
 * anchored to a fixed local-clock target hour, self-rescheduling for "same
 * time tomorrow" each time it fires (see DailyStatusReceiver).
 */
object DailyStatusScheduler {
    private const val TAG = "FMC-DailyStatus"
    const val ACTION_DAILY_STATUS = "com.ohadsam.findmycar.ACTION_DAILY_STATUS"

    // Fixed local-clock hour the notification targets — not user-configurable
    // today (the user only asked for "once a day", not a specific time).
    // Documented in CLAUDE.md; change here if that default ever needs to move.
    const val TARGET_HOUR = 9
    const val TARGET_MINUTE = 0

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyStatusReceiver::class.java).setAction(ACTION_DAILY_STATUS)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    // Next occurrence of TARGET_HOUR:TARGET_MINUTE strictly after "now" —
    // today if that time hasn't passed yet, otherwise tomorrow. Deterministic
    // from wall-clock time alone, so calling this repeatedly (e.g. on every
    // syncVehicles() call) is idempotent and never drifts the target later.
    private fun nextTriggerAtMillis(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
            set(Calendar.MINUTE, TARGET_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    fun scheduleOrCancel(context: Context, enabled: Boolean) {
        if (enabled) scheduleNext(context) else cancel(context)
    }

    fun scheduleNext(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = nextTriggerAtMillis()
            val pi = pendingIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleNext failed (non-fatal)", e)
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed (non-fatal)", e)
        }
    }
}
