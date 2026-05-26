package uk.co.twinscrollgridbalancer.tsgbheater.protocol.hcalory

// Tuya BLE frame codec. The protocol is documented at
// https://developer.tuya.com/en/docs/iot/tuya-ble-sdk-protocol and was
// cross-checked against the decompiled HCalory app's encoder/decoder
// (OLDSRC/hcalory/sources/sources/j2/j.java).
//
// Frame layout (hex-string-on-the-wire representation):
//
//   pos   bytes   field          meaning
//   ----  ------  -------------  ----------------------------------
//   0     1       version        protocol version byte (0x00 in HCalory)
//   1     1       seq            monotonically incrementing seq id
//   2     1       cmd0           sub-command byte (often 0x00 for DP)
//   3     1       cmd1
//   4     1       cmd2
//   5     1       flags          status flags
//   6     2       payloadLen     big-endian length of payload portion
//   8..   N-2     payload        DP units, each: dpid(1)|dpType(1)|dpLen(2BE)|value(dpLen)
//   N-1   1       checksum       (sum of preceding bytes) & 0xFF
//
// Note: the on-the-wire format is BYTES — the hex-string layer in
// HCalory's code is just how their parser handles it internally. We
// keep the codec working in raw bytes for clarity.
//
// Data Point (DP) types per Tuya spec:
//   0x00 raw      - opaque blob
//   0x01 bool     - 1 byte, 0/1
//   0x02 value    - 4 bytes, BE signed int
//   0x03 string   - ascii bytes
//   0x04 enum     - 1 byte
//   0x05 fault    - bitfield (length-defined)

@Suppress("MemberVisibilityCanBePrivate")
object TuyaBleFrame {

    const val DP_TYPE_RAW    = 0x00
    const val DP_TYPE_BOOL   = 0x01
    const val DP_TYPE_VALUE  = 0x02
    const val DP_TYPE_STRING = 0x03
    const val DP_TYPE_ENUM   = 0x04
    const val DP_TYPE_FAULT  = 0x05

    data class Dp(val id: Int, val type: Int, val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Dp && id == other.id && type == other.type && value.contentEquals(other.value)
        override fun hashCode(): Int =
            31 * (31 * id + type) + value.contentHashCode()
    }

    data class Frame(
        val version:  Int,
        val seq:      Int,
        val cmd0:     Int = 0,
        val cmd1:     Int = 0,
        val cmd2:     Int = 0,
        val flags:    Int = 0,
        val dps:      List<Dp>,
    )

    // --- Encoding ---------------------------------------------------

    /** Build a single-DP write frame. Most commands are one DP at a time. */
    fun encodeSingleDp(seq: Int, dp: Dp, version: Int = 0): ByteArray =
        encode(Frame(version = version, seq = seq, dps = listOf(dp)))

    fun encode(frame: Frame): ByteArray {
        val payload = ByteArray(frame.dps.sumOf { 4 + it.value.size })
        var p = 0
        for (dp in frame.dps) {
            payload[p++] = (dp.id and 0xFF).toByte()
            payload[p++] = (dp.type and 0xFF).toByte()
            payload[p++] = ((dp.value.size ushr 8) and 0xFF).toByte()
            payload[p++] = (dp.value.size and 0xFF).toByte()
            System.arraycopy(dp.value, 0, payload, p, dp.value.size)
            p += dp.value.size
        }

        val out = ByteArray(8 + payload.size + 1)
        out[0] = (frame.version and 0xFF).toByte()
        out[1] = (frame.seq and 0xFF).toByte()
        out[2] = (frame.cmd0 and 0xFF).toByte()
        out[3] = (frame.cmd1 and 0xFF).toByte()
        out[4] = (frame.cmd2 and 0xFF).toByte()
        out[5] = (frame.flags and 0xFF).toByte()
        out[6] = ((payload.size ushr 8) and 0xFF).toByte()
        out[7] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 8, payload.size)
        out[out.size - 1] = checksum(out, 0, out.size - 1).toByte()
        return out
    }

    // --- Decoding ---------------------------------------------------

    /** Returns null if the frame is malformed (too short, length mismatch, bad checksum). */
    fun decode(raw: ByteArray): Frame? {
        if (raw.size < 9) return null  // 8-byte header + 1-byte checksum minimum

        val payloadLen = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (8 + payloadLen + 1 != raw.size) return null

        val expected = checksum(raw, 0, raw.size - 1) and 0xFF
        val seen     = raw[raw.size - 1].toInt() and 0xFF
        if (expected != seen) return null

        val dps = mutableListOf<Dp>()
        var p = 8
        val end = 8 + payloadLen
        while (p < end) {
            if (p + 4 > end) return null
            val id   = raw[p].toInt() and 0xFF
            val type = raw[p + 1].toInt() and 0xFF
            val len  = ((raw[p + 2].toInt() and 0xFF) shl 8) or (raw[p + 3].toInt() and 0xFF)
            if (p + 4 + len > end) return null
            val value = raw.copyOfRange(p + 4, p + 4 + len)
            dps += Dp(id, type, value)
            p += 4 + len
        }

        return Frame(
            version = raw[0].toInt() and 0xFF,
            seq     = raw[1].toInt() and 0xFF,
            cmd0    = raw[2].toInt() and 0xFF,
            cmd1    = raw[3].toInt() and 0xFF,
            cmd2    = raw[4].toInt() and 0xFF,
            flags   = raw[5].toInt() and 0xFF,
            dps     = dps,
        )
    }

    // --- Helpers ----------------------------------------------------

    private fun checksum(buf: ByteArray, from: Int, untilExclusive: Int): Int {
        var s = 0
        for (i in from until untilExclusive) s = (s + (buf[i].toInt() and 0xFF)) and 0xFF
        return s
    }

    fun dpBool(id: Int, v: Boolean)            = Dp(id, DP_TYPE_BOOL,   byteArrayOf(if (v) 1 else 0))
    fun dpEnum(id: Int, v: Int)                = Dp(id, DP_TYPE_ENUM,   byteArrayOf((v and 0xFF).toByte()))
    fun dpValue(id: Int, v: Int)               = Dp(id, DP_TYPE_VALUE,
        byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr  8) and 0xFF).toByte(),
            ( v          and 0xFF).toByte(),
        ))
    fun dpString(id: Int, v: String)           = Dp(id, DP_TYPE_STRING, v.toByteArray(Charsets.US_ASCII))
    fun dpRaw(id: Int, v: ByteArray)           = Dp(id, DP_TYPE_RAW,    v)

    fun dpValueInt(dp: Dp): Int {
        require(dp.value.size == 4) { "value DP must be 4 bytes" }
        return ((dp.value[0].toInt() and 0xFF) shl 24) or
               ((dp.value[1].toInt() and 0xFF) shl 16) or
               ((dp.value[2].toInt() and 0xFF) shl  8) or
               ( dp.value[3].toInt() and 0xFF)
    }
}
