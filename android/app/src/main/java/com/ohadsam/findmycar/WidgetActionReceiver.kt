package com.ohadsam.findmycar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.ohadsam.findmycar.core.PendingWidgetAction
import com.ohadsam.findmycar.widgets.QuickSaveWidgetProvider
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

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
        // Set when the trigger was a notification button rather than a widget
        // tap, so the notification that asked the question can be cleared once
        // it has been answered (see BackgroundAlertNotifier.Action).
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        // Pseudo-action for a notification's "ignore" button: clears the
        // notification and does nothing else. Deliberately handled here rather
        // than in a second receiver, so every notification button goes through
        // exactly one path.
        const val ACTION_DISMISS = "dismiss"
        // How long the page gets to acknowledge before the tap is queued
        // instead. Comfortably inside a manifest receiver's ~10s budget.
        private const val ACK_TIMEOUT_MS = 2500L
        private const val TAG = "FMC-WidgetAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(QuickSaveWidgetProvider.EXTRA_ACTION)
        if (action.isNullOrBlank()) return
        val vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID)

        // Answering from the shade must always clear the question, whichever
        // button was used and whether or not the action itself succeeds.
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notifId >= 0) {
            try {
                NotificationManagerCompat.from(context).cancel(notifId)
            } catch (e: Exception) {
                Log.w(TAG, "could not cancel notification $notifId (non-fatal)", e)
            }
        }
        if (action == ACTION_DISMISS) return

        val webView = MainActivity.getActiveWebView()
        if (webView == null) {
            Log.i(TAG, "no live WebView — recording pending widget action=$action for replay on next resume")
            queueForReplay(context, action, vehicleId, "no live WebView")
            return
        }

        val actionArg  = JSONObject.quote(action)
        val vehicleArg = if (vehicleId.isNullOrBlank()) "null" else JSONObject.quote(vehicleId)
        // Returns a marker SYNCHRONOUSLY so evaluateJavascript's own callback
        // can tell "the page accepted this" from "the page could not take it".
        // The promise result still comes back separately via AndroidWidgetBridge.
        val script = """
            (function() {
              try {
                if (!window.app || !window.app.performWidgetAction) return 'FMC_NOT_READY';
                window.app.performWidgetAction($actionArg, $vehicleArg)
                  .then(function(msg) { if (window.AndroidWidgetBridge) window.AndroidWidgetBridge.onResult(msg); })
                  .catch(function() { if (window.AndroidWidgetBridge) window.AndroidWidgetBridge.onResult('שגיאה בביצוע הפעולה'); });
                return 'FMC_ACCEPTED';
              } catch (e) { return 'FMC_NOT_READY'; }
            })();
        """.trimIndent()

        Log.i(TAG, "running headless widget action=$action vehicleId=$vehicleId")

        // A live WebView object does NOT mean a live page. Until v1.42.0 this
        // was `evaluateJavascript(script, null)` against a script that began
        // `if (!window.app) return;` — so whenever the WebView existed but the
        // page had not finished initialising (cold start, a reload, a JS engine
        // the OEM had frozen), the tap vanished completely: not performed, not
        // queued, no Toast, and not one line in any log. That is a silent black
        // hole, and it is exactly what "the widget takes ages to reach the app"
        // looks like from the outside.
        //
        // So: the action is only considered delivered once the page says so.
        // Anything else — an explicit FMC_NOT_READY, or no answer at all within
        // ACK_TIMEOUT_MS — falls back to the same replay queue used when there
        // is no WebView. goAsync() keeps this receiver alive across that wait;
        // `settled` makes the two paths mutually exclusive, so an action is
        // never both performed live and queued (which would double-apply it).
        val pending = goAsync()
        val settled = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        val finish = Runnable {
            try { pending.finish() } catch (e: Exception) { Log.w(TAG, "finish() threw (non-fatal)", e) }
        }
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                Log.w(TAG, "no acknowledgement from the page within ${ACK_TIMEOUT_MS}ms — queueing action=$action")
                queueForReplay(context, action, vehicleId, "page did not acknowledge")
                finish.run()
            }
        }
        handler.postDelayed(timeout, ACK_TIMEOUT_MS)

        try {
            webView.evaluateJavascript(script) { result ->
                if (!settled.compareAndSet(false, true)) return@evaluateJavascript
                handler.removeCallbacks(timeout)
                // evaluateJavascript hands back the JSON-encoded value, so the
                // marker arrives quoted — match on containment, not equality.
                if (result != null && result.contains("FMC_ACCEPTED")) {
                    Log.i(TAG, "page accepted widget action=$action")
                } else {
                    Log.w(TAG, "page could not take widget action=$action (result=$result) — queueing")
                    queueForReplay(context, action, vehicleId, "page not ready")
                }
                finish.run()
            }
        } catch (e: Exception) {
            // evaluateJavascript can throw if the WebView is being destroyed
            // right now — the queue is the correct answer there too.
            if (settled.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                Log.w(TAG, "evaluateJavascript threw — queueing action=$action", e)
                queueForReplay(context, action, vehicleId, "evaluateJavascript threw")
                finish.run()
            }
        }
    }

    /**
     * The single "this tap will happen, just not now" path: persist it for
     * js/app.js's #reconcilePendingWidgetActions() to replay on the next
     * resume, and say so. Every branch that cannot deliver live ends here, so
     * a widget tap can no longer be lost without a trace.
     */
    private fun queueForReplay(context: Context, action: String, vehicleId: String?, why: String) {
        try {
            PendingWidgetActionStore.add(context, PendingWidgetAction(action, vehicleId, System.currentTimeMillis()))
            NativeLogStore.add(context, TAG, "WIDGET", "queued widget action \"$action\" for replay on next app open ($why)")
            Toast.makeText(context, "יבוצע כשהאפליקציה תיפתח מחדש", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "failed to record pending widget action (non-fatal)", e)
        }
    }
}
