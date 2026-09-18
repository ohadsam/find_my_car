package com.ohadsam.findmycar

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import com.ohadsam.findmycar.core.WalkAwayDecision
import com.ohadsam.findmycar.core.WalkAwayEngine
import com.ohadsam.findmycar.core.WalkAwayState
import com.ohadsam.findmycar.widgets.WidgetStatusRefresher
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

        // GPS thresholds mirroring js/config.js's CFG.gpsSpeedThreshold/
        // gpsSpeedDuration/gpsVehicleEvidenceMs/gpsSpeedSampleCapMs/
        // gpsDerivedSpeedMinIntervalMs/gpsEvidenceTtlMs/
        // gpsDistanceThreshold — keep these in sync if those ever change
        // (there is no single shared source between JS and Kotlin for these
        // constants). See CLAUDE.md "Vehicle-movement detection" for why the
        // speed bar is set where it is and why distance now needs evidence.
        private const val GPS_SPEED_THRESHOLD_MPS = 7.0
        private const val GPS_SPEED_DURATION_MS = 120_000L
        private const val GPS_VEHICLE_EVIDENCE_MS = 10_000L
        private const val GPS_SPEED_SAMPLE_CAP_MS = 15_000L
        private const val GPS_DERIVED_SPEED_MIN_INTERVAL_MS = 5000L
        private const val GPS_EVIDENCE_TTL_MS = 600_000L
        private const val GPS_DISTANCE_THRESHOLD_M = 300.0
        // Walk-away detection thresholds, mirroring js/config.js's
        // CFG.walkMinSpeed/walkMaxSpeed/walkAbortSpeed/walkRequiredMs/
        // walkMinDisplacement/walkWindowMs — same hand-kept JS<->Kotlin parity
        // as the GPS constants above (see CLAUDE.md "Walk-away parking
        // suggestion").
        private const val WALK_MIN_SPEED_MPS = 0.5
        private const val WALK_MAX_SPEED_MPS = 3.0
        private const val WALK_ABORT_SPEED_MPS = 6.0
        private const val WALK_REQUIRED_MS = 8_000L
        private const val WALK_MIN_DISPLACEMENT_M = 30.0
        private const val WALK_WINDOW_MS = 600_000L

        private const val LOCATION_MIN_TIME_MS = 3000L
        private const val LOCATION_MIN_DISTANCE_M = 5f

        // Mirrors js/config.js's CFG.diagHeartbeatIntervalMs — no single
        // shared source between JS and Kotlin, keep in sync if it ever
        // changes (see CLAUDE.md "Heartbeats").
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        private const val ACTION_HEARTBEAT = "com.ohadsam.findmycar.ACTION_HEARTBEAT"

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
                    NativeLogStore.add(context, TAG, "SERVICE", "starting foreground service (reason=$reason)")
                    ContextCompat.startForegroundService(context, intent)
                } else if (!wasEmpty && nowEmpty) {
                    NativeLogStore.add(context, TAG, "SERVICE", "stopping foreground service (last reason=$reason cleared)")
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
                instanceRef?.get()?.onParkingActiveChanged(parkingIsActive)
            }
        }

        @Synchronized
        private fun isParkingReasonActive(): Boolean = activeReasons.contains("parking")

        /**
         * A walk-away window opened or closed. The location watch is normally
         * tied to the "parking" reason, but a walk-away window needs it while
         * there is deliberately NO parking yet — so the running instance has to
         * re-evaluate. Same WeakReference nudge as setReasonActive()'s.
         */
        fun onWalkAwayWindowChanged(context: Context) {
            instanceRef?.get()?.refreshLocationWatch()
        }

        // activeReasons is plain in-memory static state — it does NOT survive
        // process death (an OEM background killer, an OOM kill, a reboot, or an
        // app update all wipe it), and every setReasonActive() caller is a
        // Capacitor @PluginMethod, i.e. only ever reachable while the app is
        // actually open. So after any process restart the set is empty and
        // nothing short of the user reopening the app can repopulate it: a
        // START_STICKY-restarted service would come back with its BT receiver
        // registered but its GPS watch never started, despite a parking being
        // genuinely active. Re-deriving both reasons from the persisted mirror
        // (the same SharedPreferences the widgets and native decision engines
        // already read) is what makes the service self-healing instead.
        // Additive on purpose — never clears a reason a live JS call has since
        // set, it only fills in what was lost.
        @Synchronized
        fun restoreReasons(context: Context) {
            try {
                val prefs = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false)) activeReasons.add("parking")
                if (prefs.getBoolean(WidgetDataPlugin.KEY_BT_ENABLED, false)) activeReasons.add("bluetooth")
            } catch (e: Exception) {
                Log.w(TAG, "restoreReasons failed (non-fatal)", e)
            }
        }

        // Boot/app-update recovery: nothing else starts this service without the
        // app being opened first, so after a reboot (or an APK update, which
        // also kills the process) background BT/GPS detection stayed dead until
        // the user happened to launch the app. Called from ServiceRestartReceiver.
        /**
         * MainActivity.onResume() → the app is visible, so a location-typed
         * foreground service may now be started where it wasn't before. No-op
         * unless the service is actually running with an active parking.
         * See onBecameEligibleForLocationType().
         */
        // @JvmStatic because MainActivity is Java: a plain companion function
        // compiles to ParkingForegroundService.Companion.onAppForegrounded(),
        // which Java cannot call as a static. This is the only cross-language
        // call into this companion — every other caller is Kotlin.
        @JvmStatic
        fun onAppForegrounded() {
            try {
                if (!isRunning) return
                instanceRef?.get()?.onBecameEligibleForLocationType()
            } catch (e: Exception) {
                Log.w(TAG, "onAppForegrounded threw (non-fatal)", e)
            }
        }

        fun startIfNeeded(context: Context) {
            restoreReasons(context)
            val reasons = synchronized(this) { activeReasons.toSet() }
            if (reasons.isEmpty()) {
                NativeLogStore.add(context, TAG, "SERVICE", "restart check: nothing to restore (no parking, Bluetooth off) — service not started")
                return
            }
            try {
                NativeLogStore.add(context, TAG, "SERVICE", "restarting foreground service after boot/update (reasons=$reasons)")
                ContextCompat.startForegroundService(context, Intent(context, ParkingForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "startIfNeeded: startForegroundService threw", e)
                NativeLogStore.add(context, TAG, "SERVICE", "restart after boot/update FAILED (${e.message})")
            }
        }
    }

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var gpsShadowState = GpsDecisionState()
    private var walkAwayState = WalkAwayState()
    // The window the current walkAwayState belongs to, so a NEW disconnect
    // (a different window) resets the accumulator instead of inheriting the
    // previous one's progress.
    private var walkAwayWindowAt: Long? = null

    // Previous fix, kept solely so GpsDecisionEngine.effectiveSpeed() can
    // derive a speed when the platform reports none — Location.hasSpeed() is
    // false on plenty of real devices, and requiring speed evidence (which the
    // distance trigger now does) must not silently disable detection on them.
    private var prevFixLat: Double? = null
    private var prevFixLng: Double? = null
    private var prevFixAt: Long? = null

    private var heartbeatReceiver: BroadcastReceiver? = null
    private var heartbeatPendingIntent: PendingIntent? = null

    // The foreground-service type currently in effect, and whether the LOCATION
    // bit was present when this service ENTERED the foreground state.
    //
    // These are different facts, and conflating them was the v1.38.1 bug: the
    // while-in-use location capability an FGS gets is bound to the moment it
    // entered foreground state. A service started from a background context
    // (ServiceRestartReceiver on BOOT_COMPLETED/MY_PACKAGE_REPLACED) never had
    // that capability — and re-calling startForeground() later with the LOCATION
    // bit added does NOT grant it retroactively. Android accepts the new type
    // without error and keeps withholding updates, so every signal we had said
    // "location type active" while the OS delivered nothing.
    @Volatile private var currentType = 0
    @Volatile private var startedWithLocationType = false
    // One restart attempt per process — a restart loop would be far worse than
    // the bug it fixes.
    @Volatile private var locationRestartAttempted = false

    // Rolling GPS-delivery counters, reported in each heartbeat. This is the
    // diagnostic that was missing: "GPS watch started" told us the request was
    // accepted, never whether a single fix actually arrived. Folded into the
    // existing 5-minute heartbeat rather than logged per update, so it costs
    // zero extra entries against NativeLogStore's 200-entry cap.
    @Volatile private var gpsUpdatesSinceHeartbeat = 0
    @Volatile private var lastFixAt = 0L
    @Volatile private var lastFixDistanceM = -1.0

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            // Never a bare startForeground() — a rejected type must cost us
            // that type, not the BT receiver and everything else below.
            val type = startForegroundResilient()
            registerBtReceiver()
            instanceRef = WeakReference(this)
            // Rebuild any reason lost to a process restart before deciding
            // whether the GPS watch should be running (see restoreReasons()).
            restoreReasons(this)
            // Covers the case where "parking" was already active before this
            // instance started (e.g. the service starts fresh because of the
            // "parking" reason itself) — setReasonActive()'s direct nudge to
            // instanceRef only helps once an instance already exists.
            refreshLocationWatch()
            isRunning = true
            Log.i(TAG, "onCreate succeeded — foreground service running (type=$type)")
            NativeLogStore.add(this, TAG, "SERVICE", "onCreate succeeded — foreground service running (type=$type)")
            startHeartbeat()
        } catch (e: Exception) {
            // Starting this service must never crash the whole app — worst
            // case BT/GPS background detection is inactive until the next
            // successful start (e.g. once the user grants BLUETOOTH_CONNECT).
            Log.e(TAG, "onCreate failed — BT/GPS background detection inactive", e)
            NativeLogStore.add(this, TAG, "SERVICE", "onCreate FAILED — BT/GPS background detection inactive (${e.message})")
            isRunning = false
            stopSelf()
        }
    }

    // **The `location` type is what makes background GPS work at all** — a real,
    // previously-shipped bug (see CLAUDE.md "Background location needs a
    // location-typed foreground service"). From Android 10 (API 29) on, an app
    // may only receive location updates while it isn't in the foreground if
    // either it holds ACCESS_BACKGROUND_LOCATION, or the updates come from a
    // foreground service declared with the `location` type — and this service
    // had neither, so updateLocationWatch()'s LocationManager request silently
    // stopped delivering the moment the Activity left the foreground. Confirmed
    // from a real device log: the native GPS watch only ever produced a
    // GPS-SHADOW decision one second after the app was opened (foregrounded),
    // never once during an actual drive with the app closed.
    //
    // Each type OR'd in here must ALSO be declared in the manifest's
    // android:foregroundServiceType AND have its runtime prerequisite granted —
    // Android 14+ throws from startForeground() otherwise, which for a service
    // that starts on EVERY parking save would crash the app. So each bit is
    // gated on its own permission check, with specialUse (no prerequisite at
    // all) as the always-safe fallback when neither is granted yet.
    private fun resolveForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val btGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val locationGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        var type = 0
        if (locationGranted && canStartLocationType()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (btGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (type != 0) return type

        // Neither granted yet (e.g. very first parking save before any prompt) —
        // specialUse is the only type with no runtime-permission prerequisite.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE // pre-14: not runtime-permission-gated
        }
    }

    /**
     * Whether a `location`-typed foreground service may be STARTED right now.
     *
     * Real, previously-shipped bug (v1.36.3, caught from a production log):
     * having ACCESS_FINE/COARSE_LOCATION granted is necessary but NOT
     * sufficient. Android 14 refused the start outright:
     *
     *   Starting FGS with type location ... requires permissions:
     *   all of [FOREGROUND_SERVICE_LOCATION] any of [ACCESS_COARSE_LOCATION,
     *   ACCESS_FINE_LOCATION] **and the app must be in the eligible state/
     *   exemptions to access the foreground only permission**
     *
     * That last clause is the whole thing. Without ACCESS_BACKGROUND_LOCATION,
     * location is a *foreground-only* permission: the app only actually holds
     * it while it has while-in-use capability. Starting the service from a
     * background context — which is exactly what ServiceRestartReceiver does on
     * BOOT_COMPLETED/MY_PACKAGE_REPLACED — means no while-in-use capability, so
     * startForeground() threw, onCreate()'s single try/catch caught it, and the
     * ENTIRE service died: no BT receiver, no heartbeat, no detection at all
     * until the user next opened the app by hand. The v1.36.3 fix for missing
     * background GPS therefore broke the v1.36.3 fix for restarting after a
     * reboot — each verified in isolation, never together.
     *
     * So: claim the location type only when the start would actually be
     * eligible. Everything else still starts normally, and the type is upgraded
     * later via onAppForegrounded() once the app is genuinely in the
     * foreground. Note the restriction applies to STARTING/UPDATING the
     * service, not to keeping it running — a location-typed service started
     * while foreground keeps delivering updates after the app is backgrounded,
     * which is the entire point.
     */
    private fun canStartLocationType(): Boolean {
        // The eligibility rule arrived in Android 14; below that, a granted
        // location permission is enough.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        // ACCESS_BACKGROUND_LOCATION would make the app permanently eligible,
        // but this app deliberately never declares it (see CLAUDE.md) — so it
        // is not checked here: an undeclared permission can never be granted,
        // and checking for it would be dead code implying an option the user
        // does not actually have. Eligibility therefore reduces to: is the
        // Activity genuinely visible right now.
        return MainActivity.isForeground()
    }

    /**
     * Calls startForeground() so that it can never take the whole service down
     * with it. A type Android rejects costs us that type — never the BT
     * receiver, the heartbeat, or the reason bookkeeping that follow it in
     * onCreate(). Returns the type actually in effect, for logging.
     *
     * This is the structural half of the bug above: the gate in
     * canStartLocationType() encodes the rule as understood today, but this
     * codebase cannot compile or run Android locally, and OEM builds enforce
     * these rules with their own variations. A future type/permission rule we
     * get wrong should degrade one capability, not produce another total
     * outage.
     */
    private fun startForegroundResilient(): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return 0
        }
        val preferred = resolveForegroundServiceType()
        try {
            if (preferred != 0) startForeground(NOTIFICATION_ID, notification, preferred)
            else startForeground(NOTIFICATION_ID, notification)
            currentType = preferred
            startedWithLocationType =
                (preferred and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
            return preferred
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(type=$preferred) rejected — retrying without it", e)
            NativeLogStore.add(
                this, TAG, "SERVICE",
                "foreground-service type $preferred rejected (${e.message}) — retrying with a safe type"
            )
        }
        // Safe retry: specialUse has no runtime prerequisite at all, so it is
        // the one type that cannot be refused for a permission reason.
        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (fallback != 0) startForeground(NOTIFICATION_ID, notification, fallback)
        else startForeground(NOTIFICATION_ID, notification)
        currentType = fallback
        startedWithLocationType = false
        return fallback
    }

    /**
     * The app just became visible, so a `location`-typed start is now eligible
     * where it may not have been before (see canStartLocationType()). Called
     * from MainActivity.onResume().
     *
     * Without this, a service started from BOOT_COMPLETED correctly comes up
     * without the location type — and then keeps running without it forever,
     * since nothing else re-resolves the type for an already-running service
     * whose "parking" reason never transitions again.
     */
    fun onBecameEligibleForLocationType() {
        // shouldWatchLocation(), NOT isParkingReasonActive(): the walk-away
        // window is the second consumer of this watch and deliberately runs
        // while there is NO parking, so gating on parking alone would leave it
        // permanently unable to obtain the location capability — the same
        // "every signal reads healthy while the OS delivers nothing" failure
        // this restart exists to fix, just reached by the other consumer.
        if (!shouldWatchLocation()) return

        // The real fix (v1.38.1). If this service entered the foreground state
        // WITHOUT the location type — which is exactly what a boot/update
        // restart produces, since ServiceRestartReceiver runs from a background
        // broadcast — then it holds no while-in-use location capability, and
        // refreshForegroundServiceType() alone cannot grant one. Android accepts
        // the added type silently and still delivers nothing, which is why every
        // signal read "location type active" through a whole drive that produced
        // no fixes at all. The only way to obtain the capability is to enter the
        // foreground state again, now, from a foreground app context.
        if (!startedWithLocationType && !locationRestartAttempted && canStartLocationType() && locationPermitted()) {
            locationRestartAttempted = true
            NativeLogStore.add(
                this, TAG, "SERVICE",
                "restarting service from the foreground to obtain background-location capability " +
                    "(it had entered foreground with type=$currentType, without the location bit)"
            )
            restartFromForeground()
            return
        }

        refreshForegroundServiceType()
        // The watch itself may never have started (or started under a type
        // Android was throttling) — restarting it under the now-correct type
        // is what actually resumes background GPS.
        refreshLocationWatch()
    }

    private fun locationPermitted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Stops this service and immediately starts it again from the current
     * (foreground) context, so it re-enters the foreground state while the app
     * genuinely holds while-in-use location — the only way to gain the
     * background-location capability retroactively.
     *
     * Safe because `activeReasons` is rebuilt by restoreReasons() from the
     * persisted mirror on the new instance, so nothing is lost by the bounce;
     * guarded by locationRestartAttempted so it can only ever happen once per
     * process.
     */
    private fun restartFromForeground() {
        val ctx = applicationContext
        try {
            updateLocationWatch(false) // drop the watch that was never delivering
            stopSelf()
            ContextCompat.startForegroundService(ctx, Intent(ctx, ParkingForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "restartFromForeground failed (non-fatal)", e)
            NativeLogStore.add(ctx, TAG, "SERVICE", "foreground restart for location capability FAILED (${e.message})")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY means Android restarts this service (with a null intent)
        // after a process kill — but activeReasons is plain in-memory static
        // state, so it comes back EMPTY, and nothing but a JS call would ever
        // repopulate it. Re-derive it from the persisted state on every start,
        // so a restarted service knows whether to run its GPS watch instead of
        // silently coming back half-dead. See restoreReasons().
        restoreReasons(this)
        refreshLocationWatch()
        return START_STICKY
    }

    /**
     * Persists one live-state flag for the widgets' status dots (WidgetStatus).
     * Written alongside — never instead of — the NativeLogStore entry at the
     * same point, so the diagnostic log and the dots can never disagree about
     * what happened. Wrapped because a status indicator must never be able to
     * break the machinery it reports on.
     */
    /**
     * The heartbeat line. Carries the GPS-delivery summary so a quiet stretch is
     * never ambiguous between "the watch isn't running", "it's running but no
     * fix ever arrives" (the OS withholding them) and "fixes arrive but no
     * threshold was crossed" — three very different faults that looked
     * identical in every previous report.
     */
    private fun heartbeatMessage(): String {
        val sb = StringBuilder("heartbeat — foreground service alive (reasons=$activeReasons")
        sb.append(", fgsType=").append(currentType)
        sb.append(if (startedWithLocationType) "+loc@start" else " NO-loc@start")
        if (locationListener != null) {
            val n = gpsUpdatesSinceHeartbeat
            gpsUpdatesSinceHeartbeat = 0
            sb.append(", gpsFixes=").append(n)
            if (lastFixAt > 0L) {
                sb.append(", lastFix=").append((System.currentTimeMillis() - lastFixAt) / 1000).append("s ago")
                if (lastFixDistanceM >= 0) sb.append(", dist=").append(lastFixDistanceM.toInt()).append("m")
            } else {
                sb.append(", lastFix=NEVER")
            }
            // Accumulated time observed at vehicle speed. Both triggers now
            // depend on it (distance needs GPS_VEHICLE_EVIDENCE_MS of it before
            // it may fire at all), so "fixes arrive, distance is large, nothing
            // suggested" is only decidable with this number in the line.
            sb.append(", vehEvid=").append(gpsShadowState.speedAccumMs / 1000).append('s')
        } else {
            sb.append(", gpsWatch=off")
        }
        return sb.append(')').toString()
    }

    private fun recordHeartbeatAt() {
        try {
            getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                .edit().putLong(WidgetDataPlugin.KEY_SVC_HEARTBEAT_AT, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "recordHeartbeatAt failed (non-fatal)", e)
        }
        // The dots' own refresh alarm is Doze-throttled the same way this
        // heartbeat is, so repainting here too means a widget is never staler
        // than the most recent proof-of-life the service itself produced.
        WidgetStatusRefresher.refreshAll(this)
    }

    private fun setStatusFlag(key: String, value: Boolean) {
        try {
            getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            Log.w(TAG, "setStatusFlag($key) failed (non-fatal)", e)
        }
    }

    private fun refreshForegroundServiceType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val type = resolveForegroundServiceType()
            if (type != 0) {
                startForeground(NOTIFICATION_ID, buildNotification(), type)
                if (type != currentType) {
                    NativeLogStore.add(
                        this, TAG, "SERVICE",
                        "foreground-service type changed $currentType -> $type" +
                            (if (!startedWithLocationType) " (NOTE: service entered foreground WITHOUT the location " +
                                "type, so this alone does not grant background-location capability)" else "")
                    )
                }
                currentType = type
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshForegroundServiceType failed (non-fatal)", e)
            NativeLogStore.add(this, TAG, "SERVICE", "could not refresh foreground-service type (${e.message})")
        }
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "onDestroy — foreground service stopped")
        NativeLogStore.add(this, TAG, "SERVICE", "onDestroy — foreground service stopped")
        // Nothing is running any more — clear every liveness flag so the
        // widgets' dots go red rather than showing the last good state forever.
        setStatusFlag(WidgetDataPlugin.KEY_BT_RECEIVER_ACTIVE, false)
        setStatusFlag(WidgetDataPlugin.KEY_GPS_WATCH_ACTIVE, false)
        setStatusFlag(WidgetDataPlugin.KEY_GPS_LOCATION_TYPE_ACTIVE, false)
        WidgetStatusRefresher.refreshAll(this)
        stopHeartbeat()
        receiver?.let { try { unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* already gone */ } }
        receiver = null
        updateLocationWatch(false)
        if (instanceRef?.get() === this) instanceRef = null
        super.onDestroy()
    }

    // Logs a "heartbeat" entry to NativeLogStore's SERVICE category roughly
    // every HEARTBEAT_INTERVAL_MS for as long as this service instance is
    // alive — the native counterpart to js/app.js's #startDiagHeartbeat().
    // Neither heartbeat is useful moment-to-moment; both exist so a gap in
    // the diagnostic log is provably a real gap (that side genuinely
    // stopped running) rather than just "nothing happened to log", which
    // was previously indistinguishable from "it's broken" when reviewing a
    // report.
    //
    // A real, previously-shipped bug: the first version of this used a
    // plain Handler(Looper.getMainLooper()).postDelayed(...)
    // self-rescheduling Runnable — that has NO wake source of its own.
    // Once the device enters Doze (screen off, stationary), a Handler
    // timer only actually runs whenever the CPU happens to wake up for
    // some UNRELATED reason (a GPS fix, an incoming broadcast, a Doze
    // maintenance window) — confirmed from real production diagnostic-log
    // evidence: heartbeats that should have landed exactly 5 minutes apart
    // instead landed 7-21+ minutes apart, growing more irregular the
    // longer the device stayed idle (classic Doze maintenance-window
    // backoff). That's not "approximately every 5 minutes" — it's
    // "whenever something else happens to wake the CPU", which defeats the
    // whole point of a heartbeat (a predictable liveness cadence, not an
    // opportunistic one). Fixed by using
    // AlarmManager.setExactAndAllowWhileIdle instead — the OS-documented,
    // no-special-permission API specifically meant for "run approximately
    // on schedule even during Doze" (distinct from setExact()/
    // setAlarmClock(), which need the user-facing SCHEDULE_EXACT_ALARM
    // permission and exist for a different, user-visible-alarm purpose).
    // Below API 23 (Doze itself doesn't exist pre-M), falls back to a
    // plain AlarmManager.set() — still routed through the same
    // receiver/reschedule path rather than branching into a second
    // implementation.
    private fun startHeartbeat() {
        stopHeartbeat() // idempotent — never double-register/double-schedule if called twice
        NativeLogStore.add(this, TAG, "SERVICE", heartbeatMessage())
        recordHeartbeatAt()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                NativeLogStore.add(ctx, TAG, "SERVICE", heartbeatMessage())
                recordHeartbeatAt()
                scheduleNextHeartbeat()
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_HEARTBEAT), ContextCompat.RECEIVER_NOT_EXPORTED)
        heartbeatReceiver = receiver
        scheduleNextHeartbeat()
    }

    private fun scheduleNextHeartbeat() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(ACTION_HEARTBEAT).setPackage(packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
            heartbeatPendingIntent = pi
            val triggerAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleNextHeartbeat failed (non-fatal)", e)
        }
    }

    private fun stopHeartbeat() {
        heartbeatReceiver?.let { try { unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* already gone */ } }
        heartbeatReceiver = null
        heartbeatPendingIntent?.let { pi ->
            (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
        }
        heartbeatPendingIntent = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Real, reported bug under investigation: a user connected/disconnected
    // Bluetooth to their car (and drove) but got zero end/start-parking
    // notifications. Diagnostic-log evidence showed this receiver's own
    // "ACL broadcast: ..." line — logged UNCONDITIONALLY on every raw
    // ACTION_ACL_CONNECTED/DISCONNECTED, regardless of whether a vehicle is
    // linked or the WebView is reachable — never appeared even once across
    // many hours the receiver was confirmed alive (one continuous
    // "onCreate succeeded" the whole window, meaning this receiver was never
    // re-registered or torn down). That means the OS never delivered a
    // single ACL broadcast to this process during that window — the failure
    // is upstream of all of this app's own decision logic, not inside it.
    // Also listens for ACTION_STATE_CHANGED (the Bluetooth radio's own
    // on/off/connecting/disconnecting state, distinct from a specific
    // device's ACL state) purely as an additional diagnostic signal: if the
    // next report shows adapter-state-changed entries but still zero ACL
    // entries, that proves broadcasts in general DO reach the app and the
    // gap is specific to ACL/device-level events (pointing at a real device
    // connection never actually completing, or this device's BT stack not
    // emitting ACL for its car link) — if NEITHER ever appears, that points
    // at broadcasts being blocked entirely (OS/permission/OEM level).
    private fun registerBtReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    val stateName = when (state) {
                        BluetoothAdapter.STATE_OFF          -> "OFF"
                        BluetoothAdapter.STATE_TURNING_OFF   -> "TURNING_OFF"
                        BluetoothAdapter.STATE_ON           -> "ON"
                        BluetoothAdapter.STATE_TURNING_ON    -> "TURNING_ON"
                        else -> "UNKNOWN($state)"
                    }
                    Log.i(TAG, "Bluetooth adapter state changed: $stateName")
                    NativeLogStore.add(context, TAG, "SERVICE", "Bluetooth adapter state changed: $stateName (diagnostic only — not a device connect/disconnect)")
                    return
                }
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val label = try { device?.name } catch (e: SecurityException) {
                    Log.w(TAG, "device.getName() threw SecurityException — BLUETOOTH_CONNECT not granted?", e)
                    null
                }
                if (label.isNullOrBlank()) {
                    Log.w(TAG, "ACL broadcast (${intent.action}) received with no readable device name — dropped")
                    NativeLogStore.add(context, TAG, "SERVICE", "ACL broadcast (${intent.action}) received with no readable device name — dropped")
                    return
                }
                Log.i(TAG, "ACL broadcast: ${intent.action} label=$label")
                // Logged regardless of whether anything downstream ends up
                // acting on it (e.g. no vehicle linked to this label) — this
                // is proof the OS receiver itself is alive and receiving real
                // broadcasts, independent of WebView reachability or of
                // whether the event turns into a decision worth its own
                // BT/BT-SHADOW/BT-PENDING entry.
                NativeLogStore.add(context, TAG, "SERVICE", "ACL broadcast: ${intent.action} label=$label")
                // BtEventBus still delivers to a live BluetoothClassicPlugin
                // listener when the Activity is alive (the normal foregrounded
                // case) — but that listener is torn down exactly when the
                // Activity is destroyed, so BtPendingActionRecorder is called
                // directly here too, unconditionally, since THIS receiver (owned
                // by the Service, not the Activity) is what's actually alive
                // independent of Activity lifecycle. It no-ops itself via its own
                // MainActivity.getActiveWebView() check when the live path is
                // the one handling the event, so this never double-acts.
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        BtEventBus.emitConnected(label)
                        BtPendingActionRecorder.maybeRecord(context, label, connected = true)
                        // Reconnected — they got back in, so there is nothing
                        // left to ask about the spot they walked away from.
                        WalkAwayDetector.closeWindow(context, "reconnected to the vehicle")
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        BtEventBus.emitDisconnected(label)
                        BtPendingActionRecorder.maybeRecord(context, label, connected = false)
                        // Opt-in, and only for vehicles with auto-start OFF —
                        // see WalkAwayDetector.eligible().
                        WalkAwayDetector.maybeOpenWindow(context, label)
                    }
                }
            }
        }
        // RECEIVER_NOT_EXPORTED is required on API 33+ for dynamically registered
        // receivers with no permission; this receiver only reacts to system BT
        // broadcasts and never needs to be reachable from other apps.
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
        Log.i(TAG, "BT ACL receiver registered")
        NativeLogStore.add(this, TAG, "SERVICE", "BT ACL receiver registered")
        setStatusFlag(WidgetDataPlugin.KEY_BT_RECEIVER_ACTIVE, true)
    }

    /**
     * The location watch serves two independent consumers now: the GPS
     * end-suggestion (while a parking is active) and the walk-away parking
     * suggestion (while a disconnect window is open, when there is deliberately
     * NO parking). Every start/stop decision goes through this one predicate so
     * neither consumer can switch the other's watch off.
     */
    private fun shouldWatchLocation(): Boolean =
        isParkingReasonActive() || PendingParkingSuggestionStore.getWindow(this) != null

    private fun refreshLocationWatch() = updateLocationWatch(shouldWatchLocation())

    /**
     * A parking genuinely started or ended. The GPS decision state is reset
     * here rather than inside updateLocationWatch(), because the watch may
     * already be running for a walk-away window — in which case
     * updateLocationWatch(true) returns early and would never reset it,
     * carrying the previous session's evidence into the new parking.
     */
    private fun onParkingActiveChanged(active: Boolean) {
        if (active) resetGpsDecisionState()
        refreshLocationWatch()
    }

    private fun resetGpsDecisionState() {
        gpsShadowState = GpsDecisionState()
        prevFixLat = null
        prevFixLng = null
        prevFixAt = null
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
                    NativeLogStore.add(this, TAG, "SERVICE", "GPS watch NOT started — no location permission")
                    return
                }
                val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm == null) {
                    Log.w(TAG, "GPS shadow watch not started — no LocationManager")
                    NativeLogStore.add(this, TAG, "SERVICE", "GPS watch NOT started — no LocationManager")
                    return
                }
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    Log.w(TAG, "GPS shadow watch not started — no enabled location provider")
                    NativeLogStore.add(this, TAG, "SERVICE", "GPS watch NOT started — no enabled location provider (GPS/network both off?)")
                    return
                }
                // The service very often starts BEFORE location permission is
                // granted — it starts on every parking save, while the prompt
                // happens during app init — so onCreate()'s startForeground()
                // may have picked a type WITHOUT the `location` bit. Android
                // would then keep withholding background location updates even
                // though the permission is granted by now. Re-calling
                // startForeground() with a freshly resolved type is the
                // documented way to add a type to an already-running FGS.
                refreshForegroundServiceType()
                // Fresh watch — reset the accumulated vehicle-speed evidence
                // and the previous-fix baseline, matching js/app.js's
                // #resetGpsDetection(). onParkingActiveChanged() does this too,
                // for the case where the watch was ALREADY running for a
                // walk-away window when a parking started (this branch returns
                // early then, so it could not do it on its own).
                resetGpsDecisionState()
                val listener = LocationListener { location -> onLocationShadow(location) }
                lm.requestLocationUpdates(provider, LOCATION_MIN_TIME_MS, LOCATION_MIN_DISTANCE_M, listener)
                locationManager = lm
                locationListener = listener
                Log.i(TAG, "GPS shadow watch started (provider=$provider)")
                // requestLocationUpdates() succeeding does NOT mean updates will
                // actually arrive: without the `location` foreground-service type
                // in effect, Android silently withholds them the moment the app
                // stops being visible. Logging a bare "started" there is exactly
                // the kind of misleading success that cost days of diagnosis
                // before — so say which of the two it is.
                val locationTypeActive = canStartLocationType() &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                setStatusFlag(WidgetDataPlugin.KEY_GPS_WATCH_ACTIVE, true)
                setStatusFlag(WidgetDataPlugin.KEY_GPS_LOCATION_TYPE_ACTIVE, locationTypeActive)
                WidgetStatusRefresher.refreshAll(this)
                NativeLogStore.add(
                    this, TAG, "SERVICE",
                    if (locationTypeActive) "GPS watch started (provider=$provider)"
                    else "GPS watch started (provider=$provider) BUT the location foreground-service type is not active " +
                        "— Android will withhold updates while the app is not visible; it upgrades when the app is next opened"
                )
            } else {
                locationListener?.let { locationManager?.removeUpdates(it) }
                locationListener = null
                locationManager = null
                Log.i(TAG, "GPS shadow watch stopped")
                NativeLogStore.add(this, TAG, "SERVICE", "GPS watch stopped")
                setStatusFlag(WidgetDataPlugin.KEY_GPS_WATCH_ACTIVE, false)
                setStatusFlag(WidgetDataPlugin.KEY_GPS_LOCATION_TYPE_ACTIVE, false)
                WidgetStatusRefresher.refreshAll(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateLocationWatch($active) failed (non-fatal)", e)
            NativeLogStore.add(this, TAG, "SERVICE", "GPS watch update($active) FAILED (${e.message})")
        }
    }

    private fun onLocationShadow(location: Location) {
        try {
            // Counted for the heartbeat's GPS summary. "GPS watch started" only
            // ever proved the REQUEST was accepted; this proves fixes actually
            // arrive — the single fact that was missing while three separate
            // "no background GPS" reports were diagnosed.
            gpsUpdatesSinceHeartbeat++
            lastFixAt = System.currentTimeMillis()
            val prefs = getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
            val hasParking = prefs.getBoolean(WidgetDataPlugin.KEY_HAS_PARKING, false)
            val gpsEnabled = prefs.getBoolean(WidgetDataPlugin.KEY_GPS_AUTO_END_ENABLED, false)
            val parkLat = prefs.getFloat(WidgetDataPlugin.KEY_LAT, 0f).toDouble()
            val parkLng = prefs.getFloat(WidgetDataPlugin.KEY_LNG, 0f).toDouble()

            val now = System.currentTimeMillis()
            val reported = if (location.hasSpeed()) location.speed.toDouble() else null
            val prevLat = prevFixLat
            val prevLng = prevFixLng
            val prevAt = prevFixAt
            val movedSincePrev = if (prevLat != null && prevLng != null) {
                GpsMath.distanceMeters(location.latitude, location.longitude, prevLat, prevLng)
            } else null
            val sincePrev = if (prevAt != null) now - prevAt else null
            val speed = GpsDecisionEngine.effectiveSpeed(
                reported, movedSincePrev, sincePrev, GPS_DERIVED_SPEED_MIN_INTERVAL_MS,
            )
            // Hold the baseline while the interval is still too short to derive
            // over, so it can actually grow past the minimum — advancing it on
            // every fix would keep every interval at the update period and make
            // derivation permanently unavailable.
            if (sincePrev == null || sincePrev >= GPS_DERIVED_SPEED_MIN_INTERVAL_MS) {
                prevFixLat = location.latitude
                prevFixLng = location.longitude
                prevFixAt = now
            }

            val distance = GpsMath.distanceMeters(location.latitude, location.longitude, parkLat, parkLng)
            lastFixDistanceM = distance

            val (afterSpeed, speedDecision) = GpsDecisionEngine.checkSpeed(
                gpsShadowState, hasParking, gpsEnabled, speed,
                GPS_SPEED_THRESHOLD_MPS, GPS_SPEED_DURATION_MS, GPS_SPEED_SAMPLE_CAP_MS,
                GPS_EVIDENCE_TTL_MS, now,
            )
            gpsShadowState = afterSpeed
            emitGpsShadowDecision("speed", speedDecision)
            maybeRecordPendingGpsSuggestion(speedDecision)

            val (afterDistance, distanceDecision) = GpsDecisionEngine.checkDistance(
                gpsShadowState, hasParking, gpsEnabled, distance,
                GPS_DISTANCE_THRESHOLD_M, GPS_VEHICLE_EVIDENCE_MS,
            )
            gpsShadowState = afterDistance
            emitGpsShadowDecision("distance", distanceDecision)
            maybeRecordPendingGpsSuggestion(distanceDecision)

            // Independent of everything above, and wrapped separately so a bug
            // in the newer feature can never break drive-away detection.
            runWalkAwayCheck(location, speed, now)
        } catch (e: Exception) {
            Log.w(TAG, "onLocationShadow failed (non-fatal)", e)
        }
    }

    /**
     * Feeds WalkAwayEngine while a disconnect window is open. Runs off the same
     * location fixes as the GPS end-suggestion above — a parking session and a
     * walk-away window are mutually exclusive in practice (the window only
     * opens for a vehicle with no active parking), but nothing here assumes it.
     */
    private fun runWalkAwayCheck(location: Location, speed: Double?, now: Long) {
        try {
            val window = PendingParkingSuggestionStore.getWindow(this) ?: return

            // A different disconnect than the one the accumulator belongs to:
            // start counting from scratch rather than inheriting its progress.
            if (walkAwayWindowAt != window.disconnectedAt) {
                walkAwayWindowAt = window.disconnectedAt
                walkAwayState = WalkAwayState()
            }

            // With no fix captured at disconnect there is no origin to measure
            // displacement from. Treat the first fix of the window as that
            // origin — less precise than the real disconnect point, but it is
            // the difference between the feature working on such devices and
            // not working at all.
            val originLat = window.lat
            val originLng = window.lng
            if (originLat == null || originLng == null) {
                PendingParkingSuggestionStore.openWindow(
                    this,
                    window.copy(lat = location.latitude, lng = location.longitude),
                )
                NativeLogStore.add(
                    this, TAG, "WALK",
                    "no fix was captured at disconnect — using the first location update as the parking spot",
                )
                return
            }

            val moved = GpsMath.distanceMeters(location.latitude, location.longitude, originLat, originLng)
            val (next, decision) = WalkAwayEngine.check(
                walkAwayState, speed, moved, window.disconnectedAt,
                WALK_MIN_SPEED_MPS, WALK_MAX_SPEED_MPS, WALK_ABORT_SPEED_MPS,
                WALK_REQUIRED_MS, WALK_MIN_DISPLACEMENT_M, WALK_WINDOW_MS,
                GPS_SPEED_SAMPLE_CAP_MS, now,
            )
            walkAwayState = next

            when (decision) {
                is WalkAwayDecision.SuggestStart -> {
                    WalkAwayDetector.raise(this, window)
                    WalkAwayDetector.closeWindow(this, "suggestion raised")
                }
                is WalkAwayDecision.Abort -> WalkAwayDetector.closeWindow(
                    this,
                    if (now - window.disconnectedAt >= WALK_WINDOW_MS) "window expired"
                    else "still moving at vehicle speed — the car did not stop here",
                )
                null -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "runWalkAwayCheck failed (non-fatal)", e)
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
            // Buttons, not "open the app": this fires while the user is
            // driving, and ending the parking is a one-tap decision that
            // belongs in the shade. Routed through WidgetActionReceiver, the
            // same headless path the widgets use — including its
            // PendingWidgetActionStore fallback if the WebView is gone.
            BackgroundAlertNotifier.show(
                this, "🚗 מזוהה נסיעה", "ייתכן שהרכב זז ממקום החניה.",
                listOf(
                    BackgroundAlertNotifier.Action("סיים חניה", "end", activeVehicleId),
                    BackgroundAlertNotifier.Action("התעלם", WidgetActionReceiver.ACTION_DISMISS, null),
                )
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
