package com.ohadsam.findmycar.widgets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.ohadsam.findmycar.ParkingForegroundService
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetDataPlugin

/**
 * The two at-a-glance liveness dots every widget carries: is background GPS
 * detection actually working, and is background Bluetooth detection actually
 * working.
 *
 * Why this exists: three separate bugs in a row (the service stopping when the
 * app closed with no parking; background location silently withheld without a
 * `location`-typed FGS; that same type then killing the service outright on a
 * boot restart) all produced the identical, completely silent symptom —
 * "nothing happens." Each was only diagnosable by exporting the diagnostic log
 * and reading it line by line. A colored dot on the home screen turns that into
 * something visible before a whole drive is wasted.
 *
 * **Four states, not two.** A red dot for something the user deliberately
 * turned off would be a lie, and would teach them to ignore the dot — the same
 * discipline as the OEM setup guide's "cannot verify" badge. So:
 *
 *  - [GREEN] working as intended.
 *  - [AMBER] running, but degraded in a way that matters: specifically, the GPS
 *    watch is active while the `location` foreground-service type is NOT, which
 *    means Android is withholding updates whenever the app isn't visible. That
 *    is precisely the v1.37.1 post-reboot state, and it is invisible in every
 *    other way — the service looks alive, the watch reports started.
 *  - [RED] should be running and isn't. This is the only state that means
 *    "something is wrong."
 *  - [GRAY] deliberately off, or nothing to do right now (no active parking to
 *    watch). Not a problem, and must never be shown as one.
 *
 * Everything here is read live at render time: [ParkingForegroundService.isRunning]
 * is a static in this same process, and a running foreground service keeps that
 * process alive — so if the service is genuinely up, this reads `true` with no
 * staleness, and if the process had to be started just to render the widget,
 * `false` is the correct answer rather than a stale one.
 */
object WidgetStatus {
    // Reused from the app's own palette (style.css --color-success/warning/
    // danger/text-muted) so the dots read as the same language as the app.
    private const val GREEN = 0xFF2ED573.toInt()
    private const val AMBER = 0xFFFFA502.toInt()
    private const val RED   = 0xFFFF4757.toInt()
    private const val GRAY  = 0xFF4A5568.toInt()

    /** Tints both dots on an already-built RemoteViews. Safe to call for every widget. */
    fun render(context: Context, views: RemoteViews) {
        val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        val serviceUp = ParkingForegroundService.isRunning

        // ── GPS ───────────────────────────────────────────────────
        val gpsEnabled = prefs.getBoolean(WidgetDataPlugin.KEY_GPS_AUTO_END_ENABLED, false)
        val hasParking = prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false)
        val locationGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val watchActive = prefs.getBoolean(WidgetDataPlugin.KEY_GPS_WATCH_ACTIVE, false)
        val locationTypeActive = prefs.getBoolean(WidgetDataPlugin.KEY_GPS_LOCATION_TYPE_ACTIVE, false)

        val gps = when {
            // Nothing to watch: the setting is off, or no car is parked. Both
            // are normal, neither is a fault — gray, never red.
            !gpsEnabled || !hasParking -> GRAY to "זיהוי נסיעה: לא פעיל כרגע (אין חניה או שההגדרה כבויה)"
            !locationGranted           -> RED to "זיהוי נסיעה: חסרה הרשאת מיקום"
            !serviceUp                 -> RED to "זיהוי נסיעה: שירות הרקע לא רץ"
            !watchActive               -> RED to "זיהוי נסיעה: מעקב המיקום לא פעיל"
            // The invisible failure this whole indicator exists for.
            !locationTypeActive        -> AMBER to "זיהוי נסיעה: פעיל אך אנדרואיד מונע עדכונים ברקע — פתח את האפליקציה פעם אחת"
            else                       -> GREEN to "זיהוי נסיעה: פעיל"
        }

        // ── Bluetooth ─────────────────────────────────────────────
        val btEnabled = prefs.getBoolean(WidgetDataPlugin.KEY_BT_ENABLED, false)
        val btGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val btReceiverActive = prefs.getBoolean(WidgetDataPlugin.KEY_BT_RECEIVER_ACTIVE, false)

        val bt = when {
            !btEnabled       -> GRAY to "Bluetooth: מכובה בהגדרות"
            !btGranted       -> RED to "Bluetooth: חסרה הרשאה"
            !serviceUp       -> RED to "Bluetooth: שירות הרקע לא רץ"
            !btReceiverActive -> RED to "Bluetooth: מקלט האירועים לא רשום"
            else             -> GREEN to "Bluetooth: פעיל"
        }

        views.setInt(R.id.widget_status_gps, "setColorFilter", gps.first)
        views.setContentDescription(R.id.widget_status_gps, gps.second)
        views.setInt(R.id.widget_status_bt, "setColorFilter", bt.first)
        views.setContentDescription(R.id.widget_status_bt, bt.second)
    }
}
