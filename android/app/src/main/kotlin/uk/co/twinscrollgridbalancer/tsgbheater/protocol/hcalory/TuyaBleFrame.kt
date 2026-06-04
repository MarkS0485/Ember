package uk.co.twinscrollgridbalancer.tsgbheater.protocol.hcalory

// Tuya-style BLE frame codec, matched byte-for-byte against the live
// HCalory heater (MAC ...08:E3) and the decompiled app's encode/decode
// (OLDSRC/hcalory/sources/sources/j2/j.java — b.c() decoder, b.f()
// checksum). The earlier guess at the layout (seq-based header,
// whole-frame checksum) was wrong on the wire; this is the verified
// format.
//
// Frame layout (raw bytes on the wire):
//
//   pos     bytes   field         meaning
//   ----    ------  ------------  -------------------------------------
//   0       1       version       0x00
//   1       1       marker        0x02 app->device (fixed); increments device->app
//   2..4    3       00 01 00      fixed
//   5       1       flag          0x01
//   6..7    2       payloadLen    BE; = N-8, i.e. count of bytes 8..N-1
//                                 (the DP descriptor + value + checksum byte)
//   8       1       dpId          data-point id / message type (the dispatch key)
//   9       1       dpType        DP type
//   10..11  2       dpLen         BE length of the value
//   12..    dpLen   value
//   N-1     1       checksum      sum(bytes 8..N-2) & 0xFF  (PAYLOAD ONLY,
//                                 excludes the 7-byte header and itself)
//
// Two things the wire taught us that the docs got wrong:
//   * The checksum covers only bytes 8..N-2, NOT the header.
//   * payloadLen at 6..7 INCLUDES the trailing checksum byte (N-8), so a
//     valid frame has raw.size == 8 + payloadLen.
//
// The "0A0C" / "0909" / "0E04" tokens in the decompiled command strings
// are NOT 2-byte DP ids — they are [payloadLen-lo][dpId] fused together.
// The real dpId is the single byte at offset 8.
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

        // payloadLen (bytes 6..7) counts the DP bytes PLUS the trailing
        // checksum byte, so it equals (N - 8). Header is the fixed native
        // preamble the heater expects from a central.
        val out = ByteArray(8 + payload.size + 1)
        val payloadLen = payload.size + 1
        out[0] = 0x00
        out[1] = 0x02
        out[2] = 0x00
        out[3] = 0x01
        out[4] = 0x00
        out[5] = 0x01
        out[6] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[7] = (payloadLen and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 8, payload.size)
        // Checksum is PAYLOAD ONLY: sum of bytes 8..N-2.
        out[out.size - 1] = checksum(out, 8, out.size - 1).toByte()
        return out
    }

    // --- Decoding ---------------------------------------------------

    /** Returns null if the frame is malformed (too short, length mismatch, bad checksum). */
    fun decode(raw: ByteArray): Frame? {
        if (raw.size < 9) return null  // 8-byte header + 1-byte checksum minimum

        // payloadLen at 6..7 counts bytes 8..N-1 (DP bytes + checksum), so a
        // valid frame has raw.size == 8 + payloadLen.
        val payloadLen = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (8 + payloadLen != raw.size) return null

        // Checksum is PAYLOAD ONLY: sum of bytes 8..N-2.
        val expected = checksum(raw, 8, raw.size - 1) and 0xFF
        val seen     = raw[raw.size - 1].toInt() and 0xFF
        if (expected != seen) return null

        val dps = mutableListOf<Dp>()
        var p = 8
        val end = raw.size - 1  // DP region ends before the checksum byte
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
