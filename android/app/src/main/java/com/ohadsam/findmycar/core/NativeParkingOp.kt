package com.ohadsam.findmycar.core

/** A reverse-geocoded address, in the same shape as js/geocoder.js's AddressObj. */
data class NativeAddress(
    val display: String,
    val street: String? = null,
    val houseNumber: String? = null,
    val city: String? = null,
    val neighborhood: String? = null,
)

/**
 * A parking start or end that native committed while the app was not open —
 * the real record, decided and timestamped at the moment it happened. JS
 * adopts it verbatim on its next resume (it never re-decides, re-locates or
 * re-timestamps it), because by then every one of those would describe the
 * moment the app was opened rather than the moment the user parked.
 *
 * [parkingId] for "start" is the new parking's id (JS stores it as-is, which is
 * also what makes adoption idempotent); for "end" it is the parking being
 * ended, or null for "whatever is current".
 * [btDevice] is the Bluetooth label that caused it (btStartDevice/btEndDevice).
 */
data class NativeParkingOp(
    val opId: String,
    val type: String,
    val vehicleId: String,
    val parkingId: String?,
    val at: Long,
    val source: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Double? = null,
    val address: NativeAddress? = null,
    val noAddress: Boolean = false,
    val btDevice: String? = null,
) {
    companion object {
        const val START = "start"
        const val END = "end"
    }
}
