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

    // The native app cycles through these subtypes via DP 0x0E04 queries
    // every 300ms after sending the initial timestamp sync. Each subtype
    // asks the heater to return a different slice of its state.
    private static readonly byte[] InfoQuerySubtypes = new byte[]
    {
        0x00, 0x0A, 0x0B, 0x05, 0x07, 0x0D, 0x09, 0x0C, 0x04, 0x03, 0x02, 0x01, 0x06, 0x08
    };

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
            ConnectionChanged?.Invoke(true);

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
            // Frame 1: timestamp sync (~100ms after CCCD subscribe)
            await Task.Delay(100, ct).ConfigureAwait(false);
            var ts = BuildTimestampSyncFrame();
            Log.I("hcalory", $"TX timestamp [{ts.Length}B] {Convert.ToHexString(ts)}");
            var tsRes = await WriteRawAsync(ts).ConfigureAwait(false);
            Log.I("hcalory", $"timestamp writeRaw -> ok={tsRes.Ok} {tsRes.Error}");

            // Frames 2..N: cyclic device-info queries every 300ms
            int idx = 0;
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(300, ct).ConfigureAwait(false);
                byte subtype = InfoQuerySubtypes[idx % InfoQuerySubtypes.Length];
                var r = await WriteRawAsync(BuildInfoQueryFrame(subtype)).ConfigureAwait(false);
                if (!r.Ok)
                    Log.W("hcalory", $"info query subtype=0x{subtype:X2} FAILED: {r.Error}");
                else if (idx < 3)
                    Log.I("hcalory", $"info query subtype=0x{subtype:X2} ok");
                idx++;
            }
        }
        catch (OperationCanceledException) { /* expected on disconnect */ }
        catch (Exception ex)
        {
            Log.W("hcalory", $"post-connect sequence threw: {ex.Message}");
        }
    }

    // --- Commands ----------------------------------------------------

    public Task<ProtocolResult> StartAsync() =>
        WriteDpAsync(TuyaBleFrame.DpEnum(Dpc.Mode, Dpc.ModeAuto));

    public Task<ProtocolResult> StopAsync() =>
        WriteDpAsync(TuyaBleFrame.DpEnum(Dpc.Mode, Dpc.ModeStandby));

    public Task<ProtocolResult> VentAsync() =>
        WriteDpAsync(TuyaBleFrame.DpEnum(Dpc.Mode, Dpc.ModeWind));

    public Task<ProtocolResult> SetTargetCAsync(int celsius) =>
        WriteDpAsync(TuyaBleFrame.DpValue(Dpc.TargetTemp, celsius));

    public async Task<ProtocolResult> SetGearAsync(int gear)
    {
        var r = await WriteDpAsync(TuyaBleFrame.DpEnum(Dpc.Mode, Dpc.ModeManual)).ConfigureAwait(false);
        if (!r.Ok) return r;
        return await WriteDpAsync(TuyaBleFrame.DpValue(Dpc.TargetTemp, gear)).ConfigureAwait(false);
    }

    public Task<ProtocolResult> SetAltitudeMAsync(int metres) =>
        WriteDpAsync(TuyaBleFrame.DpValue(Dpc.Altitude, metres));

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
        if (ch == null) return ProtocolResult.Fail("not connected");
        try
        {
            RawFrameReceived?.Invoke(bytes);
            var writer = new DataWriter();
            writer.WriteBytes(bytes);
            var status = await ch.WriteValueAsync(writer.DetachBuffer(),
                GattWriteOption.WriteWithoutResponse).AsTask().ConfigureAwait(false);
            return status == GattCommunicationStatus.Success
                ? ProtocolResult.Success
                : ProtocolResult.Fail($"write returned {status}");
        }
        catch (Exception ex) { return ProtocolResult.Fail(ex.Message); }
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

    // --- Notification ingest ----------------------------------------

    private void OnNotify(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var bytes = new byte[args.CharacteristicValue.Length];
        using var r = DataReader.FromBuffer(args.CharacteristicValue);
        r.ReadBytes(bytes);
        Log.I("hcalory", $"RX [{bytes.Length}B] {Convert.ToHexString(bytes)}");
        RawFrameReceived?.Invoke(bytes);

        var frame = TuyaBleFrame.Decode(bytes);
        if (frame == null) return;

        var t = Telemetry;
        bool changed = false;
        foreach (var dp in frame.Dps)
        {
            var next = ApplyDp(t, dp);
            if (next != null) { t = next; changed = true; }
        }
        if (changed)
        {
            Telemetry = t with { UpdatedAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };
            TelemetryChanged?.Invoke(Telemetry);
        }
    }

    private static CommonTelemetry? ApplyDp(CommonTelemetry t, TuyaBleFrame.Dp dp) => dp.Id switch
    {
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
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
        {
            Log.I("hcalory", "peer disconnected");
            _keepaliveCts?.Cancel();
            IsConnected = false;
            ConnectionChanged?.Invoke(false);
        }
    }

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
