package com.ohadsam.findmycar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.os.Build
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

    @PluginMethod
    fun startWatch(call: PluginCall) {
        if (!watching) {
            watching = true
            BtEventBus.addListener(this)
            ParkingForegroundService.setReasonActive(context, "bluetooth", true)
            prevLabels = connectedDeviceLabels()
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
        for (label in current) if (!prevLabels.contains(label)) emitAndTrack(label, connected = true)
        for (label in prevLabels.toList()) if (!current.contains(label)) emitAndTrack(label, connected = false)
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
        prevLabels = prevLabels.toMutableSet().apply { if (connected) add(label) else remove(label) }
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
