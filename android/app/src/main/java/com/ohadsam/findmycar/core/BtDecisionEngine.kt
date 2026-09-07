package com.ohadsam.findmycar.core

/** Mirrors one branch of js/app.js's #onBtConnected decision logic. */
sealed class BtConnectDecision {
    data class AutoEnd(val vehicle: NativeVehicle) : BtConnectDecision()
    data class SuggestEnd(val vehicle: NativeVehicle) : BtConnectDecision()
}

/** Mirrors one branch of js/app.js's #onBtDisconnected decision logic. */
sealed class BtDisconnectDecision {
    data class AutoStart(val vehicle: NativeVehicle) : BtDisconnectDecision()
}

/**
 * Pure, framework-independent port of js/app.js's #onBtConnected/
 * #onBtDisconnected decision branches — NOT their side effects (saving
 * parking, showing modals/toasts/notifications, GPS fetches). Those stay
 * execution-time concerns handled by the caller; this only decides what
 * *should* happen given a device label and the current vehicle/parking
 * state, so it's testable with plain JUnit — no Android framework, no
 * Robolectric, no emulator.
 *
 * This is Stage 1 of moving background BT/GPS decision-making out of the
 * WebView and into native code (see CLAUDE.md's "Native background
 * detection" section) — not yet wired into BluetoothClassicPlugin's real
 * event handling. It exists standalone first so its decisions can be
 * verified against the JS side's real-world behavior before anything
 * depends on it.
 */
object BtDecisionEngine {
    /**
     * One decision per vehicle whose bluetoothDevice matches `label` and has
     * an active parking session. At most one SuggestEnd is ever returned
     * per call, matching the JS UI's "only one confirm modal open at a
     * time" behavior (#state.btPendingVehicleId) — AutoEnd is unbounded,
     * since the JS loop keeps auto-ending every matching vehicle regardless
     * of how many there are.
     */
    fun onConnected(
        vehicles: List<NativeVehicle>,
        label: String,
        hasCurrentParking: (vehicleId: String) -> Boolean,
    ): List<BtConnectDecision> {
        val decisions = mutableListOf<BtConnectDecision>()
        var suggestionClaimed = false
        for (v in vehicles) {
            if (v.bluetoothDevice != label) continue
            if (!hasCurrentParking(v.id)) continue
            if (v.bluetoothAutoEnd) {
                decisions.add(BtConnectDecision.AutoEnd(v))
            } else if (!suggestionClaimed) {
                decisions.add(BtConnectDecision.SuggestEnd(v))
                suggestionClaimed = true
            }
        }
        return decisions
    }

    /**
     * One AutoStart decision per vehicle whose bluetoothDevice matches
     * `label`, has bluetoothAutoStart on, and has no active parking yet.
     * GPS fetch success/failure (js/app.js aborts and rolls back the active
     * vehicle switch if location is unavailable) is an execution-time
     * concern the caller handles — this only decides which vehicles are
     * eligible to attempt an auto-start.
     */
    fun onDisconnected(
        vehicles: List<NativeVehicle>,
        label: String,
        hasCurrentParking: (vehicleId: String) -> Boolean,
    ): List<BtDisconnectDecision> {
        val decisions = mutableListOf<BtDisconnectDecision>()
        for (v in vehicles) {
            if (v.bluetoothDevice != label) continue
            if (!v.bluetoothAutoStart) continue
            if (hasCurrentParking(v.id)) continue
            decisions.add(BtDisconnectDecision.AutoStart(v))
        }
        return decisions
    }
}
