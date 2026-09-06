package com.ohadsam.findmycar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
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

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun startWatch(call: PluginCall) {
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
    override fun onConnected(label: String) = emitAndTrack(label, connected = true)
    override fun onDisconnected(label: String) = emitAndTrack(label, connected = false)

    private fun emitAndTrack(label: String, connected: Boolean) {
        synchronized(labelsLock) {
            prevLabels = prevLabels.toMutableSet().apply { if (connected) add(label) else remove(label) }
        }
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
