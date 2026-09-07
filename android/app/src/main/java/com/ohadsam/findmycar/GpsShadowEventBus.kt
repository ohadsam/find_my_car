package com.ohadsam.findmycar

import com.ohadsam.findmycar.core.GpsDecision

/**
 * In-process bridge from ParkingForegroundService's location watch (Stage 4
 * of the native background-detection migration — shadow mode only, see
 * CLAUDE.md "Native background detection") to WidgetDataPlugin, which turns
 * the event into a Capacitor listener callback for the JS side. Mirrors
 * BtEventBus's Service -> Plugin pattern exactly, for the same reason: the
 * Service that owns the long-lived watch isn't itself a Capacitor Plugin
 * and has no notifyListeners() of its own.
 */
object GpsShadowEventBus {
    interface Listener {
        fun onGpsShadowDecision(trigger: String, decision: GpsDecision)
    }

    private val listeners = mutableListOf<Listener>()

    @Synchronized
    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    @Synchronized
    fun emit(trigger: String, decision: GpsDecision) {
        listeners.toList().forEach { it.onGpsShadowDecision(trigger, decision) }
    }
}
