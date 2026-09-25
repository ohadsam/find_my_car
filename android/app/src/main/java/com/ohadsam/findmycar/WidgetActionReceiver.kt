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
import com.ohadsam.findmycar.widgets.WidgetStatusRefresher
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
        // "Ignore" on a question that is also stored for the app to ask again
        // on its next open. Dismissing must clear that store too, or the app
        // re-asks in a modal something the user already declined in the shade.
        const val ACTION_DISMISS_GPS = "dismissGps"
        const val ACTION_DISMISS_WALK = "dismissWalk"
        // "Save the parking at the spot recorded in PendingParkingSuggestionStore"
        // — the walk-away suggestion's accept button. Deliberately carries no
        // coordinates of its own: the location lives in that store, which both
        // the live path and the killed-app replay read, so the saved spot is
        // where the car actually is rather than wherever the user is standing.
        const val ACTION_SAVE_AT = "saveAt"
        // The ↻ button every widget now carries. Deliberately NOT queueable:
        // there is no state change to replay, so a refresh that cannot reach
        // the page simply repaints from the native mirror and says so.
        const val ACTION_REFRESH = "refresh"
        // How long the page gets to acknowledge before the tap is queued
        // instead. Comfortably inside a manifest receiver's ~10s budget.
        private const val ACK_TIMEOUT_MS = 2500L
        // Same bound as CFG.widgetFixMaxAgeMs: an older cached fix may be from
        // somewhere else entirely, so it is not trusted to place a parking.
        private const val FIX_MAX_AGE_MS = 600_000L
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
        when (action) {
            ACTION_DISMISS -> return
            ACTION_DISMISS_GPS -> {
                PendingGpsSuggestionStore.clear(context)
                NativeLogStore.add(context, TAG, "GPS-PENDING", "drive-away suggestion ignored from the notification — cleared, the app will not re-ask")
                return
            }
            ACTION_DISMISS_WALK -> {
                PendingParkingSuggestionStore.clear(context)
                WalkAwayDetector.closeWindow(context, "suggestion declined from the notification")
                return
            }
        }
        if (action == ACTION_REFRESH) { handleRefresh(context); return }

        val webView = MainActivity.getActiveWebView()
        if (webView == null) {
            Log.i(TAG, "no live WebView — recording pending widget action=$action for replay on next resume")
            queueForReplay(context, action, vehicleId, "no live WebView")
            return
        }

        val actionArg  = JSONObject.quote(action)
        val vehicleArg = if (vehicleId.isNullOrBlank()) "null" else JSONObject.quote(vehicleId)
        // A frozen page runs this script only when it thaws — typically when
        // the app is opened, long after ACK_TIMEOUT_MS made us queue the tap.
        // Passing the tap time lets the page drop that late delivery, so the
        // queued replay (with the location recorded at the tap) is the one
        // that runs, not a second save at wherever the user is now.
        val tappedAt = System.currentTimeMillis()
        // Returns a marker SYNCHRONOUSLY so evaluateJavascript's own callback
        // can tell "the page accepted this" from "the page could not take it".
        // The promise result still comes back separately via AndroidWidgetBridge.
        val script = """
            (function() {
              try {
                if (!window.app || !window.app.performWidgetAction) return 'FMC_NOT_READY';
                window.app.performWidgetAction($actionArg, $vehicleArg, { tappedAt: $tappedAt })
                  .then(function(msg) { if (msg && window.AndroidWidgetBridge) window.AndroidWidgetBridge.onResult(msg); })
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
     * The page could not take this action live, so native performs it itself,
     * now: the parking is saved/ended by NativeParkingCommitter at the moment of
     * the tap — its real time and the fix cached at the tap — and the app adopts
     * that record verbatim when it next opens. Only when there is no trustworthy
     * location for a save does it fall back to queueing the tap for the app,
     * which then takes a live fix only if opened within a couple of minutes and
     * otherwise refuses rather than saving the wrong spot. Every branch ends in
     * a Toast and a log line, so a tap can never be lost without a trace.
     */
    private fun queueForReplay(context: Context, action: String, vehicleId: String?, why: String) {
        try {
            if (commitNatively(context, action, vehicleId, why)) return
            val now = System.currentTimeMillis()
            val fix = if (action == "save" || action == "swap") LastKnownLocation.getFix(context) else null
            PendingWidgetActionStore.add(
                context,
                PendingWidgetAction(action, vehicleId, now, fix?.lat, fix?.lng, fix?.accuracy, fix?.time),
            )
            val fixNote = when {
                action != "save" && action != "swap" -> ""
                fix == null -> ", no cached location"
                else -> ", cached location too old to trust (${(now - fix.time) / 60000} min)"
            }
            NativeLogStore.add(context, TAG, "WIDGET", "queued widget action \"$action\" for the app ($why$fixNote)")
            Toast.makeText(context, "יבוצע כשהאפליקציה תיפתח מחדש", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "failed to handle widget action natively (non-fatal)", e)
        }
    }

    /** @return true when the action was fully handled here (performed or refused with a reason). */
    private fun commitNatively(context: Context, action: String, vehicleId: String?, why: String): Boolean {
        val now = System.currentTimeMillis()
        val v = NativeParkingCommitter.vehicle(context, vehicleId)
        if (v == null) {
            NativeLogStore.add(context, TAG, "WIDGET", "widget action \"$action\" — vehicle not known natively, leaving it for the app")
            return false
        }
        NativeLogStore.add(context, TAG, "WIDGET", "page unreachable ($why) — handling \"$action\" natively")
        when (action) {
            "end" -> {
                val ended = NativeParkingCommitter.commitEnd(context, v.id, "widget")
                done(context, v.label, if (ended != null) "✅ החניה הסתיימה — ${v.label}" else "אין חניה פעילה לסיום — ${v.label}", notify = ended != null)
                return true
            }
            ACTION_SAVE_AT -> {
                val s = PendingParkingSuggestionStore.get(context)
                val lat = s?.lat
                val lng = s?.lng
                if (s == null || lat == null || lng == null) return false // no recorded spot — let the app decide
                if (v.parked) {
                    done(context, v.label, "כבר קיימת חניה פעילה — ${v.label}", notify = false)
                    return true
                }
                NativeParkingCommitter.commitStart(context, s.vehicleId, lat, lng, null, "walkAway", btDevice = s.label.ifBlank { null })
                PendingParkingSuggestionStore.clear(context)
                WalkAwayDetector.closeWindow(context, "parking saved from the notification")
                done(context, v.label, "🅿️ חניה נשמרה — ${v.label}", notify = true)
                return true
            }
            "save", "swap" -> {
                val fix = LastKnownLocation.getFix(context)?.takeIf { now - it.time <= FIX_MAX_AGE_MS } ?: return false
                if (action == "save" && v.parked) {
                    done(context, v.label, "יש כבר חניה פעילה — ${v.label} (להחלפה השתמש ב\"החלף חניה\")", notify = false)
                    return true
                }
                if (action == "swap") {
                    if (!v.parked) {
                        done(context, v.label, "אין חניה פעילה להחלפה — ${v.label}", notify = false)
                        return true
                    }
                    NativeParkingCommitter.commitEnd(context, v.id, "widget")
                }
                NativeParkingCommitter.commitStart(context, v.id, fix.lat, fix.lng, fix.accuracy, "widget")
                done(
                    context, v.label,
                    if (action == "swap") "🔄 החניה הוחלפה — ${v.label}" else "🅿️ חניה נשמרה — ${v.label}",
                    notify = true,
                )
                return true
            }
            else -> return false
        }
    }

    // Titled with the vehicle (v1.51.0): with several vehicles, "FindMyCar"
    // alone left the shade unable to say which car an action was about.
    private fun done(context: Context, vehicleLabel: String, message: String, notify: Boolean) {
        NativeLogStore.add(context, TAG, "WIDGET", "widget action handled natively → $message")
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (notify) BackgroundAlertNotifier.show(context, vehicleLabel.ifBlank { "FindMyCar" }, message)
    }

    /**
     * The ↻ button. Repaints from the native mirror unconditionally — that is
     * instant, cannot fail, and is the most current thing native knows — and
     * additionally asks the page to re-sync real state when it is reachable,
     * which is the only way to pick up a change JS made without telling the
     * mirror. Nothing is ever queued: replaying a refresh on next open would be
     * pointless, since opening the app syncs anyway.
     */
    private fun handleRefresh(context: Context) {
        WidgetDataPlugin.refreshDataWidgets(context)
        WidgetStatusRefresher.refreshAll(context)

        val webView = MainActivity.getActiveWebView()
        if (webView == null) {
            val pending = WidgetMirror.hasPendingSync(context)
            NativeLogStore.add(
                context, TAG, "WIDGET",
                "refresh tapped — repainted from the native mirror (no live WebView" +
                    (if (pending) ", changes still awaiting reconciliation)" else ")"),
            )
            Toast.makeText(
                context,
                if (pending) "עודכן — יסונכרן כשהאפליקציה תיפתח" else "עודכן",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // Same synchronous-marker contract as a real action, so "the page took
        // it" stays distinguishable from "the page was not ready" — never the
        // callback-less evaluateJavascript that once swallowed taps silently.
        val script = """
            (function() {
              try {
                if (!window.app || !window.app.performWidgetAction) return 'FMC_NOT_READY';
                window.app.performWidgetAction('refresh', null);
                return 'FMC_ACCEPTED';
              } catch (e) { return 'FMC_NOT_READY'; }
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(script) { result ->
                val accepted = result != null && result.contains("FMC_ACCEPTED")
                NativeLogStore.add(
                    context, TAG, "WIDGET",
                    if (accepted) "refresh tapped — the page is re-syncing real state into the mirror"
                    else "refresh tapped — the page was not ready (result=$result); the mirror repaint still applied",
                )
            }
            Toast.makeText(context, "מסנכרן…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "refresh evaluateJavascript threw (non-fatal)", e)
            Toast.makeText(context, "עודכן", Toast.LENGTH_SHORT).show()
        }
    }
}
