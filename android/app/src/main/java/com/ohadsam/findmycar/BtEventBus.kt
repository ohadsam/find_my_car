package com.ohadsam.findmycar

/**
 * In-process bridge from ParkingForegroundService's BroadcastReceiver
 * (which owns the long-lived registration for ACTION_ACL_CONNECTED /
 * ACTION_ACL_DISCONNECTED) to BluetoothClassicPlugin (which turns events
 * into Capacitor listener callbacks for the JS side).
 */
object BtEventBus {
    interface Listener {
        fun onConnected(label: String)
        fun onDisconnected(label: String)
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
    fun emitConnected(label: String) {
        listeners.toList().forEach { it.onConnected(label) }
    }

    @Synchronized
    fun emitDisconnected(label: String) {
        listeners.toList().forEach { it.onDisconnected(label) }
    }
}
