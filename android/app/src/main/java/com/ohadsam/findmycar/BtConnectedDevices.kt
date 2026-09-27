package com.ohadsam.findmycar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Names of the classic audio devices (A2DP/HFP — the car-Bluetooth case)
 * connected right now, read by asking the Bluetooth profile services directly
 * rather than waiting for a broadcast.
 *
 * Shared by BluetoothClassicPlugin.checkNow() (the re-sync JS runs on every
 * resume) and ParkingForegroundService's poll (v1.52.0), which is the safety
 * net for an ACL broadcast that never arrives.
 *
 * **Never call this on the main thread**: the profile-proxy callbacks are
 * delivered on the main looper, so waiting for them there just burns the whole
 * timeout and returns an empty set — which would read as "everything
 * disconnected".
 *
 * @return null when the answer is unknown (no adapter, Bluetooth off, or a
 *   profile service did not answer in time) — distinct from an empty set,
 *   which means "nothing connected". A caller diffing against a previous
 *   answer must skip unknown rather than treat it as "all disconnected".
 */
object BtConnectedDevices {
    fun labels(context: Context, timeoutMs: Long = 1500): Set<String>? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val enabled = try { adapter.isEnabled } catch (e: SecurityException) { false }
        if (!enabled) return emptySet() // radio off: genuinely nothing connected
        val labels = mutableSetOf<String>()
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val latch = CountDownLatch(profiles.size)
        var answered = 0
        for (profile in profiles) {
            val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices.forEach { d ->
                            val name = try { d.name } catch (e: SecurityException) { null }
                            name?.let { synchronized(labels) { labels.add(it) } }
                        }
                        synchronized(labels) { answered++ }
                    } catch (e: SecurityException) {
                        // Missing BLUETOOTH_CONNECT — this profile's answer is unknown.
                    }
                    try { adapter.closeProfileProxy(p, proxy) } catch (e: Exception) { /* best effort */ }
                    latch.countDown()
                }
                override fun onServiceDisconnected(p: Int) { latch.countDown() }
            }, profile)
            if (!ok) latch.countDown()
        }
        val complete = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }
        return synchronized(labels) { if (complete && answered == profiles.size) labels.toSet() else null }
    }
}
