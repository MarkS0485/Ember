package uk.co.twinscrollgridbalancer.tsgbheater.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// Flow-based wrapper over Android's BluetoothLeScanner. Emits a fresh
// snapshot of discovered devices every time a new advert is seen — the
// callsite (BleManager) is responsible for collapsing those into the
// stateful device list shown in the UI.
class BleScanner(private val ctx: Context) {

    private val adapter: BluetoothAdapter? by lazy {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "No BLE scanner available — closing flow")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull()
                trySend(
                    DiscoveredDevice(
                        mac           = d.address,
                        name          = name,
                        rssi          = result.rssi,
                        isKnownHeater = BleConstants.isHeaterName(name),
                        lastSeenAtMs  = System.currentTimeMillis(),
                    )
                )
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Run an unfiltered scan and filter in code. The service UUID in
        // BleConstants is a best-guess until the protocol doc lands; an
        // unfiltered scan avoids missing the heater because of a stale
        // filter, at the cost of needing a name-match pass downstream.
        Log.i(TAG, "Starting BLE scan")
        scanner.startScan(/*filters*/ null, settings, callback)

        awaitClose {
            Log.i(TAG, "Stopping BLE scan")
            runCatching { scanner.stopScan(callback) }
        }
    }

    private companion object { const val TAG = "BleScanner" }
}
