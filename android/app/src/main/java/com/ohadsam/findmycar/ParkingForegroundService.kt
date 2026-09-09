package com.ohadsam.findmycar

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ohadsam.findmycar.core.GpsDecision
import com.ohadsam.findmycar.core.GpsDecisionEngine
import com.ohadsam.findmycar.core.GpsDecisionState
import com.ohadsam.findmycar.core.GpsMath
import com.ohadsam.findmycar.core.PendingGpsSuggestion
import com.ohadsam.findmycar.core.VehicleJsonParser
import java.lang.ref.WeakReference

/**
 * Keeps the app process alive while the screen is off or the app is
 * backgrounded, so the real Bluetooth ACL connect/disconnect broadcasts and
 * the JS GPS-speed watch (both consumed by the existing parking logic in
 * js/app.js) keep running — this is the actual fix for the PWA's background
 * limitation, which relied on a foregrounded browser tab.
 *
 * Started/stopped by a simple reference count of "reasons": active while BT
 * auto-detection is enabled (BluetoothClassicPlugin.startWatch/stopWatch) OR
 * a parking session is active (WidgetDataPlugin.update/clear).
 */
class ParkingForegroundService : Service() {

    private var receiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "FMC-FgService"
        private const val CHANNEL_ID = "findmycar_background"
        private const val NOTIFICATION_ID = 4201
        private val activeReasons = mutableSetOf<String>()

        // Stage 4 of the native background-detection migration: GPS shadow
        // thresholds mirroring js/config.js's CFG.gpsSpeedThreshold/
        // gpsSpeedDuration/gpsDistanceThreshold — keep these in sync if
        // those ever change (there is no single shared source between JS
        // and Kotlin for these constants).
        private const val GPS_SPEED_THRESHOLD_MPS = 7.0
        private const val GPS_SPEED_DURATION_MS = 8000L
        private const val GPS_DISTANCE_THRESHOLD_M = 300.0
        private const val LOCATION_MIN_TIME_MS = 3000L
        private const val LOCATION_MIN_DISTANCE_M = 5f

        // Set true only after startForeground() actually succeeds, false the
        // instant it fails or the service is torn down — lets the JS side
        // (via BluetoothClassicPlugin.isForegroundServiceRunning) directly
        // confirm the one fact it otherwise has no way to observe: whether
        // the service that's supposed to keep BT/GPS alive in the background
        // is really running, instead of silently having failed to start.
        @Volatile
        var isRunning = false
            private set

        // Lets setReasonActive() reach the running instance directly to
        // start/stop the GPS shadow location watch when the "parking"
        // reason toggles, even if the service is already running for a
        // different reason (e.g. bluetooth) and onCreate() won't fire again.
        // Mirrors MainActivity's activeInstance WeakReference pattern.
        @Volatile
        private var instanceRef: WeakReference<ParkingForegroundService>? = null

        @Synchronized
        fun setReasonActive(context: Context, reason: String, active: Boolean) {
            val wasEmpty = activeReasons.isEmpty()
            val parkingWasActive = activeReasons.contains("parking")
            if (active) activeReasons.add(reason) else activeReasons.remove(reason)
            val nowEmpty = activeReasons.isEmpty()
            val parkingIsActive = activeReasons.contains("parking")
            Log.d(TAG, "setReasonActive($reason, $active) — reasons=$activeReasons")

            val intent = Intent(context, ParkingForegroundService::class.java)
            try {
                if (wasEmpty && !nowEmpty) {
                    ContextCompat.startForegroundService(context, intent)
                } else if (!wasEmpty && nowEmpty) {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService/stopService threw", e)
                // Never let this crash the caller (e.g. a rare background-start
                // restriction) — BT/GPS detection just stays inactive this time.
            }

            // Only react to a genuine "parking" transition, not every
            // WidgetDataPlugin.update() call (which fires on every parking
            // state sync, not just session start — see CLAUDE.md) — otherwise
            // the location watch would restart constantly and its
            // GpsDecisionState would never accumulate a sustained-speed
            // window.
            if (reason == "parking" && parkingWasActive != parkingIsActive) {
                instanceRef?.get()?.updateLocationWatch(parkingIsActive)
            }
        }

        @Synchronized
        private fun isParkingReasonActive(): Boolean = activeReasons.contains("parking")
    }

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var gpsShadowState = GpsDecisionState()

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val type = resolveForegroundServiceType()
            if (type != 0) {
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            registerBtReceiver()
            instanceRef = WeakReference(this)
            // Covers the case where "parking" was already active before this
            // instance started (e.g. the service starts fresh because of the
            // "parking" reason itself) — setReasonActive()'s direct nudge to
            // instanceRef only helps once an instance already exists.
            if (isParkingReasonActive()) updateLocationWatch(true)
            isRunning = true
            Log.i(TAG, "onCreate succeeded — foreground service running (type=$type)")
        } catch (e: Exception) {
            // Starting this service must never crash the whole app — worst
            // case BT/GPS background detection is inactive until the next
            // successful start (e.g. once the user grants BLUETOOTH_CONNECT).
            Log.e(TAG, "onCreate failed — BT/GPS background detection inactive", e)
            isRunning = false
            stopSelf()
        }
    }

    // connectedDevice requires BLUETOOTH_CONNECT to already be GRANTED at
    // runtime on Android 12+ (enforced once targetSdk reaches 34) — that
    // permission is only requested lazily when the user links a vehicle's BT
    // device, so it's very often not granted yet when this service first
    // starts (e.g. from any parking save, unrelated to Bluetooth). specialUse
    // has no such prerequisite and is always safe to fall back to.
    private fun resolveForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val btGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            btGranted -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE // pre-14: declaring it isn't runtime-permission-gated
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "onDestroy — foreground service stopped")
        receiver?.let { try { unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* already gone */ } }
        receiver = null
        updateLocationWatch(false)
        if (instanceRef?.get() === this) instanceRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerBtReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val label = try { device?.name } catch (e: SecurityException) {
                    Log.w(TAG, "device.getName() threw SecurityException — BLUETOOTH_CONNECT not granted?", e)
                    null
                }
                if (label.isNullOrBlank()) {
                    Log.w(TAG, "ACL broadcast (${intent.action}) received with no readable device name — dropped")
                    return
                }
                Log.i(TAG, "ACL broadcast: ${intent.action} label=$label")
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED    -> BtEventBus.emitConnected(label)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> BtEventBus.emitDisconnected(label)
                }
            }
        }
        // RECEIVER_NOT_EXPORTED is required on API 33+ for dynamically registered
        // receivers with no permission; this receiver only reacts to system BT
        // broadcasts and never needs to be reachable from other apps.
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
        Log.i(TAG, "BT ACL receiver registered")
    }

    // Stage 4 of the native background-detection migration (see CLAUDE.md
    // "Native background detection"): runs GpsDecisionEngine in shadow mode
    // against real location updates while a parking session is active —
    // purely for comparison against the real JS decision, via
    // GpsShadowEventBus -> WidgetDataPlugin -> notifyListeners. Takes no
    // real action itself (never opens gpsEndModal, never touches parking
    // state). Wrapped in its own try/catch backstop, independent of BT
    // handling and of onCreate()'s own backstop, so a permission/provider
    // failure here can never crash the foreground service.
    private fun updateLocationWatch(active: Boolean) {
        try {
            if (active) {
                if (locationListener != null) return // already watching
                val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                if (!fineGranted && !coarseGranted) {
                    Log.w(TAG, "GPS shadow watch not started — no location permission")
                    return
                }
                val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm == null) {
                    Log.w(TAG, "GPS shadow watch not started — no LocationManager")
                    return
                }
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    Log.w(TAG, "GPS shadow watch not started — no enabled location provider")
                    return
                }
                // New parking session — reset the sustained-speed/already-
                // suggested state, matching js/app.js resetting
                // #state.gpsSpeedSince/#state.gpsEndSuggested on every save/swap.
                gpsShadowState = GpsDecisionState()
                val listener = LocationListener { location -> onLocationShadow(location) }
                lm.requestLocationUpdates(provider, LOCATION_MIN_TIME_MS, LOCATION_MIN_DISTANCE_M, listener)
                locationManager = lm
                locationListener = listener
                Log.i(TAG, "GPS shadow watch started (provider=$provider)")
            } else {
                locationListener?.let { locationManager?.removeUpdates(it) }
                locationListener = null
                locationManager = null
                Log.i(TAG, "GPS shadow watch stopped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateLocationWatch($active) failed (non-fatal)", e)
        }
    }

    private fun onLocationShadow(location: Location) {
        try {
            val prefs = getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val hasParking = prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false)
            val gpsEnabled = prefs.getBoolean(WidgetDataPlugin.KEY_GPS_AUTO_END_ENABLED, false)
            val parkLat = prefs.getFloat(WidgetDataPlugin.KEY_LAT, 0f).toDouble()
            val parkLng = prefs.getFloat(WidgetDataPlugin.KEY_LNG, 0f).toDouble()

            val speed = if (location.hasSpeed()) location.speed.toDouble() else null
            val (afterSpeed, speedDecision) = GpsDecisionEngine.checkSpeed(
                gpsShadowState, hasParking, gpsEnabled, speed,
                GPS_SPEED_THRESHOLD_MPS, GPS_SPEED_DURATION_MS, System.currentTimeMillis(),
            )
            gpsShadowState = afterSpeed
            emitGpsShadowDecision("speed", speedDecision)
            maybeRecordPendingGpsSuggestion(speedDecision)

            val distance = GpsMath.distanceMeters(location.latitude, location.longitude, parkLat, parkLng)
            val (afterDistance, distanceDecision) = GpsDecisionEngine.checkDistance(
                gpsShadowState, hasParking, gpsEnabled, distance, GPS_DISTANCE_THRESHOLD_M,
            )
            gpsShadowState = afterDistance
            emitGpsShadowDecision("distance", distanceDecision)
            maybeRecordPendingGpsSuggestion(distanceDecision)
        } catch (e: Exception) {
            Log.w(TAG, "onLocationShadow failed (non-fatal)", e)
        }
    }

    private fun emitGpsShadowDecision(trigger: String, decision: GpsDecision?) {
        if (decision == null) return
        Log.i(TAG, "GPS shadow decision ($trigger): suggestEnd")
        GpsShadowEventBus.emit(trigger, decision)
    }

    // Stage 7 of the native background-detection migration (see CLAUDE.md
    // "Native background detection"): GPS's counterpart to
    // BluetoothClassicPlugin.maybeRecordPendingAction() (Stage 5). When the
    // live JS path isn't actually running, GpsDecisionEngine's only decision
    // (SuggestEnd) can never be shown as a live confirmation modal —
    // nothing happens today. Records the suggestion (for whichever vehicle
    // is active) and shows a notification, so JS can open the same
    // gpsEndModal confirmation the next time it resumes — this NEVER
    // auto-ends a parking, unlike Bluetooth's AutoEnd, since a GPS
    // suggestion always requires user confirmation.
    //
    // Gated on MainActivity.isForeground(), NOT getActiveWebView() != null —
    // this was a real, previously-shipped bug. getActiveWebView() only tells
    // you the Activity object exists (true for the entire onCreate..onDestroy
    // span, i.e. the whole backgrounded/paused period too, since KeepRunning
    // keeps the Activity+WebView alive without destroying them); it says
    // nothing about whether the Activity is actually visible right now. The
    // live path this is meant to defer to — js/app.js's #checkGpsSpeed/
    // #checkGpsDistance, fed by navigator.geolocation.watchPosition() — is a
    // WebView-level browser API subject to Android's own background-location
    // throttling tied to Activity *visibility*, not just process/JS-engine
    // liveness: KeepRunning keeps the JS engine executing, but does not
    // prevent Android from silently throttling/stopping watchPosition()
    // updates once the Activity is merely paused (screen off, app
    // backgrounded, not destroyed) — the far more common case than the
    // Activity actually being destroyed. The old getActiveWebView() check
    // treated that entire paused-but-alive window as "handled live," so
    // nothing ever recorded a suggestion or showed a notification during it —
    // GpsDecisionEngine's own shadow watch (a plain LocationManager request
    // made directly from this foreground Service, NOT subject to the same
    // throttling) kept deciding SuggestEnd correctly the whole time, visible
    // only in the GPS-SHADOW diagnostic-log category, while the user got no
    // notification at all until they physically reopened the app — sometimes
    // long after actually leaving the vehicle. Deliberately a no-op only when
    // the Activity is genuinely foregrounded right now: the live
    // #suggestGpsEnd() path is reliable in that case. Wrapped in its own
    // try/catch backstop, independent of emitGpsShadowDecision (which must
    // stay strictly log-only).
    private fun maybeRecordPendingGpsSuggestion(decision: GpsDecision?) {
        if (decision == null) return
        try {
            if (MainActivity.isForeground()) return // live path already handles it
            val prefs = getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val activeVehicleId = prefs.getString(WidgetDataPlugin.KEY_ACTIVE_VEHICLE_ID, "") ?: ""
            if (activeVehicleId.isBlank()) return
            val vehicles = VehicleJsonParser.parse(prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]")
            val vehicleName = vehicles.find { it.id == activeVehicleId }?.name ?: ""
            PendingGpsSuggestionStore.set(this, PendingGpsSuggestion(activeVehicleId, vehicleName, System.currentTimeMillis()))
            Log.i(TAG, "recorded pending GPS suggestion (WebView unreachable) for vehicle=$vehicleName")
            BackgroundAlertNotifier.show(
                this, "🚗 מזוהה נסיעה", "ייתכן שהרכב זז ממקום החניה. פתח את האפליקציה לסיים את החניה."
            )
        } catch (e: Exception) {
            Log.w(TAG, "maybeRecordPendingGpsSuggestion failed (non-fatal)", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // LOW (not MIN) — MIN notifications are collapsed under "show
            // silent notifications" and require an extra tap to even see,
            // which made it look like the app posts no notification at all.
            // LOW still has no sound/heads-up popup, just a normal shade entry.
            val channel = NotificationChannel(
                CHANNEL_ID, "פעילות ברקע", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "שומר על זיהוי Bluetooth ומיקום פעילים ברקע בזמן חניה"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindMyCar פעיל ברקע")
            .setContentText("מזהה Bluetooth ומיקום כדי לשמור/לסיים חניה אוטומטית")
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(0xFF5B8BF5.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
