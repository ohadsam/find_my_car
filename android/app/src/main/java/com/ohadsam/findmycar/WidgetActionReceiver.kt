package com.ohadsam.findmycar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.ohadsam.findmycar.core.PendingWidgetAction
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
 * When the WebView isn't alive at all (app fully killed, not just
 * backgrounded — no JS to run against), Stage 8 of the native background-
 * detection migration (see CLAUDE.md) records the tapped action to
 * PendingWidgetActionStore and shows a Toast directly, instead of the
 * older fallback of force-opening the app: js/app.js's
 * #reconcilePendingWidgetActions() replays it through the same real
 * performWidgetAction() the next time the app resumes.
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
            Log.i(TAG, "no live WebView — recording pending widget action=$action for replay on next resume")
            try {
                PendingWidgetActionStore.add(context, PendingWidgetAction(action, vehicleId, System.currentTimeMillis()))
                Toast.makeText(context, "יבוצע כשהאפליקציה תיפתח מחדש", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "failed to record pending widget action (non-fatal)", e)
            }
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
