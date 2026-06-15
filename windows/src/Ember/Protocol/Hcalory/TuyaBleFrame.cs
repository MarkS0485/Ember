namespace Ember.Protocol.Hcalory;

// Tuya-style BLE frame codec, matched byte-for-byte against the live
// HCalory heater (MAC ...08:E3) and the decompiled app (j2/j.java b.c()
// decoder, b.f() checksum). The earlier guess at the layout (seq-based
// header, whole-frame checksum) was wrong on the wire; this is verified.
//
// Frame layout (on-the-wire bytes):
//
//   pos     bytes   field         meaning
//   ----    ------  ------------  -------------------------------------
//   0       1       version       0x00
//   1       1       marker        0x02 app->device (fixed); increments device->app
//   2..4    3       00 01 00      fixed
//   5       1       flag          0x01
//   6..7    2       payloadLen    BE; = N-8, i.e. count of bytes 8..N-1
//                                 (DP descriptor + value + the checksum byte)
//   8       1       dpId          data-point id / message type (dispatch key)
//   9       1       dpType
//   10..11  2       dpLen         BE length of the value
//   12..    dpLen   value
//   N-1     1       checksum      sum(bytes 8..N-2) & 0xFF  (PAYLOAD ONLY,
//                                 excludes the 7-byte header and itself)
//
// Wire facts the docs got wrong:
//   * checksum covers only bytes 8..N-2, NOT the header.
//   * payloadLen (6..7) INCLUDES the trailing checksum byte (= N-8), so a
//     valid frame has raw.Length == 8 + payloadLen.
//   * the "0A0C"/"0909"/"0E04" tokens in the decompiled command strings are
//     [payloadLen-lo][dpId] fused, NOT 2-byte dp ids. Real dpId is byte 8.
//
// DP types (Tuya spec):
//   0x00 raw, 0x01 bool, 0x02 value (4B BE int),
//   0x03 string (ascii), 0x04 enum (1B), 0x05 fault (bitfield)
public static class TuyaBleFrame
{
    public const byte DpTypeRaw    = 0x00;
    public const byte DpTypeBool   = 0x01;
    public const byte DpTypeValue  = 0x02;
    public const byte DpTypeString = 0x03;
    public const byte DpTypeEnum   = 0x04;
    public const byte DpTypeFault  = 0x05;

    public sealed record Dp(byte Id, byte Type, byte[] Value)
    {
        public bool Equals(Dp? other)
            => other is not null && Id == other.Id && Type == other.Type
               && Value.AsSpan().SequenceEqual(other.Value);
        public override int GetHashCode()
            => HashCode.Combine(Id, Type, Value.Length);
    }

    public sealed record Frame(
        byte Version,
        byte Seq,
        byte Cmd0,
        byte Cmd1,
        byte Cmd2,
        byte Flags,
        IReadOnlyList<Dp> Dps);

    // --- Encoding ----------------------------------------------------

    /// <summary>Build a single-DP write frame. Most commands are one DP at a time.</summary>
    public static byte[] EncodeSingleDp(byte seq, Dp dp, byte version = 0)
        => Encode(new Frame(version, seq, 0, 0, 0, 0, new[] { dp }));

    public static byte[] Encode(Frame frame)
    {
        var payloadLen = 0;
        foreach (var dp in frame.Dps) payloadLen += 4 + dp.Value.Length;

        var payload = new byte[payloadLen];
        var p = 0;
        foreach (var dp in frame.Dps)
        {
            payload[p++] = dp.Id;
            payload[p++] = dp.Type;
            payload[p++] = (byte)((dp.Value.Length >> 8) & 0xFF);
            payload[p++] = (byte)(dp.Value.Length & 0xFF);
            Buffer.BlockCopy(dp.Value, 0, payload, p, dp.Value.Length);
            p += dp.Value.Length;
        }

        // payloadLen (bytes 6..7) counts the DP bytes PLUS the trailing
        // checksum byte (= N-8). Header is the fixed native preamble the
        // heater expects from a central.
        var outBuf = new byte[8 + payload.Length + 1];
        int payloadLenField = payload.Length + 1;
        outBuf[0] = 0x00;
        outBuf[1] = 0x02;
        outBuf[2] = 0x00;
        outBuf[3] = 0x01;
        outBuf[4] = 0x00;
        outBuf[5] = 0x01;
        outBuf[6] = (byte)((payloadLenField >> 8) & 0xFF);
        outBuf[7] = (byte)(payloadLenField & 0xFF);
        Buffer.BlockCopy(payload, 0, outBuf, 8, payload.Length);
        // Checksum is PAYLOAD ONLY: sum of bytes 8..N-2.
        outBuf[^1] = Checksum(outBuf, 8, outBuf.Length - 1);
        return outBuf;
    }

    // --- Decoding ----------------------------------------------------

    /// <summary>Returns null if the frame is malformed (too short, length mismatch, bad checksum).</summary>
    public static Frame? Decode(byte[] raw)
    {
        if (raw.Length < 9) return null;

        // payloadLen at 6..7 counts bytes 8..N-1 (DP bytes + checksum), so a
        // valid frame has raw.Length == 8 + payloadLen.
        var payloadLen = (raw[6] << 8) | raw[7];
        if (8 + payloadLen != raw.Length) return null;

        // Checksum is PAYLOAD ONLY: sum of bytes 8..N-2.
        var expected = Checksum(raw, 8, raw.Length - 1);
        if (expected != raw[^1]) return null;

        var dps = new List<Dp>();
        var p = 8;
        var end = raw.Length - 1;  // DP region ends before the checksum byte
        while (p < end)
        {
            if (p + 4 > end) return null;
            byte id   = raw[p];
            byte type = raw[p + 1];
            int  len  = (raw[p + 2] << 8) | raw[p + 3];
            if (p + 4 + len > end) return null;
            var value = new byte[len];
            Buffer.BlockCopy(raw, p + 4, value, 0, len);
            dps.Add(new Dp(id, type, value));
            p += 4 + len;
        }

        return new Frame(
            Version: raw[0],
            Seq:     raw[1],
            Cmd0:    raw[2],
            Cmd1:    raw[3],
            Cmd2:    raw[4],
            Flags:   raw[5],
            Dps:     dps);
    }

    // --- Helpers -----------------------------------------------------

    private static byte Checksum(byte[] buf, int from, int untilExclusive)
    {
        int s = 0;
        for (int i = from; i < untilExclusive; i++) s = (s + buf[i]) & 0xFF;
        return (byte)s;
    }

    public static Dp DpBool(byte id, bool v)         => new(id, DpTypeBool,   new[] { (byte)(v ? 1 : 0) });
    public static Dp DpEnum(byte id, byte v)         => new(id, DpTypeEnum,   new[] { v });
    public static Dp DpValue(byte id, int v)         => new(id, DpTypeValue,  new[]
    {
        (byte)((v >> 24) & 0xFF),
        (byte)((v >> 16) & 0xFF),
        (byte)((v >>  8) & 0xFF),
        (byte)( v        & 0xFF),
    });
    public static Dp DpString(byte id, string v)     => new(id, DpTypeString, System.Text.Encoding.ASCII.GetBytes(v));
    public static Dp DpRaw(byte id, byte[] v)        => new(id, DpTypeRaw,    v);

    public static int DpValueInt(Dp dp)
    {
        if (dp.Value.Length != 4) throw new ArgumentException("value DP must be 4 bytes");
        return (dp.Value[0] << 24) | (dp.Value[1] << 16) | (dp.Value[2] << 8) | dp.Value[3];
    }
}
