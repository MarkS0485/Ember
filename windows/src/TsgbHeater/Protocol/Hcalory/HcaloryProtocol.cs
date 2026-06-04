using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;
using TsgbHeater.Services;

namespace TsgbHeater.Protocol.Hcalory;

// HCalory (Tuya BLE) driver. Owns its own BluetoothLEDevice — separate
// from HeaterClient's HeatGenie session because the GATT profile, write
// semantics and frame codec are all different.
//
// GATT profile: legacy (FFF0/FFF1/FFF2) or custom (BD39/BDF8/BDF7). We
// try legacy first; fall back to custom. Write type: WriteWithoutResponse.
//
// Post-connect: Tuya BLE devices drop idle clients after ~6s. The native
// HCalory app never goes silent — it pumps a timestamp sync immediately
// after subscribing, then info-query frames every 300ms in a round-robin
// over a fixed subtype list. We mirror that behaviour here. See
// OLDSRC/hcalory/POST_CONNECT_SEQUENCE.md for the captured frame layouts.
//
// Frame format is hand-crafted bytes (see BuildTimestampSyncFrame and
// BuildInfoQueryFrame). The wire layout has a 7-byte fixed header, a
// 16-bit DP id, 1-byte type, 16-bit length, then the value. Not stock
// Tuya BLE — HCalory-specific.
public sealed class HcaloryProtocol : IHeaterProtocol, IAsyncDisposable
{
    public ProtocolKind       Kind         => ProtocolKind.Hcalory;
    public HeaterCapabilities Capabilities => HeaterCapabilities.Hcalory;

    public bool IsConnected { get; private set; }
    public event Action<bool>?            ConnectionChanged;
    public event Action<CommonTelemetry>? TelemetryChanged;
    public event Action<byte[]>?          RawFrameReceived;

    public CommonTelemetry Telemetry { get; private set; } = CommonTelemetry.Empty;

    private BluetoothLEDevice?       _device;
    private GattCharacteristic?      _writeChar;
    private GattCharacteristic?      _notifyChar;
    private string?                  _currentMac;
    private int                      _seq;
    private CancellationTokenSource? _keepaliveCts;

    // BLE login PIN. The heater answers every pre-auth query with a 0x0C
    // "authenticate me" frame and drops idle/unauthed clients; it only
    // streams real telemetry (DP 0x03) once we send the 0x0C login. The
    // native app defaults to "0000" when no PIN is stored (h8/t.java) — and
    // the official app never prompts to set one, so this is effectively a
    // fixed firmware handshake constant.
    public string BlePin { get; set; } = "0000";
    private bool _authed;
    private long _lastLoginSentMs;

    // Echoed device state, captured from each 0x03 status frame so control
    // commands can replay the heater's current display unit / setpoint.
    private bool _lastTempUnitF;
    private int  _lastTargetDisp;   // target temp in the heater's display unit

    // --- Lifecycle ---------------------------------------------------

    public async Task<ProtocolResult> ConnectAsync(string mac)
    {
        try
        {
            if (_device != null && string.Equals(_currentMac, mac, StringComparison.OrdinalIgnoreCase))
                return ProtocolResult.Success;
            if (_device != null) await DisconnectAsync().ConfigureAwait(false);

            ulong addr = ParseMac(mac);
            _currentMac = mac;
            Log.I("hcalory", $"ConnectAsync({mac})");
            var dev = await BluetoothLEDevice.FromBluetoothAddressAsync(addr).AsTask().ConfigureAwait(false);
            if (dev == null) return ProtocolResult.Fail("BluetoothLEDevice.FromBluetoothAddress returned null");
            _device = dev;
            dev.ConnectionStatusChanged += OnConnectionStatusChanged;

            // Discover services. Probe legacy (FFF0) first, custom (BD39) second.
            var (svc, wUuid, nUuid) = await PickServiceAsync(dev).ConfigureAwait(false);
            if (svc == null) return ProtocolResult.Fail("neither Tuya service found on this device");
            Log.I("hcalory", $"matched service {svc.Uuid}");

            var wRes = await svc.GetCharacteristicsForUuidAsync(wUuid).AsTask().ConfigureAwait(false);
            var nRes = await svc.GetCharacteristicsForUuidAsync(nUuid).AsTask().ConfigureAwait(false);
            if (wRes.Status != GattCommunicationStatus.Success || wRes.Characteristics.Count == 0)
                return ProtocolResult.Fail($"write char {wUuid} not present");
            if (nRes.Status != GattCommunicationStatus.Success || nRes.Characteristics.Count == 0)
                return ProtocolResult.Fail($"notify char {nUuid} not present");
            _writeChar  = wRes.Characteristics[0];
            _notifyChar = nRes.Characteristics[0];

            _notifyChar.ValueChanged += OnNotify;
            var ccc = await _notifyChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify).AsTask().ConfigureAwait(false);
            if (ccc != GattCommunicationStatus.Success)
                return ProtocolResult.Fail($"enable-notify CCCD write returned {ccc}");
            Log.I("hcalory", "CCCD subscribed — starting post-connect sequence");

            IsConnected = true;
            _connectedAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            ConnectionChanged?.Invoke(true);
            Log.I("hcalory", $"=== CONNECTED to {mac} @ {DateTime.Now:HH:mm:ss.fff} ===");

            // Kick off the timestamp sync + 300ms info-query loop. Fire-
            // and-forget — it runs on its own task until DisconnectAsync
            // cancels it. Without this, the heater drops us at ~6s.
            _keepaliveCts = new CancellationTokenSource();
            _ = Task.Run(() => RunPostConnectSequenceAsync(_keepaliveCts.Token));

            return ProtocolResult.Success;
        }
        catch (Exception ex)
        {
            Log.W("hcalory", $"ConnectAsync threw: {ex.GetType().Name}: {ex.Message}");
            return ProtocolResult.Fail($"{ex.GetType().Name}: {ex.Message}");
        }
    }

    public async Task<ProtocolResult> DisconnectAsync()
    {
        try
        {
            _keepaliveCts?.Cancel();
            _keepaliveCts?.Dispose();
            _keepaliveCts = null;
            if (_notifyChar != null) _notifyChar.ValueChanged -= OnNotify;
            if (_device != null) _device.ConnectionStatusChanged -= OnConnectionStatusChanged;
            _device?.Dispose();
            _device     = null;
            _writeChar  = null;
            _notifyChar = null;
            _currentMac = null;
            IsConnected = false;
            ConnectionChanged?.Invoke(false);
            await Task.CompletedTask;
            return ProtocolResult.Success;
        }
        catch (Exception ex) { return ProtocolResult.Fail(ex.Message); }
    }

    public ValueTask DisposeAsync() => new(DisconnectAsync());

    // --- Post-connect handshake -------------------------------------

    private async Task RunPostConnectSequenceAsync(CancellationToken ct)
    {
        try
        {
            _authed = false;
            _lastLoginSentMs = 0;

            // Frame 1: timestamp sync (~100ms after CCCD subscribe)
            await Task.Delay(100, ct).ConfigureAwait(false);
            var ts = BuildTimestampSyncFrame();
            Log.I("hcalory", $"TX timestamp [{ts.Length}B] {Convert.ToHexString(ts)}");
            var tsRes = await WriteRawAsync(ts).ConfigureAwait(false);
            Log.I("hcalory", $"timestamp writeRaw -> ok={tsRes.Ok} {tsRes.Error}");

            // Frame 2: PIN login. Until the heater accepts it, every query
            // gets a 0x0C auth-request back and the link is dropped after
            // ~6-10s. OnNotify resends this while result==00.
            await Task.Delay(150, ct).ConfigureAwait(false);
            await SendLoginAsync().ConfigureAwait(false);

            // Steady state: poll the device-status query (subtype 0x00) ~1 Hz,
            // mirroring the native app's main-screen keepalive (z8.o.d() ->
            // k2.e.f()). Spraying all 14 subtypes made the heater answer
            // several with 0x03 frames of DIFFERENT meaning, which the status
            // parser would misread; subtype 0x00 alone yields the canonical
            // device-status frame.
            int tick = 0;
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(1000, ct).ConfigureAwait(false);
                var r = await WriteRawAsync(BuildInfoQueryFrame(0x00)).ConfigureAwait(false);
                if (!r.Ok)
                    Log.W("hcalory", $"status poll FAILED: {r.Error}");
                else if (tick < 3)
                    Log.I("hcalory", "status poll (subtype 0x00) ok");
                tick++;
            }
        }
        catch (OperationCanceledException) { /* expected on disconnect */ }
        catch (Exception ex)
        {
            Log.W("hcalory", $"post-connect sequence threw: {ex.Message}");
        }
    }

    // --- Commands ----------------------------------------------------

    // CONTROL COMMANDS — derived from the native home controller m7.k.G(),
    // which maps each mode to a specific k2.e builder (NOT a generic mode-list):
    //   STANDBY -> e.d()  AUTO -> e.b()  MANUAL -> e.p()  NATURAL_WIND -> e.p()
    // setTarget (DP 0x06) and gear (DP 0x07) are caller-confirmed from the +/-
    // handler. start/stop/vent are decompile-exact but NOT yet radio-verified.

    private int UnitWire() => _lastTempUnitF ? 1 : 0;

    // Local clock as [HH, MM, SS, DOW] — the 4-byte value the native mode
    // builders embed (ISO DOW: Mon=1..Sun=7).
    private static byte[] ClockBytes()
    {
        var now = DateTime.Now;
        byte dow = (byte)((int)now.DayOfWeek == 0 ? 7 : (int)now.DayOfWeek);
        return new[] { (byte)now.Hour, (byte)now.Minute, (byte)now.Second, dow };
    }

    // Complex-pipeline frame, matching the native r()/d()/t()/u() builders in
    // k2.e (used by all mode switches). Header flag byte (offset 5) is 0x01 for
    // auto/standby, 0x00 for wind/manual; value excludes the trailing
    // placeholder (u() overwrites it with the payload checksum).
    // Layout: 00 02 00 01 00 <flag> <payloadLenBE> | <dpId> 00 <valLenBE> | value | csum
    private static byte[] BuildComplexFrame(int flag, int dpId, byte[] value)
    {
        var outBuf = new byte[8 + 4 + value.Length + 1];
        int payloadLen = outBuf.Length - 8;
        outBuf[0] = 0x00; outBuf[1] = 0x02; outBuf[2] = 0x00; outBuf[3] = 0x01; outBuf[4] = 0x00;
        outBuf[5] = (byte)flag;
        outBuf[6] = (byte)((payloadLen >> 8) & 0xFF);
        outBuf[7] = (byte)(payloadLen & 0xFF);
        outBuf[8] = (byte)dpId;
        outBuf[9] = 0x00;
        outBuf[10] = (byte)((value.Length >> 8) & 0xFF);
        outBuf[11] = (byte)(value.Length & 0xFF);
        System.Buffer.BlockCopy(value, 0, outBuf, 12, value.Length);
        int s = 0;
        for (int i = 8; i < outBuf.Length - 1; i++) s = (s + outBuf[i]) & 0xFF;
        outBuf[^1] = (byte)s;
        return outBuf;
    }

    // POWER / MODE via DP 0x08. The earlier DP 0x05 complex frames were ACKed
    // but inert on the wire (DP 0x05 is the schedule/scene channel, not mode).
    // The working control DPs are the low ids: temp 0x06, gear 0x07 (both
    // confirmed). DP 0x08 — native e.k() = `...0608 00 00 01 00` — is the
    // power/mode command. Probing value: start=01, stop=00, vent=02.
    private Task<ProtocolResult> PowerDpAsync(int value, string label)
    {
        Log.I("hcalory", $"BTN {label} -> DP08 value=0x{value:X2}");
        var frame = TuyaBleFrame.EncodeSingleDp(0,
            new TuyaBleFrame.Dp(0x08, TuyaBleFrame.DpTypeRaw, new[] { (byte)value }));
        return WriteRawAsync(frame);
    }

    public Task<ProtocolResult> StartAsync() => PowerDpAsync(0x01, "START HEAT");
    public Task<ProtocolResult> StopAsync()  => PowerDpAsync(0x00, "STOP HEAT");
    public Task<ProtocolResult> VentAsync()  => PowerDpAsync(0x02, "BLOWER ONLY");

    private static byte[] Concat(byte[] a, byte[] b)
    {
        var r = new byte[a.Length + b.Length];
        System.Buffer.BlockCopy(a, 0, r, 0, a.Length);
        System.Buffer.BlockCopy(b, 0, r, a.Length, b.Length);
        return r;
    }

    public Task<ProtocolResult> SetTargetCAsync(int celsius)
    {
        // Native J(temp, unit): standalone DP 0x06, value = [temp, unit].
        // Confirmed from the +/- button handler — high-confidence.
        int disp = _lastTempUnitF ? (int)Math.Round(celsius * 9.0 / 5.0 + 32) : celsius;
        disp = Math.Clamp(disp, 0, 255);
        Log.I("hcalory", $"CMD setTarget {celsius}C -> DP0x06 disp={disp} unit={UnitWire()}");
        var frame = TuyaBleFrame.EncodeSingleDp(0,
            new TuyaBleFrame.Dp(0x06, TuyaBleFrame.DpTypeRaw, new[] { (byte)disp, (byte)UnitWire() }));
        return WriteRawAsync(frame);
    }

    public async Task<ProtocolResult> SetGearAsync(int gear)
    {
        // Native I() — standalone DP 0x07, single-byte gear value.
        var frame = TuyaBleFrame.EncodeSingleDp(0,
            new TuyaBleFrame.Dp(0x07, TuyaBleFrame.DpTypeRaw, new[] { (byte)gear }));
        return await WriteRawAsync(frame).ConfigureAwait(false);
    }

    public Task<ProtocolResult> SetAltitudeMAsync(int metres) =>
        // Native H() altitude is DP 0x0909 with sign byte + 2-byte magnitude
        // + unit byte — more involved than a 1-byte enum. Send via the
        // raw helper so the magnitude doesn't get truncated.
        WriteRawAsync(BuildAltitudeFrame(metres));

    public Task<ProtocolResult> PulsePumpAsync(int seconds) =>
        Task.FromResult(ProtocolResult.Unsupported("manual pump"));

    public async Task<ProtocolResult> SendRawAsync(string opcode, byte[] payload)
    {
        try
        {
            byte dpid = (byte)Convert.ToInt32(opcode[^2..], 16);
            var dp = new TuyaBleFrame.Dp(dpid, TuyaBleFrame.DpTypeRaw, payload);
            var bytes = TuyaBleFrame.EncodeSingleDp((byte)(System.Threading.Interlocked.Increment(ref _seq) & 0xFF), dp);
            return await WriteRawAsync(bytes).ConfigureAwait(false);
        }
        catch (Exception ex) { return ProtocolResult.Fail(ex.Message); }
    }

    // --- DP / raw write helpers -------------------------------------

    private async Task<ProtocolResult> WriteDpAsync(TuyaBleFrame.Dp dp)
    {
        var bytes = TuyaBleFrame.EncodeSingleDp(
            (byte)(System.Threading.Interlocked.Increment(ref _seq) & 0xFF), dp);
        return await WriteRawAsync(bytes).ConfigureAwait(false);
    }

    private async Task<ProtocolResult> WriteRawAsync(byte[] bytes)
    {
        var ch = _writeChar;
        if (ch == null)
        {
            Log.W("hcalory", $"TX dropped — not connected. wanted to send [{bytes.Length}B] {Convert.ToHexString(bytes)}");
            return ProtocolResult.Fail("not connected");
        }
        try
        {
            Log.I("hcalory", $"TX [{bytes.Length}B] {Convert.ToHexString(bytes)}");
            RawFrameReceived?.Invoke(bytes);
            var writer = new DataWriter();
            writer.WriteBytes(bytes);
            var status = await ch.WriteValueAsync(writer.DetachBuffer(),
                GattWriteOption.WriteWithoutResponse).AsTask().ConfigureAwait(false);
            if (status != GattCommunicationStatus.Success)
                Log.W("hcalory", $"TX gatt status={status} for last frame");
            return status == GattCommunicationStatus.Success
                ? ProtocolResult.Success
                : ProtocolResult.Fail($"write returned {status}");
        }
        catch (Exception ex)
        {
            Log.W("hcalory", $"TX threw: {ex.GetType().Name}: {ex.Message}");
            return ProtocolResult.Fail(ex.Message);
        }
    }

    // Build an HCalory simple-pipeline frame for a single-byte value
    // setter. Mirrors `j2.j.b.a("00 02 00 01 00 01 00 [HI][LO][TYPE]
    // 0001 [VAL]")` in the decompile.
    //
    // Layout (12 bytes):
    //   0..6  : 00 02 00 01 00 01 00       (7-byte fixed header)
    //   7,8   : DP id high / low
    //   9     : DP type byte
    //   10,11 : value length = 0001
    //   12    : value
    //   13    : checksum
    private static byte[] BuildSimpleSetFrame(byte dpHi, byte dpLo, byte type, byte valueByte)
    {
        var b = new byte[14];
        b[0] = 0x00; b[1] = 0x02;
        b[2] = 0x00; b[3] = 0x01; b[4] = 0x00; b[5] = 0x01; b[6] = 0x00;
        b[7]  = dpHi; b[8] = dpLo;
        b[9]  = type;
        b[10] = 0x00; b[11] = 0x01;     // 1-byte value
        b[12] = valueByte;
        b[13] = ChecksumPayload(b);
        return b;
    }

    // Altitude via DP 0x0909, type 0x00, value bytes = sign + |altM|_BE16 + unit.
    // From native H(int, fVar) in k2/e.java:218. Sign byte 0x01 = negative
    // (altitude below sea level), 0x00 = positive. Unit byte 0x00 = m,
    // 0x01 = ft — we always submit metres so the heater stores the canonical
    // SI value; display conversion happens UI-side.
    //
    // Layout (17 bytes incl. checksum):
    //   0..6  : 00 02 00 01 00 01 00
    //   7,8   : 09 09                      (DP id)
    //   9     : 00                          (type)
    //   10,11 : 00 04                       (value length)
    //   12    : sign byte
    //   13,14 : abs(metres) big-endian
    //   15    : unit (m)
    //   16    : checksum
    private static byte[] BuildAltitudeFrame(int metres)
    {
        int abs = Math.Abs(metres);
        byte sign = (byte)(metres < 0 ? 0x01 : 0x00);
        var b = new byte[17];
        b[0] = 0x00; b[1] = 0x02;
        b[2] = 0x00; b[3] = 0x01; b[4] = 0x00; b[5] = 0x01; b[6] = 0x00;
        b[7] = 0x09; b[8] = 0x09;
        b[9] = 0x00;
        b[10] = 0x00; b[11] = 0x04;
        b[12] = sign;
        b[13] = (byte)((abs >> 8) & 0xFF);
        b[14] = (byte)(abs & 0xFF);
        b[15] = 0x00;
        b[16] = ChecksumPayload(b);
        return b;
    }

    // --- Raw frame builders (HCalory wire format) -------------------
    //
    // Reverse-engineered from k2/e.java + j2/j.java in the decompile —
    // earlier interpretations were off:
    //
    //   * Info query payload is 8 zero bytes, NOT 18 — total wire 22B
    //   * Every frame ends with a 1-byte checksum: (sum of bytes 8..N-1) & 0xFF
    //   * Timestamp value is HH MM SS DOW when on the BD39 service family
    //     (which we always hit on this heater), NOT a Unix epoch.
    //   * DOW is Monday=1..Sunday=7 (ISO), not Java Calendar's 1=Sun..7=Sat.
    //
    // The `a()` wrapper in j2.j.b takes a hex string ending at byte N-1
    // and appends the checksum hex char pair → wire bytes 0..N. We bake
    // that into the builders.

    private static byte[] BuildTimestampSyncFrame()
    {
        // Layout (18 bytes):
        //   0..6   : 00 02 00 01 00 01 00       (7-byte fixed header)
        //   7,8    : 0A 0A                       (cmd / DP id)
        //   9..11  : 00 00 05                    (value descriptor: type=0, len=5)
        //  12..15  : HH MM SS DOW                (local time, ISO DOW)
        //  16     : 00                           (trailing pad)
        //  17     : checksum = sum(bytes 8..16) & 0xFF
        var now = DateTime.Now;
        byte hh = (byte)now.Hour;
        byte mm = (byte)now.Minute;
        byte ss = (byte)now.Second;
        byte dow = (byte)((int)now.DayOfWeek == 0 ? 7 : (int)now.DayOfWeek);

        var b = new byte[18];
        b[0] = 0x00; b[1] = 0x02;
        b[2] = 0x00; b[3] = 0x01; b[4] = 0x00; b[5] = 0x01; b[6] = 0x00;
        b[7] = 0x0A; b[8] = 0x0A;
        b[9] = 0x00;
        b[10] = 0x00; b[11] = 0x05;
        b[12] = hh; b[13] = mm; b[14] = ss; b[15] = dow;
        b[16] = 0x00;
        b[17] = ChecksumPayload(b);
        return b;
    }

    private static byte[] BuildInfoQueryFrame(byte subtype)
    {
        // Layout (22 bytes):
        //   0..6   : 00 02 00 01 00 01 00
        //   7,8    : 0E 04
        //   9..11  : 00 00 09
        //  12..19  : 00 x 8
        //  20     : subtype
        //  21     : checksum
        var b = new byte[22];
        b[0] = 0x00; b[1] = 0x02;
        b[2] = 0x00; b[3] = 0x01; b[4] = 0x00; b[5] = 0x01; b[6] = 0x00;
        b[7] = 0x0E; b[8] = 0x04;
        b[9] = 0x00;
        b[10] = 0x00; b[11] = 0x09;
        // bytes 12..19 stay 0
        b[20] = subtype;
        b[21] = ChecksumPayload(b);
        return b;
    }

    // Login / authentication frame (k2.e.a() in the decompile):
    //   00 02 00 01 00 01 00 | 0A | 0C 00 00 05 | 01 <p0> <p1> <p2> <p3> | csum
    // DP id 0x0C, type 0x00, value = [0x01 login opcode] + 4 PIN digits, one
    // digit per byte (PIN "0000" -> 00 00 00 00). The heater replies with
    // another 0x0C carrying result: 00=awaiting, 01=success, 02=wrong.
    private byte[] BuildLoginFrame(string pin)
    {
        var digits = pin.PadLeft(4, '0');
        digits = digits.Substring(digits.Length - 4);
        var b = new byte[18];
        b[0] = 0x00; b[1] = 0x02;
        b[2] = 0x00; b[3] = 0x01; b[4] = 0x00; b[5] = 0x01; b[6] = 0x00;
        b[7] = 0x0A;
        b[8] = 0x0C;            // dpId = auth
        b[9] = 0x00;            // dpType
        b[10] = 0x00; b[11] = 0x05;  // value length = 5
        b[12] = 0x01;          // login opcode
        for (int i = 0; i < 4; i++) b[13 + i] = (byte)(digits[i] - '0');
        b[17] = ChecksumPayload(b);
        return b;
    }

    private async Task SendLoginAsync()
    {
        _lastLoginSentMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var f = BuildLoginFrame(BlePin);
        Log.I("hcalory", $"TX login pin={BlePin} [{f.Length}B] {Convert.ToHexString(f)}");
        var r = await WriteRawAsync(f).ConfigureAwait(false);
        Log.I("hcalory", $"login writeRaw -> ok={r.Ok} {r.Error}");
    }

    // Checksum = sum of payload bytes (offset 8 through N-2) mod 256.
    // Replicates j2.j.b.f() in the decompile.
    private static byte ChecksumPayload(byte[] frame)
    {
        int s = 0;
        for (int i = 8; i < frame.Length - 1; i++) s = (s + frame[i]) & 0xFF;
        return (byte)s;
    }

    /// <summary>
    /// Test hook: send a hand-crafted frame, computing and appending the
    /// checksum byte. Pass the frame WITHOUT the trailing checksum slot;
    /// we'll add it (so for an 18-byte wire frame, pass 17 bytes).
    /// </summary>
    public Task<ProtocolResult> SendRawTestFrameAsync(byte[] frameWithoutChecksum)
    {
        var b = new byte[frameWithoutChecksum.Length + 1];
        Array.Copy(frameWithoutChecksum, b, frameWithoutChecksum.Length);
        b[^1] = ChecksumPayload(b);
        Log.I("hcalory", $"TX testFrame [{b.Length}B] {Convert.ToHexString(b)}");
        return WriteRawAsync(b);
    }

    /// <summary>Debug: send bytes EXACTLY as given (no checksum recompute).</summary>
    public Task<ProtocolResult> DebugSendExactAsync(byte[] bytes)
    {
        Log.I("hcalory", $"TX exact [{bytes.Length}B] {Convert.ToHexString(bytes)}");
        return WriteRawAsync(bytes);
    }

    // --- Notification ingest ----------------------------------------

    private void OnNotify(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var bytes = new byte[args.CharacteristicValue.Length];
        using var r = DataReader.FromBuffer(args.CharacteristicValue);
        r.ReadBytes(bytes);
        Log.I("hcalory", $"RX [{bytes.Length}B] {Convert.ToHexString(bytes)}");
        RawFrameReceived?.Invoke(bytes);

        var frame = TuyaBleFrame.Decode(bytes);
        if (frame == null)
        {
            Log.W("hcalory", "RX frame failed decode (bad checksum/length?) — dropping");
            return;
        }

        var t = Telemetry;
        bool changed = false;
        foreach (var dp in frame.Dps)
        {
            if (dp.Id == Dpc.Auth) { HandleAuthDp(dp); continue; }
            var next = ApplyDp(t, dp);
            if (next != null) { t = next; changed = true; }
        }
        if (changed)
        {
            Telemetry = t with { UpdatedAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };
            TelemetryChanged?.Invoke(Telemetry);
        }
    }

    private CommonTelemetry? ApplyDp(CommonTelemetry t, TuyaBleFrame.Dp dp) => dp.Id switch
    {
        Dpc.Status
            => ParseDeviceStatus03(t, dp.Value),
        Dpc.Mode when dp.Value.Length > 0
            => t with { Mode = DpModeToCommon(dp.Value[0]) },
        Dpc.TargetTemp when dp.Value.Length == 4
            => t with { TargetC = TuyaBleFrame.DpValueInt(dp) },
        Dpc.TempUnit when dp.Value.Length > 0
            => t with { TempUnitF = dp.Value[0] == 1 },
        Dpc.Altitude when dp.Value.Length == 4
            => t with { AltitudeM = TuyaBleFrame.DpValueInt(dp) },
        _ => null,
    };

    // 0x0C auth-response handler. value = [opCode][..][..][..][..][result].
    // result: 0x00 awaiting PIN, 0x01 success, 0x02 wrong PIN.
    private void HandleAuthDp(TuyaBleFrame.Dp dp)
    {
        int result = dp.Value.Length >= 6 ? dp.Value[5] : -1;
        switch (result)
        {
            case 0x01:
                if (!_authed) { _authed = true; Log.I("hcalory", $"AUTH ok (pin={BlePin})"); }
                break;
            case 0x02:
                Log.W("hcalory", $"AUTH FAILED: wrong PIN ({BlePin}). Heater needs its BLE password.");
                break;
            case 0x00:
                if (!_authed &&
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - _lastLoginSentMs > 2500)
                {
                    _ = Task.Run(SendLoginAsync);
                }
                break;
            default:
                Log.I("hcalory", $"AUTH 0x0C result={result} value={Convert.ToHexString(dp.Value)}");
                break;
        }
    }

    // Faithful port of k2.e.x() — decodes the DP 0x03 device-status payload.
    // Offsets are into the DP value (v[0] = first value byte). Temperatures
    // are reported in the heater's CURRENT display unit (v[25]); we convert
    // to Celsius for CommonTelemetry, which is canonical °C.
    private CommonTelemetry? ParseDeviceStatus03(CommonTelemetry t, byte[] v)
    {
        if (v.Length < 26) return null;
        int U16(int i) => (v[i] << 8) | v[i + 1];

        int statusByte     = v[8];   // bit-packed: fan/plug/pump + op-state; 0xFF = fault
        int deviceStateRaw = v[9];   // 00 standby,01 auto-temp,02 manual-gear,03 wind
        int tempOrGear     = v[10];
        double voltage     = U16(12) / 10.0;
        int shellRaw       = U16(15);
        int ambRaw         = U16(18);
        double shellDisp   = (v[14] == 0x01 && shellRaw != 0 ? -shellRaw : shellRaw) / 10.0;
        double ambDisp     = (v[17] == 0x01 && ambRaw  != 0 ? -ambRaw  : ambRaw)  / 10.0;
        bool   tempUnitF   = v[25] == 0x01;
        int    altitude    = ((v[20] << 16) | (v[21] << 8) | v[22]) / 10;

        bool fault = statusByte == 0xFF;
        // operative state from bits 6,7 of the status byte (k2.e.x reverses
        // the 8-bit string then reads chars 6,7): op = (bit6<<1)|bit7.
        int opState = (((statusByte >> 6) & 0x01) << 1) | ((statusByte >> 7) & 0x01);

        CommonRunningMode mode =
            fault                                              ? CommonRunningMode.Fault
            : (opState == 3 || deviceStateRaw == 0x03)         ? CommonRunningMode.Vent
            : opState == 1                                     ? CommonRunningMode.Running
            : opState == 2                                     ? CommonRunningMode.Shutdown  // cooling engine body
            : (deviceStateRaw == 0x01 || deviceStateRaw == 0x02) ? CommonRunningMode.Running
            :                                                    CommonRunningMode.Standby;

        double ToC(double disp) => tempUnitF ? (disp - 32.0) * 5.0 / 9.0 : disp;

        bool isGearMode = deviceStateRaw == 0x02;
        int? errorNum   = fault ? deviceStateRaw : (int?)null;

        // Cache state for command replay (display-unit + setpoint).
        _lastTempUnitF  = tempUnitF;
        _lastTargetDisp = tempOrGear;

        return t with
        {
            Mode      = mode,
            ModeLabel = errorNum is int e ? $"Fault E{e:D2}" : null,
            AmbientC  = ToC(ambDisp),
            HousingC  = ToC(shellDisp),
            TargetC   = !isGearMode && tempOrGear > 0 ? ToC(tempOrGear) : t.TargetC,
            AimGear   = isGearMode ? tempOrGear : t.AimGear,
            BatteryV  = voltage,
            AltitudeM = altitude,
            TempUnitF = tempUnitF,
            FaultBits = errorNum,
        };
    }

    private static CommonRunningMode DpModeToCommon(byte v) => v switch
    {
        Dpc.ModeStandby => CommonRunningMode.Standby,
        Dpc.ModeAuto    => CommonRunningMode.Running,
        Dpc.ModeManual  => CommonRunningMode.Running,
        Dpc.ModeWind    => CommonRunningMode.Vent,
        Dpc.ModeFault   => CommonRunningMode.Fault,
        _               => CommonRunningMode.Unknown,
    };

    private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        var now = DateTime.Now.ToString("HH:mm:ss.fff");
        Log.I("hcalory", $"ConnectionStatusChanged @ {now}: status={sender.ConnectionStatus} for {_currentMac}");
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
        {
            Log.W("hcalory", $"peer disconnected @ {now} — keepalive cancelled. Connection was alive {(_connectedAtMs > 0 ? (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - _connectedAtMs) : 0)}ms");
            _keepaliveCts?.Cancel();
            IsConnected = false;
            ConnectionChanged?.Invoke(false);
        }
    }

    // Timestamp of last successful connect — lets us calculate how long
    // we stayed alive each time the peer drops us. Useful for diagnosing
    // whether commands cause an immediate disconnect vs an eventual timeout.
    private long _connectedAtMs;

    // --- Service / characteristic discovery -------------------------

    private async Task<(GattDeviceService? svc, Guid wUuid, Guid nUuid)> PickServiceAsync(BluetoothLEDevice dev)
    {
        var res = await dev.GetGattServicesAsync(BluetoothCacheMode.Uncached).AsTask().ConfigureAwait(false);
        if (res.Status != GattCommunicationStatus.Success) return (null, Guid.Empty, Guid.Empty);
        foreach (var s in res.Services)
        {
            if (s.Uuid == ServiceLegacy) return (s, WriteCharLegacy, NotifyCharLegacy);
            if (s.Uuid == ServiceCustom) return (s, WriteCharCustom, NotifyCharCustom);
        }
        return (null, Guid.Empty, Guid.Empty);
    }

    private static ulong ParseMac(string mac)
    {
        var hex = mac.Replace(":", "").Replace("-", "").Replace(" ", "");
        return Convert.ToUInt64(hex, 16);
    }

    // --- DP catalog --------------------------------------------------

    private static class Dpc
    {
        public const byte Auth       = 0x0C;   // login/auth request+response (PIN)
        public const byte Status     = 0x03;   // device-status push/response (k2.e.x payload)
        public const byte Mode       = 0x01;
        public const byte TempUnit   = 0x0B;
        public const byte TargetTemp = 0x05;
        public const byte Altitude   = 0x09;

        public const byte ModeStandby = 0x00;
        public const byte ModeAuto    = 0x01;
        public const byte ModeManual  = 0x02;
        public const byte ModeWind    = 0x03;
        public const byte ModeFault   = 0x04;
    }

    // --- GATT UUIDs --------------------------------------------------

    public static readonly Guid ServiceLegacy     = new("0000FFF0-0000-1000-8000-00805F9B34FB");
    public static readonly Guid WriteCharLegacy   = new("0000FFF2-0000-1000-8000-00805F9B34FB");
    public static readonly Guid NotifyCharLegacy  = new("0000FFF1-0000-1000-8000-00805F9B34FB");

    public static readonly Guid ServiceCustom     = new("0000BD39-0000-1000-8000-00805F9B34FB");
    public static readonly Guid WriteCharCustom   = new("0000BDF7-0000-1000-8000-00805F9B34FB");
    public static readonly Guid NotifyCharCustom  = new("0000BDF8-0000-1000-8000-00805F9B34FB");
}
