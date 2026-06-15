package com.emberheat.ble

import kotlin.random.Random

// Frame builder/parser for HeatGenie's BLE protocol. See
// docs/BLE_PROTOCOL.md for line-by-line citations into the decompiled JS.
//
// Wire format (8-byte heater frame):
//   AA  len  op  d0  d1  d2  crcH  crcL    (CRC-16/XMODEM over bytes 0..5)
//
// Telemetry response variant (longer):
//   AA  len  op  d0  d1  d2  d3  tag  <payload, 4*(len+1) bytes>  crcH crcL
//
// Where tag at byte 7 selects an "info area":
//   0xF1 = version, 0xF2 = regInfo (live status), 0xF3 = paraInfo,
//   0xF5 = timerInfo.
object FrameCodec {

    private const val SYNC: Byte = 0xAA.toByte()

    // Heater-side opcodes (DB0_DN_*).
    private const val OP_CMD             = 0x61   // button events (uses random nonce in d1)
    private const val OP_MANU_PUMP       = 0x62   // timed manual pump (seconds in d0)
    private const val OP_AUTO_UPDATE     = 0x65   // start/stop periodic telemetry
    private const val OP_SHORT_PARA      = 0x66   // set a single setting
    private const val OP_GET_REG_VAL     = 0x64   // one-shot read of an info area
    private const val OP_WRITE_AREA      = 0xFF   // long-frame write to a whole info area

    // CMD_* values used as d0 of DB0_DN_CMD.
    private const val CMD_ON              = 1
    private const val CMD_OFF             = 2
    private const val CMD_UP              = 3
    private const val CMD_DOWN            = 4
    private const val CMD_SWITCH_TEMP_FC  = 8
    private const val CMD_BLOW_ON         = 9
    private const val CMD_SWITCH_TEMP_CF  = 10
    private const val CMD_OIL_PUMP_ON     = 11
    private const val CMD_OIL_PUMP_OFF    = 12

    // SHORT_* values used as d0 of DB0_DN_SHORT_PARA.
    private const val SHORT_RUN_MODE      = 0x00
    private const val SHORT_TARGET_TEMP   = 0x01
    private const val SHORT_TARGET_GEAR   = 0x02
    private const val SHORT_TIMER_ADJUST  = 0x03   // overloaded — sets wall clock (day+hour+minute)
    private const val SHORT_TEMP_DIFF     = 0x04

    // Area tags.
    const val AREA_VERSION  = 0xF1
    const val AREA_REG_INFO = 0xF2
    const val AREA_PARA     = 0xF3
    const val AREA_TIMER    = 0xF5

    // Temperature units used as d1 of SHORT_TARGET_TEMP / SHORT_TEMP_DIFF.
    enum class TempUnit(val wire: Int) { Celsius(0), Fahrenheit(1) }

    enum class RunMode(val wire: Int) { Auto(0), Manual(1), StartStop(2) }

    // --- Outgoing command builders -------------------------------------

    fun buildStartHeater(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_ON.toByte(),  randomNonce(), 0))

    fun buildStopHeater(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_OFF.toByte(), randomNonce(), 0))

    fun buildButtonUp(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_UP.toByte(),   randomNonce(), 0))

    fun buildButtonDown(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_DOWN.toByte(), randomNonce(), 0))

    fun buildBlowOn(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_BLOW_ON.toByte(),     randomNonce(), 0))

    fun buildOilPumpOn(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_OIL_PUMP_ON.toByte(), randomNonce(), 0))

    fun buildOilPumpOff(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_OIL_PUMP_OFF.toByte(), randomNonce(), 0))

    // Timed manual pump (DB0_DN_MANU_PUMP). The CMD_OIL_PUMP_ON button just
    // wakes the pump; the controller still needs a duration via this opcode,
    // otherwise it ignores the request. Vendor app clamps to 20..120 s.
    fun buildManualPumpRun(seconds: Int): ByteArray {
        val clamped = seconds.coerceIn(MANUAL_PUMP_MIN_S, MANUAL_PUMP_MAX_S)
        return eightByteCmd(OP_MANU_PUMP, byteArrayOf(clamped.toByte(), randomNonce(), 0))
    }

    const val MANUAL_PUMP_MIN_S = 5
    const val MANUAL_PUMP_MAX_S = 90
    const val MANUAL_PUMP_DEFAULT_S = 20

    fun buildSwitchToCelsius(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_SWITCH_TEMP_FC.toByte(), randomNonce(), 0))

    fun buildSwitchToFahrenheit(): ByteArray =
        eightByteCmd(OP_CMD, byteArrayOf(CMD_SWITCH_TEMP_CF.toByte(), randomNonce(), 0))

    fun buildSetTargetTemp(value: Int, unit: TempUnit = TempUnit.Celsius): ByteArray =
        eightByteCmd(OP_SHORT_PARA, byteArrayOf(
            SHORT_TARGET_TEMP.toByte(),
            unit.wire.toByte(),
            (value and 0xFF).toByte(),
        ))

    fun buildSetGear(gear: Int): ByteArray =
        eightByteCmd(OP_SHORT_PARA, byteArrayOf(
            SHORT_TARGET_GEAR.toByte(),
            0,
            (gear and 0xFF).toByte(),
        ))

    fun buildSetRunMode(mode: RunMode): ByteArray =
        eightByteCmd(OP_SHORT_PARA, byteArrayOf(
            SHORT_RUN_MODE.toByte(),
            0,
            mode.wire.toByte(),
        ))

    fun buildSetTempHysteresis(diff: Int, unit: TempUnit = TempUnit.Celsius): ByteArray =
        eightByteCmd(OP_SHORT_PARA, byteArrayOf(
            SHORT_TEMP_DIFF.toByte(),
            unit.wire.toByte(),
            (diff and 0xFF).toByte(),
        ))

    // Experimental: send an arbitrary SHORT_PARA frame. Vendor uses indices
    // 0..4 (RunMode/TargetTemp/TargetGear/TimerAdjust/TempDiffAdjust). Higher
    // indices are unmapped — used by the altitude probe to look for a
    // hidden setter. Returns the encoded frame so the caller can render it
    // for the user to verify on the wire.
    fun buildShortParaProbe(index: Int, d1: Int, d2: Int): ByteArray =
        eightByteCmd(OP_SHORT_PARA, byteArrayOf(
            (index and 0xFF).toByte(),
            (d1 and 0xFF).toByte(),
            (d2 and 0xFF).toByte(),
        ))

    // Set the heater's wall clock — day-of-week + hour + minute. The
    // controller doesn't store a date, just where it sits in the weekly
    // schedule. Vendor uses Mon=0..Sun=6; the ISO Calendar.MONDAY=2 mapping
    // is handled here so callers can pass any Calendar/LocalDateTime.
    fun buildSetClock(
        vendorDayMonZero: Int,   // 0=Mon..6=Sun (see calendarToVendorDay)
        hour: Int,               // 0..23
        minute: Int,             // 0..59
    ): ByteArray {
        val day = vendorDayMonZero.coerceIn(0, 6)
        val h   = hour.coerceIn(0, 31)        // 5-bit field
        val m   = minute.coerceIn(0, 59)
        val packed = (day shl 5) or h
        return eightByteCmd(OP_SHORT_PARA, byteArrayOf(
            SHORT_TIMER_ADJUST.toByte(),
            packed.toByte(),
            m.toByte(),
        ))
    }

    // Convert a Calendar's DAY_OF_WEEK (Sun=1..Sat=7) to the vendor's
    // index (Mon=0..Sun=6). Mirrors the JS `i&=7; if(i==0)i=6 else i-=1`
    // with the only difference that JS uses Sun=0 while Calendar uses Sun=1.
    fun calendarDayOfWeekToVendor(calendarDow: Int): Int = when (calendarDow) {
        java.util.Calendar.MONDAY    -> 0
        java.util.Calendar.TUESDAY   -> 1
        java.util.Calendar.WEDNESDAY -> 2
        java.util.Calendar.THURSDAY  -> 3
        java.util.Calendar.FRIDAY    -> 4
        java.util.Calendar.SATURDAY  -> 5
        java.util.Calendar.SUNDAY    -> 6
        else                         -> 0
    }

    // Start the periodic telemetry stream. The vendor app sends (0x0A, 0x00)
    // for ~1 Hz when a page opens; we follow that convention.
    fun buildStartTelemetryStream(): ByteArray =
        eightByteCmd(OP_AUTO_UPDATE, byteArrayOf(0x02, 0x0A, 0x00))

    fun buildStopTelemetryStream(): ByteArray =
        eightByteCmd(OP_AUTO_UPDATE, byteArrayOf(0x02, 0x00, 0x1E))

    fun buildReadRegInfo(): ByteArray =
        eightByteCmd(OP_GET_REG_VAL, byteArrayOf(AREA_REG_INFO.toByte(), 0x00, 0x05))

    // Read the 7-day timer schedule (area 0xF5). Reply arrives as the longer
    // telemetry response variant with tag 0xF5; pass through parseTimerInfo.
    fun buildReadTimerInfo(): ByteArray =
        eightByteCmd(OP_GET_REG_VAL, byteArrayOf(AREA_TIMER.toByte(), 0x00, 0x09))

    // --- Long-frame area write (opcode 0xFF) ----------------------------
    //
    // The vendor app uses this for paraInfoArea / timerInfoArea writes.
    // Different shape from the 8-byte command path:
    //
    //   AA <len> FF <rand> 00 00 00 <areaTag> <payload> <pCrcH> <pCrcL> <fCrcH> <fCrcL>
    //
    // - len           = (payloadBytes / 4) - 1     (payload must be 4-aligned)
    // - pCrc          = CRC-16/XMODEM over payload alone
    // - fCrc          = CRC-16/XMODEM over header(8) + payload + pCrc(2)
    // - Total bytes   = 8 + payload + 4

    data class WriteTimerSlot(
        val dayIndex: Int,    // 0=Mon … 6=Sun
        val modeRaw:  Int,    // 0=off, 1=one-shot, 4=daily/recurring
        val onHour:   Int,
        val onMin:    Int,
        val offHour:  Int,
        val offMin:   Int,
    )

    fun buildWriteTimerArea(slots: List<WriteTimerSlot>): ByteArray {
        // Always send all 7 days; missing days default to mode=0 (disabled).
        val byDay = slots.associateBy { it.dayIndex.coerceIn(0, 6) }
        val payload = ByteArray(TIMER_PAYLOAD_BYTES) // 40 bytes, trailing 5 stay zero
        for (i in 0..6) {
            val s = byDay[i] ?: WriteTimerSlot(i, 0, 0, 0, 0, 0)
            payload[5 * i + 0] = s.modeRaw.toByte()
            payload[5 * i + 1] = s.onHour.toByte()
            payload[5 * i + 2] = s.onMin.toByte()
            payload[5 * i + 3] = s.offHour.toByte()
            payload[5 * i + 4] = s.offMin.toByte()
        }
        return buildLongWriteFrame(AREA_TIMER, payload)
    }

    private fun buildLongWriteFrame(areaTag: Int, payload: ByteArray): ByteArray {
        require(payload.size % 4 == 0) { "payload must be 4-aligned, got ${payload.size}" }
        val total = 8 + payload.size + 4
        val out = ByteArray(total)
        out[0] = SYNC
        out[1] = ((payload.size / 4) - 1).toByte()
        out[2] = OP_WRITE_AREA.toByte()
        out[3] = randomNonce()
        // Address bytes 4..6 are zero for area-tag writes; byte 7 carries
        // the area tag. Vendor sends longAddr reversed; for the timer
        // area whose longAddr is [0xF5, 0, 0, 0] that yields [0, 0, 0, 0xF5].
        out[4] = 0
        out[5] = 0
        out[6] = 0
        out[7] = areaTag.toByte()
        payload.copyInto(out, destinationOffset = 8)
        val pCrc = crc16Xmodem(payload, 0, payload.size)
        out[8 + payload.size]     = ((pCrc shr 8) and 0xFF).toByte()
        out[8 + payload.size + 1] = (pCrc and 0xFF).toByte()
        val fCrc = crc16Xmodem(out, 0, 8 + payload.size + 2)
        out[8 + payload.size + 2] = ((fCrc shr 8) and 0xFF).toByte()
        out[8 + payload.size + 3] = (fCrc and 0xFF).toByte()
        return out
    }

    private const val TIMER_PAYLOAD_BYTES = 40

    private fun eightByteCmd(opcode: Int, data: ByteArray): ByteArray {
        require(data.size == 3) { "Command payload must be exactly 3 bytes" }
        val out = ByteArray(8)
        out[0] = SYNC
        out[1] = 0x00
        out[2] = opcode.toByte()
        data.copyInto(out, destinationOffset = 3)
        val crc = crc16Xmodem(out, 0, 6)
        out[6] = ((crc shr 8) and 0xFF).toByte()
        out[7] = (crc and 0xFF).toByte()
        return out
    }

    private fun randomNonce(): Byte = Random.nextInt(0, 256).toByte()

    // --- CRC-16/XMODEM -------------------------------------------------
    // Poly 0x1021, init 0x0000, no reflection, no final XOR. Table lookup
    // mirrors the JS implementation in HeatGenie's Ee() function.

    private val CRC_TABLE: IntArray = intArrayOf(
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
    )

    private fun crc16Xmodem(buf: ByteArray, offset: Int, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            val b = buf[offset + i].toInt() and 0xFF
            // Two nibble lookups per byte, mirroring the JS table size (16).
            val hi = (b shr 4) and 0x0F
            val lo = b and 0x0F
            crc = ((crc shl 4) and 0xFFFF) xor CRC_TABLE[((crc shr 12) and 0x0F) xor hi]
            crc = ((crc shl 4) and 0xFFFF) xor CRC_TABLE[((crc shr 12) and 0x0F) xor lo]
        }
        return crc and 0xFFFF
    }

    fun validateCrc(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // Per the JS: crc16(buf, total_len) == 0 for a valid frame.
        return crc16Xmodem(bytes, 0, bytes.size) == 0
    }

    // --- Incoming frames -----------------------------------------------

    fun parseTelemetry(bytes: ByteArray): HeaterTelemetry? {
        // Reject anything that isn't a heater frame or has a bad CRC.
        if (bytes.size < 10) return null
        if (bytes[0] != SYNC) return null
        if (!validateCrc(bytes)) return null

        val len = bytes[1].toInt() and 0xFF
        if (len == 0) return null // 8-byte short frames carry no telemetry payload

        val tag = bytes[7].toInt() and 0xFF
        if (tag != AREA_REG_INFO) return null // only regInfo is telemetry-rich

        // Payload starts at byte 8, runs 4*(len+1) bytes.
        val payloadLen = 4 * (len + 1)
        if (bytes.size < 8 + payloadLen + 2) return null
        val p = bytes.copyOfRange(8, 8 + payloadLen)
        if (p.size < 24) return null // need at least the basic block

        val machineStatus  = p[0].toInt() and 0xFF
        val runningModeNib = machineStatus and 0x0F
        val tempUnitF      = (machineStatus and 0x10) != 0    // bit 4 = °F flag

        val voltage   = uleShort(p, 2) / 10.0
        val altitude  = uleShort(p, 4)
        val ambient   = signedTempC(p, 6)
        val housing   = signedTempC(p, 8)
        val pumpHz    = uleShort(p, 10) / 10.0
        val ignWatts  = uleShort(p, 12) / 10.0
        val fanRpm    = uleShort(p, 14)
        val intake    = signedTempC(p, 16).takeUnless { it == NOT_SUPPORTED }
        val outlet    = signedTempC(p, 18).takeUnless { it == NOT_SUPPORTED }
        val errorBits = uleShort(p, 20)

        val aimTarget = if (p.size >= 36) {
            val gear = p[32].toInt() and 0xFF
            val tval = p[33].toInt() and 0xFF
            // The byte holds the target in the heater's CURRENT display unit
            // (tempUnitFlag, bit 4 of machineStatus). Convert F→C so the app
            // always works in Celsius internally; HeaterTelemetry.targetTempC
            // is canonical Celsius regardless of what the heater is showing.
            val targetC = if (tempUnitF) (tval - 32) * 5.0 / 9.0 else tval.toDouble()
            AimTarget(gear = gear, valueC = targetC)
        } else null

        return HeaterTelemetry(
            outletTempC      = outlet,
            targetTempC      = aimTarget?.valueC,
            fuelPumpHz       = pumpHz,
            fanRpm           = fanRpm,
            glowPlugA        = null,
            batteryV         = voltage,
            ambientTempC     = ambient,
            housingTempC     = housing,
            intakeTempC      = intake,
            altitudeM        = altitude,
            ignitionWatts    = ignWatts,
            runningMode      = RunningMode.fromWire(runningModeNib),
            faultBits        = errorBits,
            tempUnitFahrenheit = tempUnitF,
            aimGear          = aimTarget?.gear,
            updatedAtMs      = System.currentTimeMillis(),
        )
    }

    // Parse the timerInfoArea (tag 0xF5) reply into 7 weekday slots.
    // Returns null if the frame doesn't match expectations.
    fun parseTimerInfo(bytes: ByteArray): List<TimerSlot>? {
        if (bytes.size < 10) return null
        if (bytes[0] != SYNC) return null
        if (!validateCrc(bytes)) return null
        val len = bytes[1].toInt() and 0xFF
        if (len == 0) return null
        if ((bytes[7].toInt() and 0xFF) != AREA_TIMER) return null
        val payloadLen = 4 * (len + 1)
        if (bytes.size < 8 + payloadLen + 2) return null
        if (payloadLen < 35) return null   // need 7 × 5 bytes minimum
        val p = bytes.copyOfRange(8, 8 + payloadLen)
        return List(7) { i ->
            val off = i * 5
            val rawMode = p[off + 0].toInt() and 0xFF
            // Vendor app folds raw 1/2 to display value 3 and ≥4 to 4.
            val folded = when {
                rawMode == 1 || rawMode == 2 -> 3
                rawMode >= 4                 -> 4
                else                         -> rawMode
            }
            TimerSlot(
                dayIndex = i,
                modeRaw  = rawMode,
                mode     = TimerMode.fromFolded(folded),
                onHour   = p[off + 1].toInt() and 0xFF,
                onMin    = p[off + 2].toInt() and 0xFF,
                offHour  = p[off + 3].toInt() and 0xFF,
                offMin   = p[off + 4].toInt() and 0xFF,
            )
        }
    }

    private fun uleShort(p: ByteArray, off: Int): Int =
        ((p[off + 1].toInt() and 0xFF) shl 8) or (p[off].toInt() and 0xFF)

    private fun signedTempC(p: ByteArray, off: Int): Double {
        val raw = uleShort(p, off)
        if (raw == 32760) return NOT_SUPPORTED   // sentinel
        val signed = if (raw > 32767) raw - 65536 else raw
        return signed / 10.0
    }

    private const val NOT_SUPPORTED = Double.MIN_VALUE

    // --- Hex helpers ----------------------------------------------------

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = " ") { "%02X".format(it) }

    fun fromHex(hex: String): ByteArray {
        val cleaned = hex.filter { !it.isWhitespace() && it != ':' && it != ',' }
        require(cleaned.length % 2 == 0) { "Hex must have an even length" }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private data class AimTarget(val gear: Int, val valueC: Double)
}

// Running-mode nibble (low 4 bits of machineStatus byte). String labels
// match the vendor app's UI.
enum class RunningMode(val wire: Int, val label: String) {
    Boot(0,             "Boot"),
    Ignition(1,         "Ignition"),
    AutoRun(2,          "Running"),
    ManualRun(3,        "Manual"),
    Cooldown(4,         "Cool-down"),
    Standby(5,          "Standby"),
    Fault(6,            "Fault"),
    ManualPump(7,       "Priming pump"),
    Ventilation(8,      "Ventilating"),
    StartStopActive(9,  "Auto on"),
    StartStopConfig(10, "Auto configured"),
    Unknown(-1,         "—");

    companion object {
        fun fromWire(v: Int): RunningMode = entries.firstOrNull { it.wire == v } ?: Unknown
    }
}
