package com.ohadsam.findmycar

import android.content.Context
import com.ohadsam.findmycar.core.NativeAddress
import com.ohadsam.findmycar.core.NativeParkingOp
import com.ohadsam.findmycar.core.NativeParkingOpJson

/**
 * The journal of parking starts/ends native committed while the app was not
 * open. js/app.js adopts each entry verbatim on its next resume and then
 * removes exactly the ones it adopted — by id, never "clear all", so an op
 * committed in between (a Bluetooth event during the adoption) is not lost.
 *
 * Not unit-tested directly (needs a live Context) — NativeParkingOpJson is the
 * tested layer, the same split as every other *Store here.
 */
object NativeParkingStore {
    private const val PREFS = "findmycar_native_parking_ops"
    private const val KEY_JSON = "ops_json"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(context: Context): List<NativeParkingOp> =
        NativeParkingOpJson.parse(prefs(context).getString(KEY_JSON, "[]") ?: "[]")

    @Synchronized
    fun add(context: Context, op: NativeParkingOp) {
        write(context, getAll(context) + op)
    }

    @Synchronized
    fun remove(context: Context, opIds: Collection<String>) {
        if (opIds.isEmpty()) return
        write(context, getAll(context).filterNot { it.opId in opIds })
    }

    @Synchronized
    fun contains(context: Context, opId: String): Boolean = getAll(context).any { it.opId == opId }

    /** Fills in the address of a start op once the native lookup answers. */
    @Synchronized
    fun patchAddress(context: Context, opId: String, address: NativeAddress?) {
        val ops = getAll(context)
        if (ops.none { it.opId == opId }) return // already adopted — JS resolves its own
        write(context, ops.map {
            if (it.opId != opId) it
            else if (address != null) it.copy(address = address, noAddress = false)
            else it.copy(address = null, noAddress = true)
        })
    }

    private fun write(context: Context, ops: List<NativeParkingOp>) {
        prefs(context).edit().putString(KEY_JSON, NativeParkingOpJson.toJson(ops)).apply()
    }
}
