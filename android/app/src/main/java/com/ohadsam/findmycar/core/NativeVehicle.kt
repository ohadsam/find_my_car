package com.ohadsam.findmycar.core

/**
 * Native mirror of the JS vehicle model (js/vehicles.js) — only the fields
 * BtDecisionEngine and (later) GpsDecisionEngine actually need to make
 * headless decisions without the WebView alive. Populated from the JSON
 * WidgetDataPlugin.syncVehicles() stores (see js/widget-bridge.js), which
 * mirrors the full vehicle list on every #syncUI() call.
 *
 * Deliberately zero Android/org.json dependency — this is the pure data
 * shape the decision engines operate on, so tests using it don't need
 * Robolectric. Parsing it out of JSON (which does need org.json) lives in
 * VehicleJsonParser instead.
 */
data class NativeVehicle(
    val id: String,
    val name: String,
    val icon: String,
    val bluetoothDevice: String?,
    val bluetoothAutoEnd: Boolean,
    val bluetoothAutoStart: Boolean,
    val bluetoothStartPopup: Boolean,
)
