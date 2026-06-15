package com.emberheat.protocol.hcalory

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
import com.emberheat.diag.BleEventLog
import com.emberheat.protocol.CommonRunningMode
import com.emberheat.protocol.CommonTelemetry
import com.emberheat.protocol.HeaterCapabilities
import com.emberheat.protocol.IHeaterProtocol
import com.emberheat.protocol.ProtocolKind
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

    // BLE login PIN. The heater answers every pre-auth query with a 0x0C
    // "authenticate me" frame and drops idle/unauthed clients; it only
    // streams real telemetry (0x0E / 0x0D) once we send the 0x0C login.
    // The native app defaults to "0000" when no PIN is stored (h8/t.java).
    @Volatile var blePin: String = "0000"
    @Volatile private var authed = false
    @Volatile private var lastLoginSentMs = 0L

    // Echoed device state, captured from each 0x03 status frame so control
    // commands can replay the heater's current display unit / setpoint.
    @Volatile private var lastTempUnitF    = false
    @Volatile private var lastTargetDisp   = 0   // target temp in the heater's display unit

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
        BleEventLog.log("CONN", "connecting to $mac")
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, /*autoConnect*/ false, gattCallback,
                BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, gattCallback)
        }
        gatt = g
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        BleEventLog.log("CONN", "disconnect requested")
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
    //   t≈100ms   send timestamp sync (DP 0x0A)
    //   t≈250ms   send 0x0C PIN login (auth)
    //   then every 1s: poll the device-status query (subtype 0x00)
    //
    // Without this the heater drops the connection at ~6-10s. With it, we
    // both keep the link alive AND coax telemetry out — info-query
    // responses populate the DP stream that our notify handler decodes.

    private fun startPostConnectSequence() {
        keepaliveJob?.cancel()
        authed = false
        lastLoginSentMs = 0L
        keepaliveJob = scope.launch {
            try {
                BleEventLog.log("CONN", "post-connect: timestamp sync")
                delay(100)
                writeRaw(buildTimestampSyncFrame())

                // Authenticate. Until the heater accepts the PIN it answers
                // every query with a 0x0C auth-request and will not stream
                // telemetry. ingest() resends this on a 0x0C result=00.
                delay(150)
                sendLogin()

                // Steady state: poll the device-status query (subtype 0x00)
                // ~1 Hz, mirroring the native app's main-screen keepalive
                // (z8.o.d() -> k2.e.f()). Spraying all 14 subtypes made the
                // heater answer several of them with 0x03 frames of DIFFERENT
                // meaning, which x() would misparse; subtype 0x00 alone yields
                // the canonical device-status frame. Pre-auth the heater
                // answers 0x0C (ingest resends the login); post-auth it
                // answers with the 0x03 status frame we decode.
                while (true) {
                    delay(1000)
                    // writeRaw logs the TX + result; no extra logging here so
                    // the 1 Hz poll doesn't drown the diagnostic log.
                    writeRaw(buildInfoQueryFrame(0x00.toByte()))
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected on disconnect.
            } catch (t: Throwable) {
                BleEventLog.warn("CONN", "post-connect sequence threw: ${t.message}")
            }
        }
    }

    // Build a literal-byte frame from the native HCalory app's wire format.
    // Verified end-to-end against the live heater from the Windows test
    // runner — the heater accepts these and the connection holds open
    // indefinitely. See OLDSRC/hcalory/POST_CONNECT_SEQUENCE.md and the
    // C# mirror at windows/src/Ember/Protocol/Hcalory/HcaloryProtocol.cs.
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

    // Login / authentication frame (k2.e.a() in the decompile):
    //   00 02 00 01 00 01 00 | 0A | 0C 00 00 05 | 01 <p0> <p1> <p2> <p3> | csum
    // DP id 0x0C, type 0x00, value = [0x01 (login opcode)] + 4 PIN digits,
    // one digit per byte (PIN "0000" -> 00 00 00 00). The heater replies
    // with another 0x0C carrying result: 00=awaiting, 01=success, 02=wrong.
    private fun buildLoginFrame(pin: String): ByteArray {
        val digits = pin.padStart(4, '0').takeLast(4)
        val out = ByteArray(18)
        out[0] = 0x00; out[1] = 0x02
        out[2] = 0x00; out[3] = 0x01; out[4] = 0x00; out[5] = 0x01; out[6] = 0x00
        out[7] = 0x0A
        out[8] = 0x0C            // dpId = auth
        out[9] = 0x00            // dpType
        out[10] = 0x00; out[11] = 0x05   // value length = 5
        out[12] = 0x01           // login opcode
        for (i in 0..3) out[13 + i] = (digits[i] - '0').toByte()
        out[17] = checksumPayload(out)
        return out
    }

    private suspend fun sendLogin() {
        lastLoginSentMs = System.currentTimeMillis()
        BleEventLog.log("AUTH", "sending login (pin=$blePin)")
        writeRaw(buildLoginFrame(blePin))
    }

    private fun unitWire(): Int = if (lastTempUnitF) 1 else 0

    // Local clock as [HH, MM, SS, DOW] — the 4-byte value the native mode
    // builders embed (ISO DOW: Mon=1..Sun=7). Same source as the timestamp
    // sync the heater already accepts.
    private fun clockBytes(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        val javaDow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val dow = if (javaDow == java.util.Calendar.SUNDAY) 7 else javaDow - 1
        return byteArrayOf(
            cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            cal.get(java.util.Calendar.MINUTE).toByte(),
            cal.get(java.util.Calendar.SECOND).toByte(),
            dow.toByte(),
        )
    }

    // Complex-pipeline frame, matching the native r()/d()/t()/u() builders in
    // k2.e (used by all mode switches). The header flag byte (offset 5) is 0x01
    // for auto/standby, 0x00 for wind/manual; `value` excludes the trailing
    // placeholder (u() overwrites it with the payload checksum).
    // Layout: 00 02 00 01 00 <flag> <payloadLenBE> | <dpId> 00 <valLenBE> | value | csum
    private fun buildComplexFrame(flag: Int, dpId: Int, value: ByteArray): ByteArray {
        val out = ByteArray(8 + 4 + value.size + 1)
        val payloadLen = out.size - 8
        out[0] = 0x00; out[1] = 0x02; out[2] = 0x00; out[3] = 0x01; out[4] = 0x00
        out[5] = (flag and 0xFF).toByte()
        out[6] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[7] = (payloadLen and 0xFF).toByte()
        out[8] = (dpId and 0xFF).toByte()
        out[9] = 0x00
        out[10] = ((value.size ushr 8) and 0xFF).toByte()
        out[11] = (value.size and 0xFF).toByte()
        value.copyInto(out, 12)
        out[out.size - 1] = checksumPayload(out)
        return out
    }

    /**
     * Test hook for the Test Cmd page (or any other protocol experiment):
     * pass the frame WITHOUT its trailing checksum byte; we'll compute and
     * append it and send. Mirrors the Windows-side SendRawTestFrameAsync.
     */
    // Debug: send the bytes EXACTLY as given (no checksum recompute). For
    // probing frames where we want full control of every byte.
    suspend fun debugSendExact(bytes: ByteArray): Result<Unit> {
        BleEventLog.log("DBG", "exact [${bytes.size}B] ${bytes.joinToString("") { "%02X".format(it) }}")
        return writeRaw(bytes)
    }

    suspend fun sendRawTestFrame(frameWithoutChecksum: ByteArray): Result<Unit> {
        val b = ByteArray(frameWithoutChecksum.size + 1)
        System.arraycopy(frameWithoutChecksum, 0, b, 0, frameWithoutChecksum.size)
        b[b.size - 1] = checksumPayload(b)
        Log.i(TAG, "TX testFrame [${b.size}B] ${b.joinToString("") { "%02X".format(it) }}")
        return writeRaw(b)
    }

    // --- IHeaterProtocol: commands ---------------------------------

    // POWER / MODE via DP 0x08.
    //
    // Earlier attempt used DP 0x05 complex frames (native e.b()/e.p()). Those
    // were ACKed but did nothing on the wire (2026-05-30 test) — because DP
    // 0x05 is the SCHEDULE/scene-data channel (e.K() schedule also uses 0x05),
    // not a mode control. Sending a bare clock to it is a no-op the heater just
    // echoes.
    //
    // The working control DPs are the low "real" ids: temp 0x06 (confirmed),
    // gear 0x07 (confirmed). The one id between those and altitude 0x09 is
    // DP 0x08 — native e.k() builds exactly `00 02 00 01 00 01 00 0608 00 00 01
    // 00` (real dpId 0x08, type 00, 1-byte value). That's the power/mode
    // command; the old code only ever tried it with the broken framing.
    //
    // Probing the value byte: start=0x01, stop=0x00 (the value native e.k()
    // always sends), vent=0x02. If 0x08 turns out to be a momentary toggle
    // (single value 00), the log will show it and we collapse to one button.
    private suspend fun powerDp(value: Int, label: String): Result<Unit> {
        BleEventLog.log("BTN", "$label -> DP08 value=0x%02X".format(value))
        return writeDp(TuyaBleFrame.Dp(0x08, TuyaBleFrame.DP_TYPE_RAW,
            byteArrayOf((value and 0xFF).toByte())))
    }

    override suspend fun start(): Result<Unit> = powerDp(0x01, "START HEAT")
    override suspend fun stop():  Result<Unit> = powerDp(0x00, "STOP HEAT")
    override suspend fun vent():  Result<Unit> = powerDp(0x02, "BLOWER ONLY")

    // Set target temperature — native J(temp, unit): standalone DP 0x06,
    // value = [temp-in-display-unit, unit]. CONFIRMED reaching the heater.
    override suspend fun setTargetC(c: Int): Result<Unit> {
        val disp = (if (lastTempUnitF) Math.round(c * 9.0 / 5.0 + 32).toInt() else c).coerceIn(0, 255)
        BleEventLog.log("BTN", "SET TARGET ${c}C -> disp=$disp unit=${unitWire()} (DP06)")
        return writeDp(TuyaBleFrame.Dp(0x06, TuyaBleFrame.DP_TYPE_RAW,
            byteArrayOf(disp.toByte(), unitWire().toByte())))
    }

    // Manual gear — native I(): standalone DP 0x07, single-byte gear value.
    // CONFIRMED on the wire (drove STATE set=1..10).
    override suspend fun setGear(gear: Int): Result<Unit> {
        BleEventLog.log("BTN", "SET GEAR $gear (DP07)")
        return writeDp(TuyaBleFrame.Dp(0x07, TuyaBleFrame.DP_TYPE_RAW, byteArrayOf((gear and 0xFF).toByte())))
    }

    // Altitude — native H(): DP 0x09, value = <sign> <|metres|_BE16> <unit:0=m>.
    override suspend fun setAltitudeM(metres: Int): Result<Unit> {
        val abs = kotlin.math.abs(metres)
        val value = byteArrayOf(
            (if (metres < 0) 1 else 0).toByte(),
            ((abs ushr 8) and 0xFF).toByte(),
            (abs and 0xFF).toByte(),
            0x00,
        )
        return writeDp(TuyaBleFrame.Dp(0x09, TuyaBleFrame.DP_TYPE_RAW, value))
    }

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

    private suspend fun writeRaw(bytes: ByteArray): Result<Unit> {
        val hex = bytes.joinToString("") { "%02X".format(it) }
        val result = runCatching {
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
        // Dedupe the 1 Hz status-poll keepalive (identical bytes every second)
        // so it doesn't drown the log; failures and distinct frames (commands,
        // login, timestamp) always log.
        val isRepeat = hex == lastTxHex
        lastTxHex = hex
        if (!result.isSuccess) {
            BleEventLog.warn("TX", "[${bytes.size}B] $hex -> FAIL: ${result.exceptionOrNull()?.message}")
        } else if (!isRepeat) {
            BleEventLog.log("TX", "[${bytes.size}B] $hex -> ok")
        }
        return result
    }

    @Volatile private var lastTxHex: String? = null

    // --- GATT callback ---------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    BleEventLog.log("CONN", "connected (gatt status=$status) — discovering services")
                    // Call directly on the callback thread. Wrapping in
                    // scope.launch (Dispatchers.IO) silently drops the
                    // request on some Android stacks — observed on a
                    // SM-A346B running A16: STATE_CONNECTED arrives,
                    // discoverServices() never produces a callback.
                    val ok = runCatching { g.discoverServices() }.getOrDefault(false)
                    if (!ok) BleEventLog.warn("CONN", "discoverServices() returned false")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // status 8 = supervision timeout (out of range / idle),
                    // 19 = peer terminated, 0 = clean. Surfaced so a command
                    // that drops the link is obvious in the log.
                    BleEventLog.warn("CONN", "DISCONNECTED (gatt status=$status)")
                    keepaliveJob?.cancel(); keepaliveJob = null
                    _isConnected.value = false
                    authed = false
                    lastRawHex = null
                    lastStateSummary = null
                    lastTxHex = null
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
            BleEventLog.log("CONN", "MTU=$mtu (status=$status) — subscribing notifications")
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

    // Dedupe state for logging: the heater streams an identical status frame
    // ~1 Hz in steady state, which would flood the log. We log a raw RX line
    // only when the bytes change, and a decoded STATE line only when the
    // human-readable summary changes — so a button that DOES nothing leaves
    // the STATE unchanged (itself the diagnostic signal), and one that works
    // shows up as a fresh STATE line right after the BTN/TX pair.
    @Volatile private var lastRawHex: String? = null
    @Volatile private var lastStateSummary: String? = null

    private fun ingest(bytes: ByteArray) {
        val hex = bytes.joinToString("") { "%02X".format(it) }
        _rawFrames.tryEmit(bytes)
        val isRepeat = hex == lastRawHex
        lastRawHex = hex
        if (!isRepeat) BleEventLog.log("RX", "[${bytes.size}B] $hex")

        val frame = TuyaBleFrame.decode(bytes) ?: run {
            if (!isRepeat) BleEventLog.warn("RX", "decode FAILED (bad checksum/length?) raw=$hex")
            return
        }
        var t = _telemetry.value
        var changed = false
        for (dp in frame.dps) {
            when (dp.id) {
                Dpc.AUTH -> { handleAuthDp(dp); continue }
                Dpc.STATUS -> {
                    val summary = statusSummary(dp.value)
                    if (summary != lastStateSummary) {
                        lastStateSummary = summary
                        BleEventLog.log("STATE", summary)
                    }
                }
            }
            val updated = applyDp(t, dp) ?: continue
            t = updated; changed = true
        }
        if (changed) {
            _telemetry.value = t.copy(updatedAtMs = System.currentTimeMillis())
        }
    }

    // Human-readable one-liner from a DP 0x03 device-status value, for the
    // diagnostic log. Mirrors the fields k2.e.x() pulls out, plus the
    // fan/plug/pump bits that don't survive into CommonTelemetry.
    private fun statusSummary(v: ByteArray): String {
        if (v.size < 26) return "status(short ${v.size}B)"
        fun u16(i: Int) = ((v[i].toInt() and 0xFF) shl 8) or (v[i + 1].toInt() and 0xFF)
        val sb         = v[8].toInt() and 0xFF
        val dev        = v[9].toInt() and 0xFF
        val tempOrGear = v[10].toInt() and 0xFF
        val volt       = u16(12) / 10.0
        val unitF      = (v[25].toInt() and 0xFF) == 0x01
        val fan  = if (sb and 0x01 != 0) 1 else 0
        val plug = if (sb and 0x02 != 0) 1 else 0
        val pump = if (sb and 0x04 != 0) 1 else 0
        val devName = when (dev) {
            0x00 -> "STANDBY"; 0x01 -> "AUTO-TEMP"; 0x02 -> "MANUAL-GEAR"; 0x03 -> "WIND"
            else -> "0x%02X".format(dev)
        }
        val faultStr = if (sb == 0xFF) " FAULT(E%02d)".format(dev) else ""
        return "dev=%s set=%d V=%.1f fan=%d plug=%d pump=%d unit=%s%s"
            .format(devName, tempOrGear, volt, fan, plug, pump, if (unitF) "F" else "C", faultStr)
    }

    // 0x0C auth-response handler. value = [opCode][..][..][..][..][result].
    // result: 0x00 awaiting PIN, 0x01 success, 0x02 wrong PIN.
    private fun handleAuthDp(dp: TuyaBleFrame.Dp) {
        val result = if (dp.value.size >= 6) dp.value[5].toInt() and 0xFF else -1
        when (result) {
            0x01 -> if (!authed) { authed = true; BleEventLog.log("AUTH", "OK (pin=$blePin) — telemetry should now flow") }
            0x02 -> BleEventLog.error("AUTH", "FAILED: wrong PIN ($blePin). Heater has a non-default BLE password.")
            0x00 -> {
                // Still unauthenticated — resend the login, rate-limited.
                if (!authed && System.currentTimeMillis() - lastLoginSentMs > 2500) {
                    BleEventLog.log("AUTH", "awaiting PIN — resending login")
                    scope.launch { runCatching { sendLogin() } }
                }
            }
            else -> BleEventLog.log("AUTH", "0x0C result=$result value=${dp.value.joinToString("") { "%02X".format(it) }}")
        }
    }

    // Fold a single DP into the telemetry snapshot. Return null if the
    // DP is not one we currently understand — they're dropped silently
    // (and still visible in rawFrames for the debug box).
    private fun applyDp(t: CommonTelemetry, dp: TuyaBleFrame.Dp): CommonTelemetry? = when (dp.id) {
        Dpc.STATUS -> parseDeviceStatus03(t, dp.value)
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

    // Faithful port of k2.e.x() — decodes the DP 0x03 device-status payload.
    // Offsets are into the DP value (v[0] = first value byte). Temperatures
    // are reported in the heater's CURRENT display unit (v[25]); we convert
    // to Celsius for CommonTelemetry, which is canonical °C.
    private fun parseDeviceStatus03(base: CommonTelemetry, v: ByteArray): CommonTelemetry? {
        if (v.size < 26) return null
        fun u16(i: Int) = ((v[i].toInt() and 0xFF) shl 8) or (v[i + 1].toInt() and 0xFF)

        val statusByte     = v[8].toInt() and 0xFF      // bit-packed: fan/plug/pump + op-state; 0xFF = fault
        val deviceStateRaw = v[9].toInt() and 0xFF      // 00 standby,01 auto-temp,02 manual-gear,03 wind
        val tempOrGear     = v[10].toInt() and 0xFF
        val voltage        = u16(12) / 10.0
        val shellRaw       = u16(15)
        val ambRaw         = u16(18)
        val shellDisp      = (if ((v[14].toInt() and 0xFF) == 0x01 && shellRaw != 0) -shellRaw else shellRaw) / 10.0
        val ambDisp        = (if ((v[17].toInt() and 0xFF) == 0x01 && ambRaw != 0) -ambRaw else ambRaw) / 10.0
        val tempUnitF      = (v[25].toInt() and 0xFF) == 0x01
        val altitude       = (((v[20].toInt() and 0xFF) shl 16) or
                              ((v[21].toInt() and 0xFF) shl 8) or
                               (v[22].toInt() and 0xFF)) / 10

        val fault   = statusByte == 0xFF
        // operative state from bits 6,7 of the status byte (k2.e.x reverses
        // the 8-bit string then reads chars 6,7): op = (bit6<<1)|bit7.
        val opState = (((statusByte ushr 6) and 0x01) shl 1) or ((statusByte ushr 7) and 0x01)

        val mode = when {
            fault                                       -> CommonRunningMode.FAULT
            opState == 3 || deviceStateRaw == 0x03      -> CommonRunningMode.VENT
            opState == 1                                -> CommonRunningMode.RUNNING
            opState == 2                                -> CommonRunningMode.SHUTDOWN  // cooling engine body
            deviceStateRaw == 0x01 || deviceStateRaw == 0x02 -> CommonRunningMode.RUNNING
            else                                        -> CommonRunningMode.STANDBY
        }

        fun toC(disp: Double) = if (tempUnitF) (disp - 32.0) * 5.0 / 9.0 else disp

        val isGearMode = deviceStateRaw == 0x02
        val errorNum   = if (fault) deviceStateRaw else null

        // Cache state for command replay (display-unit + setpoint).
        lastTempUnitF  = tempUnitF
        lastTargetDisp = tempOrGear

        return base.copy(
            mode      = mode,
            modeLabel = errorNum?.let { "Fault E%02d".format(it) },
            ambientC  = toC(ambDisp),
            housingC  = toC(shellDisp),
            targetC   = if (!isGearMode && tempOrGear > 0) toC(tempOrGear.toDouble()) else base.targetC,
            aimGear   = if (isGearMode) tempOrGear else base.aimGear,
            batteryV  = voltage,
            altitudeM = altitude,
            tempUnitF = tempUnitF,
            faultBits = errorNum,
        )
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
        const val AUTH        = 0x0C   // login/auth request+response (PIN)
        const val STATUS      = 0x03   // device-status push/response (k2.e.x payload)

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
