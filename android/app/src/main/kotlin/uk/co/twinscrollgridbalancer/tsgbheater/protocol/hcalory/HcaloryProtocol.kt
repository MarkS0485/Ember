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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    // Tuya BLE devices drop idle clients after ~6s. The native HCalory app
    // never goes silent — it pumps device-info queries every 300ms in a
    // round-robin over a fixed subtype list. We mirror that: the keepalive
    // job is started right after CCCD subscription confirms and stopped
    // on disconnect.
    @Volatile private var keepaliveJob: Job? = null
    private val INFO_QUERY_SUBTYPES = byteArrayOf(
        0x00, 0x0A, 0x0B, 0x05, 0x07, 0x0D, 0x09, 0x0C, 0x04, 0x03, 0x02, 0x01, 0x06, 0x08
    )

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
        keepaliveJob?.cancel(); keepaliveJob = null
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

    // --- Post-connect handshake -------------------------------------
    //
    // Tuya BLE timeline:
    //   t=0       BLE connected, CCCD just subscribed
    //   t≈100ms   send timestamp sync (DP 0x0A0A)
    //   t≈300ms   send device-info query, subtype 0x00
    //   then every 300ms: send next subtype, cycle through INFO_QUERY_SUBTYPES
    //
    // Without this the heater drops the connection at ~6s. With it, we
    // both keep the link alive AND coax telemetry out — info-query
    // responses populate the DP stream that our notify handler decodes.

    private fun startPostConnectSequence() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            try {
                Log.i(TAG, "post-connect: sending timestamp sync")
                delay(100)
                val ts = buildTimestampSyncFrame()
                Log.i(TAG, "TX timestamp [${ts.size}B] ${ts.joinToString("") { "%02X".format(it) }}")
                val tsRes = writeRaw(ts)
                Log.i(TAG, "timestamp writeRaw -> $tsRes")

                var idx = 0
                while (true) {
                    delay(300)
                    val subtype = INFO_QUERY_SUBTYPES[idx % INFO_QUERY_SUBTYPES.size]
                    val r = writeRaw(buildInfoQueryFrame(subtype))
                    if (!r.isSuccess) {
                        Log.w(TAG, "info query subtype=0x${"%02X".format(subtype)} write FAILED: ${r.exceptionOrNull()?.message}")
                    } else if (idx < 3) {
                        Log.i(TAG, "info query subtype=0x${"%02X".format(subtype)} write ok")
                    }
                    idx++
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected on disconnect.
            } catch (t: Throwable) {
                Log.w(TAG, "post-connect sequence threw: ${t.message}")
            }
        }
    }

    // Build a literal-byte frame from the native HCalory app's wire format.
    // Verified end-to-end against the live heater from the Windows test
    // runner — the heater accepts these and the connection holds open
    // indefinitely. See OLDSRC/hcalory/POST_CONNECT_SEQUENCE.md and the
    // C# mirror at windows/src/TsgbHeater/Protocol/Hcalory/HcaloryProtocol.cs.
    //
    // Wire layout:
    //   bytes 0..6  : 7-byte fixed header `00 02 00 01 00 01 00`
    //   byte 7      : payload length (from byte 8 to end, inclusive of csum)
    //   bytes 8..N-2: payload (DP descriptor + value)
    //   byte  N-1   : checksum = (sum of bytes 8..N-2) & 0xFF
    //
    // Earlier code had three bugs (now fixed):
    //   1. Never computed/appended the checksum byte
    //   2. Info query had 18 zero bytes (was actually 8) — total 31B not 22B
    //   3. Timestamp used LE epoch — only correct on the FFF0 family.
    //      The BD39 family (which our heater uses) takes HH MM SS DOW.

    private fun buildTimestampSyncFrame(): ByteArray {
        // Layout (18 bytes):
        //   0..6   : 00 02 00 01 00 01 00       (7-byte fixed header)
        //   7,8    : 0A 0A                       (cmd / DP id)
        //   9..11  : 00 00 05                    (value descriptor: type=0, len=5)
        //  12..15  : HH MM SS DOW                (local time, ISO DOW)
        //  16     : 00                           (trailing pad)
        //  17     : checksum = sum(bytes 8..16) & 0xFF
        val cal = java.util.Calendar.getInstance()
        val hh = cal.get(java.util.Calendar.HOUR_OF_DAY).toByte()
        val mm = cal.get(java.util.Calendar.MINUTE).toByte()
        val ss = cal.get(java.util.Calendar.SECOND).toByte()
        // Java Calendar.DAY_OF_WEEK is Sun=1..Sat=7; HCalory wants ISO
        // Mon=1..Sun=7. Map.
        val javaDow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val dow = (if (javaDow == java.util.Calendar.SUNDAY) 7 else javaDow - 1).toByte()

        val out = ByteArray(18)
        out[0] = 0x00; out[1] = 0x02
        out[2] = 0x00; out[3] = 0x01; out[4] = 0x00; out[5] = 0x01; out[6] = 0x00
        out[7] = 0x0A; out[8] = 0x0A
        out[9] = 0x00
        out[10] = 0x00; out[11] = 0x05
        out[12] = hh; out[13] = mm; out[14] = ss; out[15] = dow
        out[16] = 0x00
        out[17] = checksumPayload(out)
        return out
    }

    // Info query template (22 bytes):
    //   00 02 00 01 00 01 00 0E 04 00 00 09 [8 x 00] [SUBTYPE] [csum]
    private fun buildInfoQueryFrame(subtype: Byte): ByteArray {
        val out = ByteArray(22)
        out[0] = 0x00; out[1] = 0x02
        out[2] = 0x00; out[3] = 0x01; out[4] = 0x00; out[5] = 0x01; out[6] = 0x00
        out[7] = 0x0E; out[8] = 0x04
        out[9] = 0x00
        out[10] = 0x00; out[11] = 0x09
        // bytes 12..19 stay 0 (8 zero bytes — NOT 18 as an earlier
        // analysis assumed; that was a miscounting of the native template)
        out[20] = subtype
        out[21] = checksumPayload(out)
        return out
    }

    // Sum of bytes 8..N-2 mod 256. Replicates j2.j.b.f() in the decompile.
    // The native wrapper a() / u() invokes this over the payload section,
    // not the fixed-header section.
    private fun checksumPayload(frame: ByteArray): Byte {
        var s = 0
        for (i in 8 until frame.size - 1) s = (s + (frame[i].toInt() and 0xFF)) and 0xFF
        return s.toByte()
    }

    /**
     * Test hook for the Test Cmd page (or any other protocol experiment):
     * pass the frame WITHOUT its trailing checksum byte; we'll compute and
     * append it and send. Mirrors the Windows-side SendRawTestFrameAsync.
     */
    suspend fun sendRawTestFrame(frameWithoutChecksum: ByteArray): Result<Unit> {
        val b = ByteArray(frameWithoutChecksum.size + 1)
        System.arraycopy(frameWithoutChecksum, 0, b, 0, frameWithoutChecksum.size)
        b[b.size - 1] = checksumPayload(b)
        Log.i(TAG, "TX testFrame [${b.size}B] ${b.joinToString("") { "%02X".format(it) }}")
        return writeRaw(b)
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
                    // Call directly on the callback thread. Wrapping in
                    // scope.launch (Dispatchers.IO) silently drops the
                    // request on some Android stacks — observed on a
                    // SM-A346B running A16: STATE_CONNECTED arrives,
                    // discoverServices() never produces a callback.
                    val ok = runCatching { g.discoverServices() }.getOrDefault(false)
                    Log.i(TAG, "discoverServices() returned $ok")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected (status=$status)")
                    keepaliveJob?.cancel(); keepaliveJob = null
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

            // Try legacy first (FFF0), fall back to custom (BD39).
            val (svc, wUuid, nUuid) =
                g.getService(SERVICE_LEGACY)?.let { Triple(it, WRITE_CHAR_LEGACY, NOTIFY_CHAR_LEGACY) }
                    ?: g.getService(SERVICE_CUSTOM)?.let { Triple(it, WRITE_CHAR_CUSTOM, NOTIFY_CHAR_CUSTOM) }
                    ?: run {
                        Log.w(TAG, "neither Tuya service found")
                        return
                    }

            writeChar  = svc.getCharacteristic(wUuid)
            notifyChar = svc.getCharacteristic(nUuid)
            if (notifyChar == null) {
                Log.w(TAG, "notify char $nUuid missing")
                return
            }

            // Negotiate a larger MTU before doing the CCCD dance. Default
            // BLE MTU is 23 bytes (20 byte payload), which is too small
            // for our 33-byte device-info query frames — silently dropped.
            // 247 is what most Tuya devices accept; we'll get the actual
            // value back in onMtuChanged and continue from there.
            val mtuReq = g.requestMtu(247)
            Log.i(TAG, "requestMtu(247) queued=$mtuReq")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            // Proceed to CCCD subscribe whether MTU negotiation succeeded
            // or not — at worst we're back to default and queries fail,
            // which we'll see in onDescriptorWrite + the 6s timeout cycle.
            val nc = notifyChar ?: return
            g.setCharacteristicNotification(nc, true)
            val cccd = nc.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "notify char has no CCCD descriptor — cannot subscribe")
                return
            }
            val cccdOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            Log.i(TAG, "CCCD writeDescriptor queued=$cccdOk — waiting for onDescriptorWrite")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "onDescriptorWrite uuid=${d.uuid} status=$status")
            if (d.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD subscribe failed status=$status")
                return
            }
            _isConnected.value = true
            // Now we're truly ready. Start the post-connect sequence —
            // timestamp sync then the device-info query merry-go-round
            // (every 300ms) so the heater never sees us idle.
            startPostConnectSequence()
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
        Log.i(TAG, "RX [${bytes.size}B] ${bytes.joinToString("") { "%02X".format(it) }}")
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
