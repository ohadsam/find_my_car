package com.ohadsam.findmycar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Backs the in-app "setup background detection" guide (js/oem-setup.js) — the
 * one place that tells the user, in order, exactly which device settings still
 * need their attention, and opens each screen for them.
 *
 * Split cleanly in two by what Android actually lets an app know:
 *
 * - **Verifiable** (`status()` reports these truthfully, and they re-check
 *   every time the guide is opened): the standard battery-optimization
 *   exemption, location permission, notification permission, and
 *   BLUETOOTH_CONNECT. All four have real APIs.
 * - **Not verifiable, only openable**: the OEM's Autostart permission and its
 *   own per-app battery policy. There is no API to read these — an app cannot
 *   tell whether MIUI's "Autostart" is on. `status()` deliberately reports
 *   `available` (can we open that screen at all?) rather than pretending to
 *   report `granted`, and the JS side tracks the user's own "I did this"
 *   confirmation locally instead of inventing a state it cannot observe.
 *   Claiming otherwise would be worse than saying nothing: the whole point of
 *   this guide is that the user can trust what it tells them.
 *
 * Locking the app in Recents has neither — no API, no launchable screen, it is
 * a pure launcher gesture — so it exists only as an instruction in the guide,
 * with no method here.
 */
@CapacitorPlugin(name = "OemSetup")
class OemSetupPlugin : Plugin() {
    companion object { private const val TAG = "FMC-OemSetup" }

    // `context` below is Plugin's own inherited getContext() (accessed as a
    // Kotlin synthetic property), same as BluetoothClassicPlugin/
    // WidgetDataPlugin — deliberately not redeclared here, which would shadow
    // the parent's accessor.

    @PluginMethod
    fun status(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: status() called")
        val ret = JSObject()
        ret.put("manufacturer", Build.MANUFACTURER ?: "")
        ret.put("isMiui", OemSettingsIntents.isMiui())

        // Verifiable — real APIs behind each of these.
        ret.put("batteryUnrestricted", isIgnoringBatteryOptimizations())
        ret.put("locationGranted", granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION))
        ret.put("locationPrecise", granted(Manifest.permission.ACCESS_FINE_LOCATION))
        // POST_NOTIFICATIONS only exists from API 33 — before that, notifications
        // are granted by default and there is nothing to request.
        ret.put("notificationsGranted",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                granted(Manifest.permission.POST_NOTIFICATIONS) else true)
        // Same for BLUETOOTH_CONNECT, which is API 31+ only.
        ret.put("bluetoothGranted",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                granted(Manifest.permission.BLUETOOTH_CONNECT) else true)

        // NOT verifiable — only "is there a screen we can open for it".
        ret.put("autostartAvailable", OemSettingsIntents.autostartAvailable(context))
        ret.put("oemBatteryAvailable", OemSettingsIntents.oemBatteryAvailable(context))

        call.resolve(ret)
    }

    @PluginMethod
    fun openAutostart(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: openAutostart() called")
        val result = OemSettingsIntents.openAutostart(context)
        NativeLogStore.add(context, TAG, "PERM", "opened autostart settings → $result")
        val ret = JSObject(); ret.put("result", result); call.resolve(ret)
    }

    @PluginMethod
    fun openOemBattery(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: openOemBattery() called")
        val result = OemSettingsIntents.openOemBattery(context)
        NativeLogStore.add(context, TAG, "PERM", "opened OEM battery settings → $result")
        val ret = JSObject(); ret.put("result", result); call.resolve(ret)
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: openAppSettings() called")
        val ok = OemSettingsIntents.openAppSettings(context)
        val ret = JSObject()
        ret.put("result", if (ok) OemSettingsIntents.RESULT_OPENED else OemSettingsIntents.RESULT_FAILED)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestIgnoreBatteryOptimizations(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: requestIgnoreBatteryOptimizations() called")
        val ok = OemSettingsIntents.requestIgnoreBatteryOptimizations(context)
        NativeLogStore.add(context, TAG, "PERM", "requested battery-optimization exemption → $ok")
        val ret = JSObject()
        ret.put("result", if (ok) OemSettingsIntents.RESULT_OPENED else OemSettingsIntents.RESULT_FAILED)
        call.resolve(ret)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean {
        // Doze/battery optimization doesn't exist before API 23 (minSdk 22) —
        // reporting "unrestricted" is correct there, not a lie.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
}
