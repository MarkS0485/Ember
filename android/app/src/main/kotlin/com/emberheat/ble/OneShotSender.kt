package com.emberheat.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

// Side-channel GATT writer for fan-out group commands. Connects fresh,
// finds the write characteristic by property, sends one payload, and
// closes. Independent of HeaterConnection so the main UI connection isn't
// disturbed during a broadcast.
//
// One sender is single-use; build a new one per write. Safe to run several
// in sequence; do NOT run in parallel against the same MAC.
@SuppressLint("MissingPermission")
class OneShotSender(private val ctx: Context) {

    suspend fun send(mac: String, payload: ByteArray, totalTimeoutMs: Long = 10_000L): Boolean {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: return false
        val device  = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return false

        val gattRef = AtomicReference<BluetoothGatt?>(null)
        val writeDone = CompletableDeferred<Boolean>()

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "[$mac] connected — discovering services")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!writeDone.isCompleted) writeDone.complete(false)
                        runCatching { g.close() }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { writeDone.complete(false); return }
                val svc = g.getService(BleConstants.HEATER_SERVICE)
                    ?: g.services.firstOrNull { svc ->
                        svc.characteristics.any { (it.properties and WRITE_BITS) != 0 }
                    }
                if (svc == null) { writeDone.complete(false); return }
                val writeChar: BluetoothGattCharacteristic? = svc.characteristics
                    .firstOrNull { (it.properties and WRITE_BITS) != 0 }
                if (writeChar == null) { writeDone.complete(false); return }

                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(writeChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                            BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        writeChar.value     = payload
                        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        g.writeCharacteristic(writeChar)
                    }
                }
                if (!started) writeDone.complete(false)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeDone.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        return try {
            gattRef.set(device.connectGatt(ctx, /*autoConnect*/ false, cb))
            withTimeoutOrNull(totalTimeoutMs) { writeDone.await() } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "[$mac] one-shot send failed", t)
            false
        } finally {
            gattRef.get()?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
        }
    }

    private companion object {
        const val TAG = "OneShotSender"
        const val WRITE_BITS = BluetoothGattCharacteristic.PROPERTY_WRITE or
                               BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    }
}
