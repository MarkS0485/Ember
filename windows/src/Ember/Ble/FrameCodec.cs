namespace Ember.Ble;

// Frame builder/parser for the HeatGenie BLE protocol. Line-for-line
// port of android/app/.../ble/FrameCodec.kt. See docs/BLE_PROTOCOL.md for
// citations into the decompiled vendor app.
//
// Wire format (8-byte heater frame):
//   AA  len  op  d0  d1  d2  crcH  crcL    (CRC-16/XMODEM over bytes 0..5)
//
// Telemetry response variant (longer):
//   AA  len  op  d0  d1  d2  d3  tag  <payload, 4*(len+1) bytes>  crcH crcL
//
// Long-write variant (paraInfo / timerInfo writes):
//   AA <len> FF <rand> 00 00 00 <areaTag> <payload> <payloadCrc> <frameCrc>
//   payload must be 4-aligned; len = (payloadBytes/4) - 1
public static class FrameCodec
{
    private const byte SYNC = 0xAA;

    // Heater-side opcodes (DB0_DN_*)
    private const int OP_CMD          = 0x61;   // button events (random nonce in d1)
    private const int OP_MANU_PUMP    = 0x62;   // timed manual pump (seconds in d0)
    private const int OP_AUTO_UPDATE  = 0x65;   // start/stop periodic telemetry
    private const int OP_SHORT_PARA   = 0x66;   // set a single setting
    private const int OP_GET_REG_VAL  = 0x64;   // one-shot read of an info area
    private const int OP_WRITE_AREA   = 0xFF;   // long-frame write to a whole info area

    // CMD_* (d0 of DB0_DN_CMD)
    private const int CMD_ON             = 1;
    private const int CMD_OFF            = 2;
    private const int CMD_UP             = 3;
    private const int CMD_DOWN           = 4;
    private const int CMD_SWITCH_TEMP_FC = 8;   // switch panel to °C (was in °F)
    private const int CMD_BLOW_ON        = 9;
    private const int CMD_SWITCH_TEMP_CF = 10;  // switch panel to °F (was in °C)
    private const int CMD_OIL_PUMP_ON    = 11;
    private const int CMD_OIL_PUMP_OFF   = 12;  // NB: vendor's manual-pump-off path uses CMD_OFF instead.

    // SHORT_* (d0 of DB0_DN_SHORT_PARA)
    private const int SHORT_RUN_MODE     = 0x00;
    private const int SHORT_TARGET_TEMP  = 0x01;
    private const int SHORT_TARGET_GEAR  = 0x02;
    private const int SHORT_TIMER_ADJUST = 0x03;   // also overloaded as wall-clock setter
    private const int SHORT_TEMP_DIFF    = 0x04;

    // Area tags
    public const int AREA_VERSION  = 0xF1;
    public const int AREA_REG_INFO = 0xF2;
    public const int AREA_PARA     = 0xF3;
    public const int AREA_TIMER    = 0xF5;

    public enum TempUnit { Celsius = 0, Fahrenheit = 1 }
    public enum RunMode  { Auto    = 0, Manual     = 1, StartStop = 2 }

    public const int ManualPumpMinSeconds     = 5;
    public const int ManualPumpMaxSeconds     = 90;
    public const int ManualPumpDefaultSeconds = 20;

    // --- 8-byte command builders ---------------------------------------

    public static byte[] BuildStartHeater() =>
        Eight(OP_CMD, (byte)CMD_ON,  Nonce(), 0);

    public static byte[] BuildStopHeater() =>
        Eight(OP_CMD, (byte)CMD_OFF, Nonce(), 0);

    public static byte[] BuildButtonUp() =>
        Eight(OP_CMD, (byte)CMD_UP,   Nonce(), 0);

    public static byte[] BuildButtonDown() =>
        Eight(OP_CMD, (byte)CMD_DOWN, Nonce(), 0);

    public static byte[] BuildBlowOn() =>
        Eight(OP_CMD, (byte)CMD_BLOW_ON,      Nonce(), 0);

    public static byte[] BuildOilPumpOnButton() =>
        Eight(OP_CMD, (byte)CMD_OIL_PUMP_ON,  Nonce(), 0);

    public static byte[] BuildOilPumpOffButton() =>
        Eight(OP_CMD, (byte)CMD_OIL_PUMP_OFF, Nonce(), 0);

    // Timed manual pump. The CMD_OIL_PUMP_ON button alone won't spin the
    // pump — the controller waits for this opcode with a duration.
    public static byte[] BuildManualPumpRun(int seconds)
    {
        int s = Math.Clamp(seconds, ManualPumpMinSeconds, ManualPumpMaxSeconds);
        return Eight(OP_MANU_PUMP, (byte)s, Nonce(), 0);
    }

    public static byte[] BuildSwitchToCelsius() =>
        Eight(OP_CMD, (byte)CMD_SWITCH_TEMP_FC, Nonce(), 0);

    public static byte[] BuildSwitchToFahrenheit() =>
        Eight(OP_CMD, (byte)CMD_SWITCH_TEMP_CF, Nonce(), 0);

    public static byte[] BuildSetTargetTemp(int value, TempUnit unit = TempUnit.Celsius) =>
        Eight(OP_SHORT_PARA,
            (byte)SHORT_TARGET_TEMP,
            (byte)(int)unit,
            (byte)(value & 0xFF));

    public static byte[] BuildSetGear(int gear) =>
        Eight(OP_SHORT_PARA,
            (byte)SHORT_TARGET_GEAR,
            0,
            (byte)(gear & 0xFF));

    public static byte[] BuildSetRunMode(RunMode mode) =>
        Eight(OP_SHORT_PARA,
            (byte)SHORT_RUN_MODE,
            0,
            (byte)(int)mode);

    public static byte[] BuildSetTempHysteresis(int diff, TempUnit unit = TempUnit.Celsius) =>
        Eight(OP_SHORT_PARA,
            (byte)SHORT_TEMP_DIFF,
            (byte)(int)unit,
            (byte)(diff & 0xFF));

    // Set the heater's wall clock. Vendor convention: Monday=0..Sunday=6.
    // hour is 5 bits (0..31), minute 0..59.
    public static byte[] BuildSetClock(int vendorDayMonZero, int hour, int minute)
    {
        int day = Math.Clamp(vendorDayMonZero, 0, 6);
        int h   = Math.Clamp(hour,   0, 31);
        int m   = Math.Clamp(minute, 0, 59);
        int packed = (day << 5) | h;
        return Eight(OP_SHORT_PARA,
            (byte)SHORT_TIMER_ADJUST,
            (byte)packed,
            (byte)m);
    }

    // Translate a DayOfWeek (Sunday=0..Saturday=6) into the vendor's
    // Mon=0..Sun=6 index.
    public static int DayOfWeekToVendor(DayOfWeek dow) => dow switch
    {
        DayOfWeek.Monday    => 0,
        DayOfWeek.Tuesday   => 1,
        DayOfWeek.Wednesday => 2,
        DayOfWeek.Thursday  => 3,
        DayOfWeek.Friday    => 4,
        DayOfWeek.Saturday  => 5,
        DayOfWeek.Sunday    => 6,
        _                    => 0,
    };

    public static byte[] BuildStartTelemetryStream() =>
        Eight(OP_AUTO_UPDATE, 0x02, 0x0A, 0x00);

    public static byte[] BuildStopTelemetryStream() =>
        Eight(OP_AUTO_UPDATE, 0x02, 0x00, 0x1E);

    public static byte[] BuildReadRegInfo() =>
        Eight(OP_GET_REG_VAL, (byte)AREA_REG_INFO, 0x00, 0x05);

    public static byte[] BuildReadTimerInfo() =>
        Eight(OP_GET_REG_VAL, (byte)AREA_TIMER, 0x00, 0x09);

    // Experimental: send an arbitrary SHORT_PARA frame. For protocol
    // probes only; vendor indices are 0..4.
    public static byte[] BuildShortParaProbe(int index, int d1, int d2) =>
        Eight(OP_SHORT_PARA,
            (byte)(index & 0xFF),
            (byte)(d1 & 0xFF),
            (byte)(d2 & 0xFF));

    private static byte[] Eight(int opcode, byte d0, byte d1, byte d2)
    {
        var o = new byte[8];
        o[0] = SYNC;
        o[1] = 0x00;
        o[2] = (byte)opcode;
        o[3] = d0;
        o[4] = d1;
        o[5] = d2;
        int crc = Crc16Xmodem(o, 0, 6);
        o[6] = (byte)((crc >> 8) & 0xFF);
        o[7] = (byte)(crc & 0xFF);
        return o;
    }

    private static readonly Random Rng = new();
    private static byte Nonce() => (byte)Rng.Next(0, 256);

    // --- Long-frame write (op 0xFF, double CRC) ------------------------

    public static byte[] BuildWriteTimerArea(IReadOnlyList<WriteTimerSlot> slots)
    {
        // Always send all 7 days; missing days default to mode=0.
        var byDay = new Dictionary<int, WriteTimerSlot>();
        foreach (var s in slots)
        {
            int d = Math.Clamp(s.DayIndex, 0, 6);
            byDay[d] = s;
        }
        var payload = new byte[TimerPayloadBytes];   // 40 bytes, trailing 5 zero
        for (int i = 0; i < 7; i++)
        {
            var s = byDay.TryGetValue(i, out var v) ? v : new WriteTimerSlot(i, 0, 0, 0, 0, 0);
            payload[5 * i + 0] = (byte)s.ModeRaw;
            payload[5 * i + 1] = (byte)s.OnHour;
            payload[5 * i + 2] = (byte)s.OnMin;
            payload[5 * i + 3] = (byte)s.OffHour;
            payload[5 * i + 4] = (byte)s.OffMin;
        }
        return BuildLongWriteFrame(AREA_TIMER, payload);
    }

    private static byte[] BuildLongWriteFrame(int areaTag, byte[] payload)
    {
        if ((payload.Length & 3) != 0)
            throw new ArgumentException($"payload must be 4-aligned, got {payload.Length}", nameof(payload));
        int total = 8 + payload.Length + 4;
        var o = new byte[total];
        o[0] = SYNC;
        o[1] = (byte)((payload.Length / 4) - 1);
        o[2] = (byte)OP_WRITE_AREA;
        o[3] = Nonce();
        // Address bytes 4..6 zero; byte 7 = area tag (vendor sends longAddr
        // reversed; [0xF5,0,0,0] → [0,0,0,0xF5]).
        o[4] = 0; o[5] = 0; o[6] = 0;
        o[7] = (byte)areaTag;
        Buffer.BlockCopy(payload, 0, o, 8, payload.Length);
        int pCrc = Crc16Xmodem(payload, 0, payload.Length);
        o[8 + payload.Length]     = (byte)((pCrc >> 8) & 0xFF);
        o[8 + payload.Length + 1] = (byte)(pCrc & 0xFF);
        int fCrc = Crc16Xmodem(o, 0, 8 + payload.Length + 2);
        o[8 + payload.Length + 2] = (byte)((fCrc >> 8) & 0xFF);
        o[8 + payload.Length + 3] = (byte)(fCrc & 0xFF);
        return o;
    }

    private const int TimerPayloadBytes = 40;

    // --- CRC-16 / XMODEM ----------------------------------------------
    // Poly 0x1021, init 0x0000, no reflection, no final XOR. Table lookup
    // mirrors the JS implementation (16-entry nibble table).

    private static readonly int[] CrcTable =
    {
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
    };

    private static int Crc16Xmodem(byte[] buf, int offset, int len)
    {
        int crc = 0;
        for (int i = 0; i < len; i++)
        {
            int b  = buf[offset + i] & 0xFF;
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            crc = ((crc << 4) & 0xFFFF) ^ CrcTable[((crc >> 12) & 0x0F) ^ hi];
            crc = ((crc << 4) & 0xFFFF) ^ CrcTable[((crc >> 12) & 0x0F) ^ lo];
        }
        return crc & 0xFFFF;
    }

    public static bool ValidateCrc(byte[] bytes)
    {
        if (bytes.Length < 4) return false;
        return Crc16Xmodem(bytes, 0, bytes.Length) == 0;
    }

    // --- Incoming frames -----------------------------------------------

    private const double NotSupported = double.MinValue;

    public static HeaterTelemetry? ParseTelemetry(byte[] bytes)
    {
        if (bytes.Length < 10) return null;
        if (bytes[0] != SYNC) return null;
        if (!ValidateCrc(bytes)) return null;

        int len = bytes[1] & 0xFF;
        if (len == 0) return null;   // 8-byte short frames carry no payload

        int tag = bytes[7] & 0xFF;
        if (tag != AREA_REG_INFO) return null;

        int payloadLen = 4 * (len + 1);
        if (bytes.Length < 8 + payloadLen + 2) return null;
        var p = new byte[payloadLen];
        Buffer.BlockCopy(bytes, 8, p, 0, payloadLen);
        if (p.Length < 24) return null;

        int machineStatus  = p[0] & 0xFF;
        int runningModeNib = machineStatus & 0x0F;
        bool tempUnitF     = (machineStatus & 0x10) != 0;

        double voltage  = UleShort(p, 2) / 10.0;
        int    altitude = UleShort(p, 4);
        double ambient  = SignedTempC(p, 6);
        double housing  = SignedTempC(p, 8);
        double pumpHz   = UleShort(p, 10) / 10.0;
        double ignWatts = UleShort(p, 12) / 10.0;
        int    fanRpm   = UleShort(p, 14);
        double? intake  = NullIfNotSupported(SignedTempC(p, 16));
        double? outlet  = NullIfNotSupported(SignedTempC(p, 18));
        int    errBits  = UleShort(p, 20);

        int?    aimGear = null;
        double? targetC = null;
        if (p.Length >= 36)
        {
            aimGear = p[32] & 0xFF;
            int tval = p[33] & 0xFF;
            // Stored in the heater's current display unit (tempUnitF bit).
            // Convert F→C so app code is always Celsius.
            targetC = tempUnitF ? (tval - 32) * 5.0 / 9.0 : (double)tval;
        }

        return new HeaterTelemetry(
            OutletTempC        : outlet,
            TargetTempC        : targetC,
            FuelPumpHz         : pumpHz,
            FanRpm             : fanRpm,
            GlowPlugA          : null,
            BatteryV           : voltage,
            AmbientTempC       : ambient,
            HousingTempC       : housing,
            IntakeTempC        : intake,
            AltitudeM          : altitude,
            IgnitionWatts      : ignWatts,
            RunningMode        : RunningModeExt.FromWire(runningModeNib),
            FaultBits          : errBits,
            TempUnitFahrenheit : tempUnitF,
            AimGear            : aimGear,
            UpdatedAtMs        : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public static IReadOnlyList<TimerSlot>? ParseTimerInfo(byte[] bytes)
    {
        if (bytes.Length < 10) return null;
        if (bytes[0] != SYNC) return null;
        if (!ValidateCrc(bytes)) return null;
        int len = bytes[1] & 0xFF;
        if (len == 0) return null;
        if ((bytes[7] & 0xFF) != AREA_TIMER) return null;
        int payloadLen = 4 * (len + 1);
        if (bytes.Length < 8 + payloadLen + 2) return null;
        if (payloadLen < 35) return null;
        var p = new byte[payloadLen];
        Buffer.BlockCopy(bytes, 8, p, 0, payloadLen);
        var slots = new TimerSlot[7];
        for (int i = 0; i < 7; i++)
        {
            int off    = i * 5;
            int rawMod = p[off] & 0xFF;
            int modeRaw = rawMod;
            // Vendor folds raw 1/2 → display 3, ≥4 → display 4; we expose
            // the raw value as-is here so the editor can round-trip.
            slots[i] = new TimerSlot(
                DayIndex: i,
                ModeRaw : modeRaw,
                OnHour  : p[off + 1] & 0xFF,
                OnMin   : p[off + 2] & 0xFF,
                OffHour : p[off + 3] & 0xFF,
                OffMin  : p[off + 4] & 0xFF);
        }
        return slots;
    }

    private static int UleShort(byte[] p, int off) =>
        ((p[off + 1] & 0xFF) << 8) | (p[off] & 0xFF);

    private static double SignedTempC(byte[] p, int off)
    {
        int raw = UleShort(p, off);
        if (raw == 32760) return NotSupported;
        int signed = raw > 32767 ? raw - 65536 : raw;
        return signed / 10.0;
    }

    private static double? NullIfNotSupported(double v) =>
        v == NotSupported ? null : v;

    // --- Hex helpers ---------------------------------------------------

    public static string Hex(byte[] bytes) =>
        string.Join(' ', bytes.Select(b => b.ToString("X2")));

    public static byte[] FromHex(string hex)
    {
        var cleaned = new string(hex.Where(c => !char.IsWhiteSpace(c) && c != ':' && c != ',').ToArray());
        if ((cleaned.Length & 1) != 0)
            throw new ArgumentException("Hex must have an even length");
        var result = new byte[cleaned.Length / 2];
        for (int i = 0; i < result.Length; i++)
            result[i] = Convert.ToByte(cleaned.Substring(i * 2, 2), 16);
        return result;
    }
}
