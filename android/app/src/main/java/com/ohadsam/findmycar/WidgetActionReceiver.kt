package com.ohadsam.findmycar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ohadsam.findmycar.widgets.QuickSaveWidgetProvider
import org.json.JSONObject

/**
 * Performs a widget-triggered parking action (save/swap/end) headlessly —
 * against the app's already-running WebView via evaluateJavascript(), never
 * bringing MainActivity to the foreground. This is what makes the widgets'
 * quick actions actually stay "on the widget" instead of jumping into the
 * app: KeepRunning + ParkingForegroundService already keep the WebView's JS
 * alive in the background (the same precondition BT/GPS auto-detection
 * already relies on), so there's normally a live window.app to call into.
 *
 * Falls back to actually opening the app (the old behavior) only when the
 * WebView isn't alive at all (app fully killed, not just backgrounded) —
 * there is no JS to run against in that case.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_VEHICLE_ID = "vehicleId"
        private const val TAG = "FMC-WidgetAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(QuickSaveWidgetProvider.EXTRA_ACTION)
        if (action.isNullOrBlank()) return
        val vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID)

        val webView = MainActivity.getActiveWebView()
        if (webView == null) {
            Log.i(TAG, "no live WebView — falling back to opening the app for action=$action")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(QuickSaveWidgetProvider.EXTRA_ACTION, action)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
            return
        }

        val actionArg  = JSONObject.quote(action)
        val vehicleArg = if (vehicleId.isNullOrBlank()) "null" else JSONObject.quote(vehicleId)
        val script = """
            (function() {
              if (!window.app || !window.app.performWidgetAction) return;
              window.app.performWidgetAction($actionArg, $vehicleArg)
                .then(function(msg) { if (window.AndroidWidgetBridge) window.AndroidWidgetBridge.onResult(msg); })
                .catch(function() { if (window.AndroidWidgetBridge) window.AndroidWidgetBridge.onResult('שגיאה בביצוע הפעולה'); });
            })();
        """.trimIndent()

        Log.i(TAG, "running headless widget action=$action vehicleId=$vehicleId")
        // onReceive() already runs on the main thread for a manifest-registered
        // receiver with no custom Handler — evaluateJavascript() itself is
        // async/non-blocking, so calling it directly here is safe.
        webView.evaluateJavascript(script, null)
    }
}
