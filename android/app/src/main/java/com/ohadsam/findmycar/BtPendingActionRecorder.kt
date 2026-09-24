package com.ohadsam.findmycar

import android.content.Context
import android.util.Log
import com.ohadsam.findmycar.core.BtConnectDecision
import com.ohadsam.findmycar.core.BtDecisionEngine
import com.ohadsam.findmycar.core.BtDisconnectDecision
import com.ohadsam.findmycar.core.NativeVehicle
import com.ohadsam.findmycar.core.PendingBtAction
import com.ohadsam.findmycar.core.VehicleJsonParser

/**
 * Stage 5 of the native background-detection migration (see CLAUDE.md
 * "Native background detection"): when the WebView is unreachable
 * (MainActivity.getActiveWebView() == null), records what BtDecisionEngine
 * decided for real, so JS can reconcile ("catch up") the next time it
 * resumes — see PendingBtActionStore. Deliberately a no-op when the WebView
 * IS reachable: the live listener path (BluetoothClassicPlugin, via
 * BtEventBus) already handles the event through the normal, unchanged flow,
 * so acting here too would double the action. Records AutoEnd/AutoStart;
 * SuggestEnd changes no state, so it is never recorded — but since v1.48.0 it
 * IS asked from here, as a notification with end/ignore buttons (see
 * suggestEnd below): leaving it to JS meant the question only appeared when
 * the app's JS happened to be awake, i.e. "not always".
 *
 * Real, previously-shipped bug this object's extraction fixes (see
 * CLAUDE.md "Open investigation" resolution): this logic used to live
 * directly inside BluetoothClassicPlugin, called only from its
 * BtEventBus.Listener callbacks — but that listener registration is torn
 * down (BluetoothClassicPlugin.handleOnDestroy() -> BtEventBus.removeListener())
 * exactly when the Activity is destroyed, which is precisely the
 * WebView-unreachable scenario this whole mechanism exists to handle. So the
 * pending-action recording could never actually fire in the one case it was
 * built for. Now called directly and unconditionally from
 * ParkingForegroundService's own BT broadcast receiver — the thing that's
 * actually alive independent of Activity/Plugin lifecycle — on every real
 * ACL broadcast, regardless of whether any live listener currently exists.
 * The `MainActivity.getActiveWebView() != null` guard below is what still
 * correctly no-ops this when the live JS path is the one handling the event.
 */
object BtPendingActionRecorder {
    private const val TAG = "FMC-BtPending"

    fun maybeRecord(context: Context, label: String, connected: Boolean) {
        try {
            // isForeground(), NOT getActiveWebView() != null — the same correction
            // Stage 7's GPS path already received, for a reason CLAUDE.md
            // explicitly (and wrongly) said did not apply to Bluetooth.
            //
            // getActiveWebView() stays non-null for the Activity's whole
            // lifetime, paused included. The claim was that a plugin event is
            // PUSHED rather than polled, so it is not subject to the throttling
            // that breaks watchPosition. The push is indeed not throttled — but
            // its DELIVERY waits for the WebView's JS engine to resume, and a
            // paused engine can sit frozen for as long as the app stays closed.
            // Real log: disconnect handled 20+ minutes late, one second after
            // the user opened the app, saving the parking where they were
            // standing rather than where the car was.
            if (MainActivity.isForeground()) return // live path genuinely handles it
            val json = context.getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
                .getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]"
            val vehicles = VehicleJsonParser.parse(json)
            val hasParking: (String) -> Boolean = { id -> vehicles.find { it.id == id }?.hasParking ?: false }
            val direction = if (connected) "connected" else "disconnected"

            if (connected) {
                // #btEndParking (js/app.js) just moves the EXISTING parking
                // record to history — it never needs a fresh location fix,
                // unlike auto-start below.
                for (decision in BtDecisionEngine.onConnected(vehicles, label, hasParking)) {
                    when (decision) {
                        is BtConnectDecision.AutoEnd ->
                            record(context, direction, "autoEnd", decision.vehicle, label, lat = null, lng = null)
                        is BtConnectDecision.SuggestEnd -> suggestEnd(context, decision.vehicle, label)
                    }
                }
            } else {
                for (decision in BtDecisionEngine.onDisconnected(vehicles, label, hasParking)) {
                    if (decision !is BtDisconnectDecision.AutoStart) continue
                    val (lat, lng) = LastKnownLocation.get(context)
                    record(context, direction, "autoStart", decision.vehicle, label, lat, lng)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "maybeRecord failed (non-fatal)", e)
        }
    }

    /**
     * "You're back at the car — end the parking?", asked natively. The same
     * question js/app.js asks live, but a Bluetooth plugin event reaches a
     * backgrounded page only when its JS engine resumes (CLAUDE.md, v1.45.0),
     * which is usually when the app is next opened — long after the drive
     * started. The buttons go through WidgetActionReceiver like every other
     * shade button, so "end" works with the app alive, frozen or killed.
     * Nothing is recorded: an unanswered question changes no state.
     */
    private fun suggestEnd(context: Context, vehicle: NativeVehicle, label: String) {
        Log.i(TAG, "asking natively whether to end ${vehicle.name}'s parking (connected to $label)")
        NativeLogStore.add(
            context, TAG, "BT-PENDING",
            "Bluetooth connected to ${vehicle.name} while the app was in the background — asked natively whether to end the parking",
        )
        BackgroundAlertNotifier.show(
            context,
            "${vehicle.icon.ifBlank { "🚗" }} הגעת לרכב?",
            "זוהה חיבור Bluetooth — יש חניה פעילה של ${vehicle.name}",
            listOf(
                BackgroundAlertNotifier.Action("סיים חניה", "end", vehicle.id),
                BackgroundAlertNotifier.Action("התעלם", WidgetActionReceiver.ACTION_DISMISS, null),
            ),
        )
    }

    private fun record(
        context: Context, direction: String, action: String, vehicle: NativeVehicle, label: String, lat: Double?, lng: Double?,
    ) {
        val entry = PendingBtAction(direction, action, vehicle.id, vehicle.name, label, lat, lng, System.currentTimeMillis())
        PendingBtActionStore.add(context, entry)
        Log.i(TAG, "recorded pending BT action (WebView unreachable): $entry")
        // Exactly the staleness a queued widget tap used to cause, reached by a
        // different path: a Bluetooth auto-end recorded here is certain to be
        // applied on the next app open, so leaving every widget showing the car
        // as parked until then is simply a wrong display, not caution.
        WidgetMirror.applyQueuedAction(context, if (action == "autoEnd") "end" else "save", vehicle.id, lat, lng)
        val title = if (action == "autoEnd") "🚗 חניה הסתיימה אוטומטית" else "🅿️ חניה חדשה תישמר בפתיחה הבאה"
        BackgroundAlertNotifier.show(context, title, "${vehicle.name} — יטופל כשהאפליקציה תיפתח מחדש")
    }
}
