package uk.co.twinscrollgridbalancer.tsgbheater.protocol.hcalory

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.HeaterCapabilities
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.IHeaterProtocol
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// HCalory driver. Owns its own BluetoothGatt — separate from HeatGenie's
// HeaterConnection because the GATT profile, write semantics and frame
// codec are all different.
//
// GATT profile: legacy (FFF0/FFF1/FFF2) or custom (BD39/BDF8/BDF7). We
// try the legacy one first because that's what most HCalory units in
// the wild seem to advertise; fall back to custom if not present.
// Write type: WRITE_TYPE_NO_RESPONSE (1) — fire-and-forget.
//
// Frame format: see TuyaBleFrame.kt. DP catalog (which id maps to
// which heater function) is captured in [Dpc] below — derived from
// the decompiled HCalory app, refined against the live heater later.
@SuppressLint("MissingPermission")
class HcaloryProtocol(private val ctx: Context) : IHeaterProtocol {

    override val kind         = ProtocolKind.HCALORY
    override val capabilities = HeaterCapabilities.HCALORY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _telemetry = MutableStateFlow(CommonTelemetry.Empty)
    override val telemetry: StateFlow<CommonTelemetry> = _telemetry.asStateFlow()

    private val _rawFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val rawFrames: Flow<ByteArray> = _rawFrames.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar:  BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var currentMac: String? = null
    private val seq = AtomicInteger(0)

    // --- Lifecycle -------------------------------------------------

    override suspend fun connect(mac: String): Result<Unit> = runCatching {
        // Idempotent: if we're already connected (or connecting) to this
        // MAC, no-op. If we were on a different MAC, tear down first.
        if (gatt != null) {
            if (currentMac == mac) return@runCatching
            disconnect()
        }
        currentMac = mac
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: error("BluetoothManager unavailable")
        val adapter = mgr.adapter ?: error("BLE adapter unavailable")
        val device: BluetoothDevice = adapter.getRemoteDevice(mac)
        Log.i(TAG, "connecting to $mac")
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, /*autoConnect*/ false, gattCallback,
                BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, gattCallback)
        }
        gatt = g
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        Log.i(TAG, "disconnect requested")
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        writeChar = null
        notifyChar = null
        currentMac = null
        _isConnected.value = false
    }

    // --- IHeaterProtocol: commands ---------------------------------

    override suspend fun start(): Result<Unit> =
        writeDp(TuyaBleFrame.dpEnum(Dpc.MODE, Dpc.MODE_AUTO))

    override suspend fun stop(): Result<Unit> =
        writeDp(TuyaBleFrame.dpEnum(Dpc.MODE, Dpc.MODE_STANDBY))

    override suspend fun vent(): Result<Unit> =
        writeDp(TuyaBleFrame.dpEnum(Dpc.MODE, Dpc.MODE_WIND))

    override suspend fun setTargetC(c: Int): Result<Unit> =
        writeDp(TuyaBleFrame.dpValue(Dpc.TARGET_TEMP, c))

    override suspend fun setGear(gear: Int): Result<Unit> {
        // HCalory: gear lives behind MANUAL mode. Two writes: switch
        // mode then set the value. The catalog notes setTargetC's DP
        // may double as gear when in manual mode — confirm against
        // real device, refine if wrong.
        writeDp(TuyaBleFrame.dpEnum(Dpc.MODE, Dpc.MODE_MANUAL))
            .onFailure { return Result.failure(it) }
        return writeDp(TuyaBleFrame.dpValue(Dpc.TARGET_TEMP, gear))
    }

    override suspend fun setAltitudeM(metres: Int): Result<Unit> =
        writeDp(TuyaBleFrame.dpValue(Dpc.ALTITUDE, metres))

    override suspend fun pulsePump(seconds: Int): Result<Unit> =
        Result.failure(NotImplementedError("HCalory has no manual-pump command"))

    override suspend fun sendRaw(opcode: String, payload: ByteArray): Result<Unit> = runCatching {
        // For HCalory, [opcode] is the DP id as hex (e.g. "05"); the
        // type is implied by payload length unless overridden via the
        // first two hex chars of opcode. Stays a thin pass-through —
        // the Test Cmd page uses this for protocol experiments.
        val dpid = opcode.takeLast(2).toInt(16)
        val frame = TuyaBleFrame.encodeSingleDp(seq.getAndIncrement() and 0xFF,
            TuyaBleFrame.Dp(dpid, TuyaBleFrame.DP_TYPE_RAW, payload))
        writeRaw(frame).getOrThrow()
    }

    // --- DP write helper -------------------------------------------

    private suspend fun writeDp(dp: TuyaBleFrame.Dp): Result<Unit> {
        val frame = TuyaBleFrame.encodeSingleDp(seq.getAndIncrement() and 0xFF, dp)
        return writeRaw(frame)
    }

    private suspend fun writeRaw(bytes: ByteArray): Result<Unit> = runCatching {
        val g  = gatt ?: error("not connected")
        val ch = writeChar ?: error("write characteristic not ready")
        _rawFrames.tryEmit(bytes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val r = g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (r != BluetoothGatt.GATT_SUCCESS) error("writeCharacteristic returned $r")
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(ch)) error("legacy writeCharacteristic returned false")
        }
    }

    // --- GATT callback ---------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    scope.launch { runCatching { g.discoverServices() } }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _isConnected.value = false
                    writeChar = null
                    notifyChar = null
                    runCatching { g.close() }
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Try legacy first (FFF0), fall back to custom (BD39). The HCalory
            // unit advertises one or the other but not both, in our experience.
            val (svc, wUuid, nUuid) =
                g.getService(SERVICE_LEGACY)?.let { Triple(it, WRITE_CHAR_LEGACY, NOTIFY_CHAR_LEGACY) }
                    ?: g.getService(SERVICE_CUSTOM)?.let { Triple(it, WRITE_CHAR_CUSTOM, NOTIFY_CHAR_CUSTOM) }
                    ?: run {
                        Log.w(TAG, "neither Tuya service found")
                        return
                    }

            writeChar  = svc.getCharacteristic(wUuid)
            notifyChar = svc.getCharacteristic(nUuid)
            val nc = notifyChar
            if (nc == null) {
                Log.w(TAG, "notify char $nUuid missing")
                return
            }
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            // We consider ourselves "ready" only after the CCCD write lands.
            // For now, optimistically mark connected — refine if we observe
            // dropped writes early on.
            _isConnected.value = true
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            ingest(value)
        }

        // Legacy callback path (pre-Android 13)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ingest(ch.value)
        }
    }

    // --- Notify ingestion + DP → CommonTelemetry -------------------

    private fun ingest(bytes: ByteArray) {
        _rawFrames.tryEmit(bytes)
        val frame = TuyaBleFrame.decode(bytes) ?: return
        var t = _telemetry.value
        var changed = false
        for (dp in frame.dps) {
            val updated = applyDp(t, dp) ?: continue
            t = updated; changed = true
        }
        if (changed) {
            _telemetry.value = t.copy(updatedAtMs = System.currentTimeMillis())
        }
    }

    // Fold a single DP into the telemetry snapshot. Return null if the
    // DP is not one we currently understand — they're dropped silently
    // (and still visible in rawFrames for the debug box).
    private fun applyDp(t: CommonTelemetry, dp: TuyaBleFrame.Dp): CommonTelemetry? = when (dp.id) {
        Dpc.MODE -> if (dp.value.isNotEmpty()) {
            t.copy(mode = dpModeToCommon(dp.value[0].toInt() and 0xFF))
        } else null
        Dpc.TARGET_TEMP -> if (dp.value.size == 4) {
            t.copy(targetC = TuyaBleFrame.dpValueInt(dp).toDouble())
        } else null
        Dpc.TEMP_UNIT -> if (dp.value.isNotEmpty()) {
            t.copy(tempUnitF = (dp.value[0].toInt() and 0xFF) == 1)
        } else null
        Dpc.ALTITUDE -> if (dp.value.size == 4) {
            t.copy(altitudeM = TuyaBleFrame.dpValueInt(dp))
        } else null
        // Add more as we learn the catalog from the real heater.
        else -> null
    }

    private fun dpModeToCommon(v: Int): CommonRunningMode = when (v) {
        Dpc.MODE_STANDBY -> CommonRunningMode.STANDBY
        Dpc.MODE_AUTO,
        Dpc.MODE_MANUAL  -> CommonRunningMode.RUNNING
        Dpc.MODE_WIND    -> CommonRunningMode.VENT
        Dpc.MODE_FAULT   -> CommonRunningMode.FAULT
        else             -> CommonRunningMode.UNKNOWN
    }

    // --- DP catalog ------------------------------------------------
    //
    // Pulled from OLDSRC/hcalory/DP_CATALOG.md (decompile of the HCalory
    // Android app). Values marked "?" need confirming against a live
    // heater; the rest are high-confidence. See the catalog markdown
    // for source-line citations.
    private object Dpc {
        // Control surface — write
        const val MODE        = 0x01   // enum: see MODE_* below
        const val TEMP_UNIT   = 0x0B   // enum: 0=C, 1=F
        const val TARGET_TEMP = 0x05   // value (int)
        const val ALTITUDE    = 0x09   // value (4-byte int).  Real id is 0x0909
                                       // (two-byte DP id?) — single-byte 0x09 may
                                       // wrap the same telemetry; confirm on device.

        // Mode enum values (DP 0x01)
        const val MODE_STANDBY = 0x00
        const val MODE_AUTO    = 0x01
        const val MODE_MANUAL  = 0x02
        const val MODE_WIND    = 0x03
        const val MODE_FAULT   = 0x04
    }

    // --- GATT UUIDs (also re-exported for the scanner) -------------

    companion object {
        private const val TAG = "HcaloryProtocol"

        val SERVICE_LEGACY     = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_LEGACY  = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_LEGACY = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")

        val SERVICE_CUSTOM     = UUID.fromString("0000BD39-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_CUSTOM  = UUID.fromString("0000BDF7-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_CUSTOM = UUID.fromString("0000BDF8-0000-1000-8000-00805F9B34FB")

        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
