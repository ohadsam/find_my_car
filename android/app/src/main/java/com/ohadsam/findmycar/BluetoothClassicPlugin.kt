package com.ohadsam.findmycar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.ohadsam.findmycar.core.BtDecisionEngine
import com.ohadsam.findmycar.core.BtShadowFormatter
import com.ohadsam.findmycar.core.VehicleJsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drop-in native replacement for the web BluetoothController (js/bluetooth.js)
 * — same event shape (connected/disconnected with a device "label") — but
 * backed by real classic-Bluetooth ACL connection state instead of the
 * enumerateDevices()/devicechange proxy the browser is limited to. See
 * js/bluetooth-native.js for the JS-side counterpart.
 */
@CapacitorPlugin(
    name = "BluetoothClassic",
    permissions = [
        Permission(strings = [Manifest.permission.BLUETOOTH_CONNECT], alias = "bluetooth")
    ]
)
class BluetoothClassicPlugin : Plugin(), BtEventBus.Listener {

    companion object { private const val TAG = "FMC-BtPlugin" }

    private var watching = false
    private var prevLabels: MutableSet<String> = mutableSetOf()
    private val labelsLock = Any()

    override fun handleOnDestroy() {
        BtEventBus.removeListener(this)
        if (watching) ParkingForegroundService.setReasonActive(context, "bluetooth", false)
        super.handleOnDestroy()
    }

    // Named requestBtPermission (not requestPermissions) — Plugin already
    // declares requestPermissions(call) itself, and Kotlin refuses to
    // silently hide an inherited member without `override`.
    @PluginMethod
    fun requestBtPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val ret = JSObject(); ret.put("granted", true); call.resolve(ret); return
        }
        requestPermissionForAlias("bluetooth", call, "permissionCallback")
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        val granted = getPermissionState("bluetooth") == PermissionState.GRANTED
        val ret = JSObject(); ret.put("granted", granted); call.resolve(ret)
    }

    // Android silently stops re-prompting after the user denies a runtime
    // permission twice ("don't ask again") — requestBtPermission() then just
    // resolves granted=false forever with no dialog at all, which looks
    // identical to "nothing happening" from the JS side. This lets the UI
    // detect that specific state and offer a way out.
    @PluginMethod
    fun permissionStatus(call: PluginCall) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getPermissionState("bluetooth") == PermissionState.GRANTED
        val canPrompt = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity == null ||
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.BLUETOOTH_CONNECT)
        val ret = JSObject()
        ret.put("granted", granted)
        ret.put("permanentlyDenied", !granted && !canPrompt)
        call.resolve(ret)
    }

    // Diagnostic-only: lets the JS side directly confirm whether
    // ParkingForegroundService (the thing that's supposed to keep BT/GPS
    // detection alive in the background) actually started, instead of only
    // ever finding out indirectly by waiting for an event that may never
    // come. See js/diag-log.js and app.js's post-startWatch() check.
    @PluginMethod
    fun isForegroundServiceRunning(call: PluginCall) {
        val ret = JSObject(); ret.put("running", ParkingForegroundService.isRunning); call.resolve(ret)
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        openAppSettingsInternal()
        call.resolve()
    }

    private fun openAppSettingsInternal() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // Standard Android battery optimization (Doze) restricts background work
    // for apps the user hasn't exempted — a very common, deterministic cause
    // of "the foreground service and BT receiver all report success, yet no
    // event or widget action ever reaches the WebView again once the app is
    // backgrounded": on many devices the OS (or an aggressive OEM skin) will
    // still reclaim the Activity/WebView unless the app is explicitly
    // exempted. Requesting the exemption directly is far more effective than
    // asking the user to hunt for it manually.
    @PluginMethod
    fun batteryOptimizationStatus(call: PluginCall) {
        // isIgnoringBatteryOptimizations() and the Doze/battery-optimization
        // concept itself don't exist before API 23 (minSdk here is 22) —
        // calling it unguarded would throw NoSuchMethodError on those devices.
        val ignoring = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        val ret = JSObject(); ret.put("ignoring", ignoring); call.resolve(ret)
    }

    @PluginMethod
    fun requestIgnoreBatteryOptimizations(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { call.resolve(); return }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Some OEMs block this specific intent — app settings is the
            // next best place for the user to find the equivalent toggle.
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS failed, falling back to app settings", e)
            openAppSettingsInternal()
        }
        call.resolve()
    }

    @PluginMethod
    fun startWatch(call: PluginCall) {
        Log.i(TAG, "startWatch() called, already watching=$watching")
        if (!watching) {
            watching = true
            BtEventBus.addListener(this)
            ParkingForegroundService.setReasonActive(context, "bluetooth", true)
            // Seed prevLabels with currently-connected devices. This scan
            // (connectedDeviceLabels()) can take up to ~1.5s waiting on the
            // async profile-proxy callbacks, and BtEventBus.addListener()
            // above is already active — so a real ACL event can land on the
            // main thread while this runs on the plugin's background thread.
            // Merge instead of overwrite so that event isn't lost.
            val seeded = connectedDeviceLabels()
            synchronized(labelsLock) { prevLabels = (prevLabels + seeded).toMutableSet() }
        }
        call.resolve()
    }

    @PluginMethod
    fun stopWatch(call: PluginCall) {
        if (watching) {
            watching = false
            BtEventBus.removeListener(this)
            ParkingForegroundService.setReasonActive(context, "bluetooth", false)
        }
        call.resolve()
    }

    @PluginMethod
    fun checkNow(call: PluginCall) {
        if (!watching) { call.resolve(); return }
        val current = connectedDeviceLabels()
        val prev = synchronized(labelsLock) { prevLabels.toSet() }
        for (label in current) if (!prev.contains(label)) emitAndTrack(label, connected = true)
        for (label in prev) if (!current.contains(label)) emitAndTrack(label, connected = false)
        call.resolve()
    }

    @PluginMethod
    fun getBondedDevices(call: PluginCall) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val arr = JSArray()
        try {
            adapter?.bondedDevices?.forEach { d ->
                val obj = JSObject()
                obj.put("label", d.name ?: "")
                arr.put(obj)
            }
        } catch (e: SecurityException) {
            // Missing BLUETOOTH_CONNECT — return what we have (empty list);
            // the JS side falls back to its permission-request prompt.
        }
        val ret = JSObject(); ret.put("devices", arr); call.resolve(ret)
    }

    // BtEventBus.Listener — fired from ParkingForegroundService's receiver
    override fun onConnected(label: String) {
        emitAndTrack(label, connected = true)
        runShadowDecision(label, connected = true)
    }
    override fun onDisconnected(label: String) {
        emitAndTrack(label, connected = false)
        runShadowDecision(label, connected = false)
    }

    // Stage 2 of the native background-detection migration (see CLAUDE.md
    // "Native background detection"): computes what BtDecisionEngine *would*
    // decide for this real event, purely to compare against what the JS side
    // actually does with the connected/disconnected event already emitted
    // above — takes no real action itself (no save/end/start call here).
    // Wrapped in a hard try/catch backstop: a bug in shadow evaluation must
    // never affect the real event, which has already been emitted by the
    // time this runs.
    private fun runShadowDecision(label: String, connected: Boolean) {
        try {
            val json = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                .getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]"
            val vehicles = VehicleJsonParser.parse(json)
            val hasParking: (String) -> Boolean = { id -> vehicles.find { it.id == id }?.hasParking ?: false }
            val summary = if (connected) {
                BtShadowFormatter.summarizeConnect(BtDecisionEngine.onConnected(vehicles, label, hasParking))
            } else {
                BtShadowFormatter.summarizeDisconnect(BtDecisionEngine.onDisconnected(vehicles, label, hasParking))
            }
            val direction = if (connected) "connected" else "disconnected"
            Log.i(TAG, "shadow decision ($direction, label=$label): $summary")
            val data = JSObject()
            data.put("direction", direction)
            data.put("label", label)
            data.put("decisions", summary)
            notifyListeners("btShadowDecision", data)
        } catch (e: Exception) {
            Log.w(TAG, "shadow decision failed (non-fatal, real BT event already handled)", e)
        }
    }

    private fun emitAndTrack(label: String, connected: Boolean) {
        synchronized(labelsLock) {
            prevLabels = prevLabels.toMutableSet().apply { if (connected) add(label) else remove(label) }
        }
        Log.i(TAG, "emitting ${if (connected) "connected" else "disconnected"} label=$label to JS")
        val data = JSObject(); data.put("label", label)
        notifyListeners(if (connected) "connected" else "disconnected", data)
    }

    /** Currently-connected classic audio devices (A2DP/HFP), the car-BT use case. */
    private fun connectedDeviceLabels(): MutableSet<String> {
        val labels = mutableSetOf<String>()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return labels
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val latch = CountDownLatch(profiles.size)
        for (profile in profiles) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices.forEach { d ->
                            d.name?.let { synchronized(labels) { labels.add(it) } }
                        }
                    } catch (e: SecurityException) {
                        // Missing permission — leave labels as-is for this profile.
                    }
                    adapter.closeProfileProxy(p, proxy)
                    latch.countDown()
                }
                override fun onServiceDisconnected(p: Int) { latch.countDown() }
            }, profile)
        }
        try { latch.await(1500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { /* use what we have */ }
        return labels
    }
}
