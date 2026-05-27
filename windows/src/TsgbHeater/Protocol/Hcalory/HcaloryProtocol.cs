using System.Collections.Concurrent;
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
// Frame format: TuyaBleFrame. DP catalog mirrors the Android driver —
// see Dpc below. Refined against the live heater later.
public sealed class HcaloryProtocol : IHeaterProtocol, IAsyncDisposable
{
    public ProtocolKind       Kind         => ProtocolKind.Hcalory;
    public HeaterCapabilities Capabilities => HeaterCapabilities.Hcalory;

    public bool IsConnected { get; private set; }
    public event Action<bool>?            ConnectionChanged;
    public event Action<CommonTelemetry>? TelemetryChanged;
    public event Action<byte[]>?          RawFrameReceived;

    public CommonTelemetry Telemetry { get; private set; } = CommonTelemetry.Empty;

    private BluetoothLEDevice?   _device;
    private GattCharacteristic?  _writeChar;
    private GattCharacteristic?  _notifyChar;
    private string?              _currentMac;
    private int                  _seq;

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

            // Discover services. Probe legacy first, custom second.
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

            IsConnected = true;
            ConnectionChanged?.Invoke(true);
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
        // Manual gear lives behind MANUAL mode. Two writes: switch mode
        // then set the value. Catalog notes target-temp DP may double
        // as gear in manual mode — confirm against real device.
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

    // --- Notification ingest ----------------------------------------

    private void OnNotify(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var bytes = new byte[args.CharacteristicValue.Length];
        using var r = DataReader.FromBuffer(args.CharacteristicValue);
        r.ReadBytes(bytes);
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
        // Accept colon-separated (XX:XX:XX:XX:XX:XX) or bare hex (XXXXXXXXXXXX).
        var hex = mac.Replace(":", "").Replace("-", "").Replace(" ", "");
        return Convert.ToUInt64(hex, 16);
    }

    // --- DP catalog --------------------------------------------------
    //
    // Pulled from OLDSRC/hcalory/DP_CATALOG.md. Values marked "?" need
    // confirming against a live heater; the rest are high-confidence.
    private static class Dpc
    {
        // Control surface — write
        public const byte Mode       = 0x01;  // enum
        public const byte TempUnit   = 0x0B;  // enum: 0=C, 1=F
        public const byte TargetTemp = 0x05;  // value (int)
        public const byte Altitude   = 0x09;  // value. Real id is 0x0909
                                              // (two-byte DP id?). Confirm on device.

        // Mode enum values (DP 0x01)
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
