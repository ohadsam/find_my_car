package com.ohadsam.findmycar.core

/**
 * Formats BtDecisionEngine's decisions into a short, human-readable summary
 * for shadow-mode logging (Logcat + the in-app diagnostic log's BT-SHADOW
 * category) — lets a developer or a user comparing their diagnostic log see
 * what native *would* have decided against what the real JS side actually
 * did, without native taking any action itself. Pure and Android-framework-
 * free like the rest of core, so it's covered by plain JUnit.
 */
object BtShadowFormatter {
    fun summarizeConnect(decisions: List<BtConnectDecision>): String {
        if (decisions.isEmpty()) return "none"
        return decisions.joinToString(", ") { d ->
            when (d) {
                is BtConnectDecision.AutoEnd    -> "autoEnd(${d.vehicle.name})"
                is BtConnectDecision.SuggestEnd -> "suggestEnd(${d.vehicle.name})"
            }
        }
    }

    fun summarizeDisconnect(decisions: List<BtDisconnectDecision>): String {
        if (decisions.isEmpty()) return "none"
        return decisions.joinToString(", ") { d ->
            when (d) {
                is BtDisconnectDecision.AutoStart -> "autoStart(${d.vehicle.name})"
            }
        }
    }
}
