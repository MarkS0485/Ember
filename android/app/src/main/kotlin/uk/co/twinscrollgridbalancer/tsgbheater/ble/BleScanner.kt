package uk.co.twinscrollgridbalancer.tsgbheater.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.hcalory.HcaloryProtocol

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
                val advertised = result.scanRecord?.serviceUuids ?: emptyList()
                val protocol = detectProtocol(advertised, name)
                val isHeater = protocol != null || BleConstants.isHeaterName(name)
                trySend(
                    DiscoveredDevice(
                        mac           = d.address,
                        name          = name,
                        rssi          = result.rssi,
                        isKnownHeater = isHeater,
                        lastSeenAtMs  = System.currentTimeMillis(),
                        protocol      = protocol,
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

    // Walk the advertised service-UUID list and return the first match. We
    // can't rely on every heater advertising its service (HeatGenie ones
    // often don't until after GATT discovery), so a null return here is
    // not a "definitely not a heater" signal — the name-match below still
    // applies. Falls through to null when nothing's recognised; the bind
    // UI lets the user override.
    private fun detectProtocol(
        advertised: List<ParcelUuid>,
        @Suppress("UNUSED_PARAMETER") name: String?,
    ): ProtocolKind? {
        for (pu in advertised) {
            val u = pu.uuid
            if (u == BleConstants.HEATER_SERVICE) return ProtocolKind.HEATGENIE
            if (u == HcaloryProtocol.SERVICE_LEGACY || u == HcaloryProtocol.SERVICE_CUSTOM)
                return ProtocolKind.HCALORY
        }
        return null
    }

    private companion object { const val TAG = "BleScanner" }
}
