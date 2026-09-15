package com.ohadsam.findmycar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Launches the device-settings screens the user has to visit by hand for
 * background detection to survive on an aggressive OEM skin.
 *
 * Why this exists at all: the standard Android battery-optimization exemption
 * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, already requested at first
 * launch) is the ONLY one of these with a public API. Xiaomi/MIUI's "Autostart"
 * permission and its separate per-app battery policy — the two settings that
 * actually decide whether this app's foreground service and its BOOT_COMPLETED
 * receiver are allowed to run at all — have no public API to query or toggle,
 * and are documented nowhere by the OEM. What they DO have is a settings
 * Activity that can be launched directly, which is the difference between
 * "go hunt through Settings for a toggle whose name varies by ROM version" and
 * one tap from inside the app.
 *
 * These component names are unofficial and version-specific by nature, so every
 * launch is: resolve first (needs the matching <queries> entry in the manifest
 * on Android 11+), try/catch around startActivity anyway, and fall back to the
 * app's own system settings page — which always exists and always contains
 * *something* relevant — rather than failing visibly. A screen that doesn't
 * open is a dead end the user can't distinguish from a bug, so there is always
 * a fallback and the caller is always told which one it got.
 *
 * Shared by BluetoothClassicPlugin (its existing app-settings/battery-exemption
 * entry points) and OemSetupPlugin (the guided setup flow) so the two never
 * drift apart — same reasoning as BackgroundAlertNotifier.
 */
object OemSettingsIntents {
    private const val TAG = "FMC-OemSettings"

    /** Result of a launch attempt, so the UI can say what actually opened. */
    const val RESULT_OPENED = "opened"        // the real OEM screen
    const val RESULT_FALLBACK = "fallback"    // the app's generic settings page
    const val RESULT_FAILED = "failed"        // nothing opened at all

    // Autostart / background-launch permission, per OEM. Ordered most-specific
    // first within each vendor — MIUI renamed this Activity across versions.
    private val AUTOSTART_COMPONENTS = listOf(
        // Xiaomi / Redmi / POCO (MIUI + HyperOS)
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / Realme (ColorOS)
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        // Vivo (FuntouchOS / OriginOS)
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        // Letv / OnePlus(older) / Asus
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
    )

    // The OEM's OWN per-app battery policy — distinct from Android's standard
    // battery-optimization exemption, which is requested separately. On MIUI
    // both exist independently and BOTH must be relaxed; exempting only the
    // standard one (the part with a public API) still leaves the app killable.
    private val BATTERY_COMPONENTS = listOf(
        // Xiaomi — "Battery saver" per-app policy list
        "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity",
        // Huawei
        "com.huawei.systemmanager" to "com.huawei.systemmanager.power.ui.HwPowerManagerActivity",
        // Oppo / Realme
        "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        // Samsung
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )

    /**
     * MIUI's own per-app permission editor. Unlike the component list above
     * this is a real (if undocumented) action string, so it survives Activity
     * renames across MIUI versions — worth trying as a second chance before
     * giving up on the vendor screen entirely and falling back to the generic
     * app-settings page.
     */
    private fun miuiPermEditorIntent(context: Context) =
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun isMiui(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m == "xiaomi" || m == "redmi" || m == "poco"
    }

    /** True when at least one known autostart screen is present on this device. */
    fun autostartAvailable(context: Context): Boolean =
        AUTOSTART_COMPONENTS.any { (pkg, cls) -> resolves(context, pkg, cls) } ||
            resolves(context, "com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")

    fun oemBatteryAvailable(context: Context): Boolean =
        BATTERY_COMPONENTS.any { (pkg, cls) -> resolves(context, pkg, cls) }

    fun openAutostart(context: Context): String {
        for ((pkg, cls) in AUTOSTART_COMPONENTS) {
            if (launch(context, pkg, cls)) return RESULT_OPENED
        }
        // MIUI second chance: the action-based permission editor.
        if (isMiui() && launch(context, miuiPermEditorIntent(context))) return RESULT_OPENED
        return if (openAppSettings(context)) RESULT_FALLBACK else RESULT_FAILED
    }

    fun openOemBattery(context: Context): String {
        for ((pkg, cls) in BATTERY_COMPONENTS) {
            if (launch(context, pkg, cls)) return RESULT_OPENED
        }
        return if (openAppSettings(context)) RESULT_FALLBACK else RESULT_FAILED
    }

    /**
     * The app's own entry in the system Settings app. Always available, so this
     * is every other method's fallback — and the honest destination for
     * "review this app's permissions", since Android has no public intent that
     * opens one specific permission's screen directly.
     */
    fun openAppSettings(context: Context): Boolean = launch(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )

    /**
     * Android's standard battery-optimization exemption dialog (API 23+).
     * Falls back to the app settings page on OEMs that block this specific
     * intent. Mirrors what BluetoothClassicPlugin did inline before this
     * object existed.
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val ok = launch(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        if (!ok) {
            Log.w(TAG, "battery-exemption intent unavailable — falling back to app settings")
            return openAppSettings(context)
        }
        return true
    }

    private fun resolves(context: Context, pkg: String, cls: String): Boolean = try {
        val intent = Intent().setComponent(ComponentName(pkg, cls))
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (e: Exception) {
        false
    }

    private fun launch(context: Context, pkg: String, cls: String): Boolean = launch(
        context,
        Intent().apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        // ActivityNotFoundException (screen absent or invisible to us under
        // package-visibility filtering) and SecurityException (some OEMs guard
        // these Activities) are both expected here — never fatal, just move on
        // to the next candidate.
        false
    }
}
