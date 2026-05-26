namespace TsgbHeater.Protocol.Hcalory;

// Tuya BLE frame codec. The protocol is documented at
// https://developer.tuya.com/en/docs/iot/tuya-ble-sdk-protocol and was
// cross-checked against the decompiled HCalory app's encoder/decoder
// (OLDSRC/hcalory/sources/sources/j2/j.java).
//
// Frame layout (on-the-wire bytes):
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

        var outBuf = new byte[8 + payload.Length + 1];
        outBuf[0] = frame.Version;
        outBuf[1] = frame.Seq;
        outBuf[2] = frame.Cmd0;
        outBuf[3] = frame.Cmd1;
        outBuf[4] = frame.Cmd2;
        outBuf[5] = frame.Flags;
        outBuf[6] = (byte)((payload.Length >> 8) & 0xFF);
        outBuf[7] = (byte)(payload.Length & 0xFF);
        Buffer.BlockCopy(payload, 0, outBuf, 8, payload.Length);
        outBuf[^1] = Checksum(outBuf, 0, outBuf.Length - 1);
        return outBuf;
    }

    // --- Decoding ----------------------------------------------------

    /// <summary>Returns null if the frame is malformed (too short, length mismatch, bad checksum).</summary>
    public static Frame? Decode(byte[] raw)
    {
        if (raw.Length < 9) return null;

        var payloadLen = (raw[6] << 8) | raw[7];
        if (8 + payloadLen + 1 != raw.Length) return null;

        var expected = Checksum(raw, 0, raw.Length - 1);
        if (expected != raw[^1]) return null;

        var dps = new List<Dp>();
        var p = 8;
        var end = 8 + payloadLen;
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
