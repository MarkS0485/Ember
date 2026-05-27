using System.IO;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Storage.Streams;
using TsgbHeater.Data;
using TsgbHeater.Protocol;
using TsgbHeater.Protocol.Hcalory;
using TsgbHeater.Services;

namespace TsgbHeater.Ble;

// Single source of truth for everything BLE on the Windows side.
// Combines the responsibilities of the Android BleScanner +
// HeaterConnection + BleManager.
public sealed class HeaterClient : IAsyncDisposable
{
    // --- Public state -------------------------------------------------

    public ConnectionState State
    {
        get => _state;
        private set { if (_state != value) { _state = value; StateChanged?.Invoke(value); } }
    }
    public event Action<ConnectionState>? StateChanged;

    public HeaterTelemetry? Telemetry
    {
        get => _telemetry;
        private set { _telemetry = value; if (value != null) TelemetryChanged?.Invoke(value); }
    }
    public event Action<HeaterTelemetry>? TelemetryChanged;

    public IReadOnlyList<DiscoveredDevice> Devices => _devices.Values.ToArray();
    public event Action? DevicesChanged;

    public bool Scanning => _watcher != null;
    public event Action<bool>? ScanningChanged;

    public event Action<RawFrame>? FrameSeen;

    // Surfaced for the status row: one-line human-readable description of
    // the most recent failure (or empty when the link is healthy).
    public string LastError
    {
        get => _lastError;
        private set { if (_lastError != value) { _lastError = value; LastErrorChanged?.Invoke(value); } }
    }
    public event Action<string>? LastErrorChanged;

    public bool VerboseRx { get; set; } = false;

    // --- Internals ----------------------------------------------------

    private ConnectionState _state = ConnectionState.Idle;
    private HeaterTelemetry? _telemetry;
    private string _lastError = "";
    private readonly Dictionary<string, DiscoveredDevice> _devices = new(StringComparer.OrdinalIgnoreCase);

    private BluetoothLEAdvertisementWatcher? _watcher;

    private BluetoothLEDevice?              _device;
    private GattCharacteristic?             _writeChar;
    private GattCharacteristic?             _notifyChar;
    private string?                         _currentMac;
    private CancellationTokenSource?        _sessionCts;
    private Task?                           _keepaliveTask;

    private long _cooldownUntilTicks;
    private const int CooldownMs           = 30_000;
    private const int KeepaliveIntervalMs  = 20_000;

    // --- Protocol dispatch -------------------------------------------
    //
    // When a non-HeatGenie device is connected, we delegate ALL the BLE
    // work to an external IHeaterProtocol driver and forward its events
    // up through HeaterClient's existing event surface so ViewModels
    // don't need to know which protocol is active.
    private HcaloryProtocol?  _hcalory;
    private IHeaterProtocol?  _activeAlt;       // non-null iff active protocol != HeatGenie
    private BoundDeviceStore? _boundDevices;    // injected by ServiceLocator after construction

    /// <summary>
    /// Wire the BoundDeviceStore in. Must be called once before the first
    /// ConnectAsync — the dispatcher uses it to learn which protocol to
    /// speak for a given MAC.
    /// </summary>
    public void AttachBoundDevices(BoundDeviceStore store) => _boundDevices = store;

    // --- Scan ---------------------------------------------------------

    public void StartScan()
    {
        if (_watcher != null) return;
        _devices.Clear();
        DevicesChanged?.Invoke();
        var w = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };
        w.Received += OnAdvert;
        w.Stopped  += (_, e) =>
        {
            _watcher = null;
            ScanningChanged?.Invoke(false);
            if (e.Error != BluetoothError.Success)
                Fail($"Scan stopped with {e.Error}");
        };
        _watcher = w;
        try
        {
            w.Start();
            Log.I("ble", "Scan started");
            ScanningChanged?.Invoke(true);
        }
        catch (Exception ex) { Fail($"Scan start failed: {ex.Message}"); }
    }

    public void StopScan()
    {
        var w = _watcher;
        if (w == null) return;
        try { w.Stop(); } catch (Exception ex) { Log.W("ble", $"Scan stop ignored: {ex.Message}"); }
        _watcher = null;
        ScanningChanged?.Invoke(false);
    }

    private void OnAdvert(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs e)
    {
        string mac  = FormatMac(e.BluetoothAddress);
        string? name = string.IsNullOrEmpty(e.Advertisement.LocalName) ? null : e.Advertisement.LocalName;
        int rssi    = e.RawSignalStrengthInDBm;
        long now    = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var proto   = DetectProtocol(e.Advertisement.ServiceUuids);
        bool isHeater = proto != null
                        || name?.Contains("KZQ", StringComparison.OrdinalIgnoreCase) == true
                        || name?.Contains("HEATGENIE", StringComparison.OrdinalIgnoreCase) == true
                        || name?.Contains("HEATER", StringComparison.OrdinalIgnoreCase) == true;

        lock (_devices)
        {
            if (_devices.TryGetValue(mac, out var existing))
            {
                _devices[mac] = existing with
                {
                    Rssi          = rssi,
                    Name          = name ?? existing.Name,
                    IsKnownHeater = existing.IsKnownHeater || isHeater,
                    LastSeenAtMs  = now,
                    // Sticky: don't let a later advert that omits the
                    // service UUID clear what we already detected.
                    Protocol      = proto ?? existing.Protocol,
                };
            }
            else
            {
                _devices[mac] = new DiscoveredDevice(mac, name, rssi, isHeater, now, proto);
            }
        }
        DevicesChanged?.Invoke();
    }

    // Walk an advert's service-UUID list and return the first known
    // match. We can't rely on every heater advertising its service
    // (HeatGenie often doesn't until after GATT discovery), so a null
    // here is not a "definitely not a heater" signal.
    private static ProtocolKind? DetectProtocol(IList<Guid> uuids)
    {
        foreach (var u in uuids)
        {
            if (u == HcaloryProtocol.ServiceLegacy || u == HcaloryProtocol.ServiceCustom)
                return ProtocolKind.Hcalory;
            // HeatGenie's primary service is 181a (Environmental Sensing).
            // Some firmware advertises it pre-GATT-discovery, some doesn't.
            if (u == new Guid("0000181a-0000-1000-8000-00805F9B34FB"))
                return ProtocolKind.HeatGenie;
        }
        return null;
    }

    // --- Connect ------------------------------------------------------

    public async Task ConnectAsync(string mac)
    {
        // Outer guard: anything we miss below turns into a clean Fail()
        // instead of a process-killing dispatcher exception. The .NET 10
        // WinRT bindings throw assorted COMExceptions when Bluetooth is
        // toggling or the radio is wedged, and we'd rather show a red
        // line than crash the window.
        try
        {
            // Look up the bound device's protocol. Unknown MAC defaults to
            // HeatGenie (the historical default — every pre-multi-protocol
            // pairing was a HG heater).
            var kind = _boundDevices?.All.FirstOrDefault(b =>
                b.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase))?.Protocol
                ?? ProtocolKind.HeatGenie;

            if (kind == ProtocolKind.Hcalory)
            {
                await ConnectViaAltAsync(EnsureHcalory(), mac).ConfigureAwait(false);
            }
            else
            {
                // Tear down any non-HG session we might be holding before
                // running the HG-native connect path.
                await TearDownAltAsync().ConfigureAwait(false);
                await ConnectInnerAsync(mac).ConfigureAwait(false);
            }
        }
        catch (Exception ex) { Fail($"Connect threw: {ex.GetType().Name}: {ex.Message}"); }
    }

    // --- Alternate-protocol dispatch (HCalory and any future driver) ---

    private HcaloryProtocol EnsureHcalory()
    {
        if (_hcalory != null) return _hcalory;
        var h = new HcaloryProtocol();
        // Forward HCalory's protocol-neutral events into HeaterClient's
        // existing event surface. ViewModels don't need to know that the
        // events originated elsewhere.
        h.ConnectionChanged   += b => State = b ? ConnectionState.Ready : ConnectionState.Idle;
        h.TelemetryChanged    += t => Telemetry = ToHeaterTelemetry(t);
        h.RawFrameReceived    += b => FrameSeen?.Invoke(new RawFrame(false, b, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        _hcalory = h;
        return h;
    }

    private async Task ConnectViaAltAsync(IHeaterProtocol drv, string mac)
    {
        // Tear down the HG session if one is open — both protocols share
        // the same exported state machine, so we can only have one active
        // at a time.
        await TearDownAsync().ConfigureAwait(false);
        _activeAlt = drv;
        State = ConnectionState.Connecting;
        var r = await drv.ConnectAsync(mac).ConfigureAwait(false);
        if (!r.Ok) Fail($"Connect ({drv.Kind}): {r.Error}");
    }

    private async Task TearDownAltAsync()
    {
        if (_activeAlt != null)
        {
            try { await _activeAlt.DisconnectAsync().ConfigureAwait(false); }
            catch (Exception ex) { Log.W("ble", $"alt disconnect threw: {ex.Message}"); }
            _activeAlt = null;
        }
    }

    // Synthesize a HeatGenie-shaped HeaterTelemetry from CommonTelemetry
    // so existing ViewModels (DevicePage, AdvancePage, …) work unchanged
    // when an HCalory device is connected. Fields the source doesn't
    // publish stay null; the UI already renders "—" for those.
    private static HeaterTelemetry ToHeaterTelemetry(CommonTelemetry t) => new(
        OutletTempC:        t.OutletC,
        TargetTempC:        t.TargetC,
        FuelPumpHz:         t.PumpHz,
        FanRpm:             t.FanRpm,
        GlowPlugA:          null,
        BatteryV:           t.BatteryV,
        AmbientTempC:       t.AmbientC,
        HousingTempC:       t.HousingC,
        IntakeTempC:        t.IntakeC,
        AltitudeM:          t.AltitudeM,
        IgnitionWatts:      t.IgnitionW,
        RunningMode:        ToHeatGenieMode(t.Mode),
        FaultBits:          t.FaultBits ?? 0,
        TempUnitFahrenheit: t.TempUnitF ?? false,
        AimGear:            t.AimGear,
        UpdatedAtMs:        t.UpdatedAtMs > 0 ? t.UpdatedAtMs : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    private static RunningMode ToHeatGenieMode(CommonRunningMode m) => m switch
    {
        CommonRunningMode.Standby    => RunningMode.Standby,
        CommonRunningMode.Starting   => RunningMode.Ignition,
        CommonRunningMode.Running    => RunningMode.AutoRun,
        CommonRunningMode.Vent       => RunningMode.Ventilation,
        CommonRunningMode.BlowerOnly => RunningMode.Ventilation,
        CommonRunningMode.Shutdown   => RunningMode.Cooldown,
        CommonRunningMode.Fault      => RunningMode.Fault,
        CommonRunningMode.ManualPump => RunningMode.ManualPump,
        _                            => RunningMode.Unknown,
    };

    private async Task ConnectInnerAsync(string mac)
    {
        Log.I("ble", $"ConnectAsync({mac}) requested");

        var s = State;
        bool alive = s is ConnectionState.Connecting
                       or ConnectionState.DiscoveringServices
                       or ConnectionState.Ready;
        if (alive && string.Equals(_currentMac, mac, StringComparison.OrdinalIgnoreCase))
        {
            Log.I("ble", $"Already {s} on {mac}, no-op");
            return;
        }

        long nowTicks = Environment.TickCount64;
        if (nowTicks < _cooldownUntilTicks)
        {
            var remain = (_cooldownUntilTicks - nowTicks) / 1000;
            Fail($"Cooldown active — heater dropped us recently, wait {remain}s");
            return;
        }

        await TearDownAsync().ConfigureAwait(false);

        // Up-front sanity check: is there a Bluetooth radio at all? If
        // the user disabled BT while the app was running, we'd rather say
        // so than emit a confusing GATT error.
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null)
            {
                Fail("No Bluetooth adapter found on this PC.");
                return;
            }
            if (!adapter.IsLowEnergySupported)
            {
                Fail("This Bluetooth adapter does not support BLE.");
                return;
            }
        }
        catch (Exception ex)
        {
            Fail($"BluetoothAdapter.GetDefaultAsync threw: {ex.Message}");
            return;
        }

        ClearError();
        State = ConnectionState.Connecting;
        _currentMac = mac;

        ulong addr;
        try { addr = MacToAddress(mac); }
        catch (Exception ex)
        {
            Fail($"Bad MAC '{mac}': {ex.Message}");
            return;
        }

        BluetoothLEDevice? dev = null;
        try
        {
            dev = await BluetoothLEDevice.FromBluetoothAddressAsync(addr);
        }
        catch (Exception ex)
        {
            Fail($"FromBluetoothAddressAsync threw: {ex.GetType().Name}: {ex.Message}");
            return;
        }
        if (dev == null)
        {
            Fail($"OS returned no device for {mac}. Bluetooth disabled? Out of range? " +
                 "Try a fresh scan and Bind again so Windows has a current advert cache.");
            return;
        }
        _device = dev;
        try { dev.ConnectionStatusChanged += OnConnectionStatusChanged; }
        catch { /* harmless */ }
        Log.I("ble", $"Device handle obtained; ConnectionStatus={dev.ConnectionStatus}");

        State = ConnectionState.DiscoveringServices;

        GattDeviceServicesResult svcResult;
        try
        {
            svcResult = await dev.GetGattServicesAsync(BluetoothCacheMode.Uncached);
        }
        catch (Exception ex)
        {
            Fail($"GetGattServicesAsync threw: {ex.GetType().Name}: {ex.Message}");
            return;
        }
        Log.I("ble", $"GetGattServicesAsync → Status={svcResult.Status} " +
                     $"ProtocolError={svcResult.ProtocolError} Services={svcResult.Services.Count}");
        if (svcResult.Status != GattCommunicationStatus.Success)
        {
            Fail(DescribeServiceFailure(svcResult.Status, svcResult.ProtocolError));
            return;
        }

        GattDeviceService? svc = null;
        foreach (var s2 in svcResult.Services)
        {
            Log.I("ble", $"  service {s2.Uuid}");
            if (s2.Uuid == BleConstants.HeaterService) { svc = s2; break; }
        }
        if (svc == null)
        {
            // Fall back: pick the first service that has both a write and
            // a notify characteristic, same as the Android client.
            foreach (var s2 in svcResult.Services)
            {
                GattCharacteristicsResult charsR;
                try { charsR = await s2.GetCharacteristicsAsync(BluetoothCacheMode.Uncached); }
                catch { continue; }
                if (charsR.Status != GattCommunicationStatus.Success) continue;
                bool hasW = charsR.Characteristics.Any(c =>
                    (c.CharacteristicProperties & (GattCharacteristicProperties.Write |
                                                   GattCharacteristicProperties.WriteWithoutResponse)) != 0);
                bool hasN = charsR.Characteristics.Any(c =>
                    (c.CharacteristicProperties & (GattCharacteristicProperties.Notify |
                                                   GattCharacteristicProperties.Indicate)) != 0);
                if (hasW && hasN) { svc = s2; break; }
            }
        }
        if (svc == null)
        {
            Fail($"No usable GATT service on {mac}. Services seen: " +
                 string.Join(", ", svcResult.Services.Select(x => x.Uuid)));
            return;
        }
        Log.I("ble", $"Picked service {svc.Uuid}");

        GattCharacteristicsResult charsRes;
        try { charsRes = await svc.GetCharacteristicsAsync(BluetoothCacheMode.Uncached); }
        catch (Exception ex)
        {
            Fail($"GetCharacteristicsAsync threw: {ex.GetType().Name}: {ex.Message}");
            return;
        }
        if (charsRes.Status != GattCommunicationStatus.Success)
        {
            Fail($"GetCharacteristicsAsync failed: {charsRes.Status}");
            return;
        }
        foreach (var c in charsRes.Characteristics)
        {
            var p = c.CharacteristicProperties;
            Log.I("ble", $"  char {c.Uuid}  props={p}");
            if (_writeChar == null && (p & (GattCharacteristicProperties.Write |
                                            GattCharacteristicProperties.WriteWithoutResponse)) != 0)
                _writeChar = c;
            if (_notifyChar == null && (p & (GattCharacteristicProperties.Notify |
                                              GattCharacteristicProperties.Indicate)) != 0)
                _notifyChar = c;
        }
        if (_writeChar == null) { Fail("No writeable characteristic on the selected service"); return; }

        if (_notifyChar != null)
        {
            try { _notifyChar.ValueChanged += OnNotification; } catch { }
            GattCommunicationStatus enableStatus;
            try
            {
                enableStatus = await _notifyChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
            }
            catch (Exception ex)
            {
                Fail($"CCCD enable threw: {ex.GetType().Name}: {ex.Message}");
                return;
            }
            if (enableStatus != GattCommunicationStatus.Success)
            {
                Fail($"CCCD enable failed: {enableStatus}");
                return;
            }
            Log.I("ble", "Notifications enabled");
        }

        State = ConnectionState.Ready;
        _sessionCts = new CancellationTokenSource();
        Log.I("ble", "Link is Ready");

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(600).ConfigureAwait(false);
                await WriteAsync(FrameCodec.BuildStartTelemetryStream()).ConfigureAwait(false);
                await Task.Delay(200).ConfigureAwait(false);
                var now = DateTime.Now;
                int day = FrameCodec.DayOfWeekToVendor(now.DayOfWeek);
                await WriteAsync(FrameCodec.BuildSetClock(day, now.Hour, now.Minute)).ConfigureAwait(false);
            }
            catch (Exception ex) { Log.W("ble", $"Post-ready writes failed: {ex.Message}"); }
        });

        StartKeepalive(_sessionCts.Token);
    }

    private static string DescribeServiceFailure(GattCommunicationStatus s, byte? protoErr) => s switch
    {
        GattCommunicationStatus.Unreachable
            => "Device is not reachable. It may be out of range, asleep, or already connected to another phone. " +
               "Power-cycle the heater, then try again.",
        GattCommunicationStatus.AccessDenied
            => "Windows refused the GATT access — most likely the heater needs to be paired through " +
               "Windows Settings → Bluetooth & devices first. Open Settings, add the heater, then retry.",
        GattCommunicationStatus.ProtocolError
            => $"GATT protocol error 0x{protoErr:X2}. The heater rejected our handshake.",
        _ => $"GetGattServicesAsync failed with {s}",
    };

    public async Task DisconnectAsync()
    {
        await TearDownAltAsync().ConfigureAwait(false);
        await TearDownAsync().ConfigureAwait(false);
    }

    private async Task TearDownAsync()
    {
        try { _sessionCts?.Cancel(); } catch { }
        _keepaliveTask = null;
        _sessionCts?.Dispose();
        _sessionCts = null;

        var notify = _notifyChar;
        if (notify != null)
        {
            try
            {
                await notify.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.None);
            }
            catch { /* link may already be down */ }
            notify.ValueChanged -= OnNotification;
        }
        _notifyChar = null;
        _writeChar  = null;

        if (_device != null)
        {
            _device.ConnectionStatusChanged -= OnConnectionStatusChanged;
            _device.Dispose();
            _device = null;
        }
        _currentMac = null;
        State = ConnectionState.Idle;
    }

    private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        Log.I("ble", $"ConnectionStatusChanged → {sender.ConnectionStatus}");
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
        {
            if (State == ConnectionState.Ready)
            {
                _cooldownUntilTicks = Environment.TickCount64 + CooldownMs;
                Log.I("ble", $"Mid-session drop — applying {CooldownMs}ms cooldown");
            }
            _ = TearDownAsync();
        }
    }

    private void StartKeepalive(CancellationToken ct)
    {
        _keepaliveTask = Task.Run(async () =>
        {
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    await Task.Delay(KeepaliveIntervalMs, ct).ConfigureAwait(false);
                    if (State != ConnectionState.Ready) break;
                    await WriteAsync(FrameCodec.BuildStartTelemetryStream()).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) { /* expected on teardown */ }
        }, ct);
    }

    // --- Writes -------------------------------------------------------

    public async Task<bool> WriteAsync(byte[] bytes)
    {
        var ch = _writeChar;
        if (ch == null) return false;
        try
        {
            using var w = new DataWriter();
            w.WriteBytes(bytes);
            var status = await ch.WriteValueAsync(w.DetachBuffer(),
                GattWriteOption.WriteWithResponse);
            bool ok = status == GattCommunicationStatus.Success;
            if (!ok) Log.W("ble", $"Write failed: {status}");
            FrameSeen?.Invoke(new RawFrame(Tx: true, Bytes: bytes,
                TimestampMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
            return ok;
        }
        catch (Exception ex)
        {
            Log.E("ble", $"Write threw: {ex.Message}");
            return false;
        }
    }

    // Protocol-neutral commands: dispatch to the active alt driver when one
    // is in use, otherwise fall through to the HG FrameCodec path.
    public Task<bool> SendStart() => _activeAlt != null
        ? UnwrapAsync(_activeAlt.StartAsync())
        : WriteAsync(FrameCodec.BuildStartHeater());

    public Task<bool> SendStop() => _activeAlt != null
        ? UnwrapAsync(_activeAlt.StopAsync())
        : WriteAsync(FrameCodec.BuildStopHeater());

    public Task<bool> BlowOn() => _activeAlt != null
        ? UnwrapAsync(_activeAlt.VentAsync())
        : WriteAsync(FrameCodec.BuildBlowOn());

    public Task<bool> OilPumpOn(int s = FrameCodec.ManualPumpDefaultSeconds) => _activeAlt != null
        ? UnwrapAsync(_activeAlt.PulsePumpAsync(s))
        : WriteAsync(FrameCodec.BuildManualPumpRun(s));

    public Task<bool> OilPumpOff() => _activeAlt != null
        ? UnwrapAsync(_activeAlt.StopAsync())
        : WriteAsync(FrameCodec.BuildStopHeater());

    public Task<bool> SetTargetTemp(int c) => _activeAlt != null
        ? UnwrapAsync(_activeAlt.SetTargetCAsync(c))
        : WriteAsync(FrameCodec.BuildSetTargetTemp(c, FrameCodec.TempUnit.Celsius));

    public Task<bool> SetGear(int g) => _activeAlt != null
        ? UnwrapAsync(_activeAlt.SetGearAsync(g))
        : WriteAsync(FrameCodec.BuildSetGear(g));

    // HeatGenie-specific commands: no-op when an alt driver is active.
    // The UI is expected to gate these on Capabilities.HasRunModes /
    // HasNativeSwitches / HasNativeSchedule, but defending here too
    // means a stray callsite doesn't blow up.
    public Task<bool> SetRunMode(FrameCodec.RunMode m) => _activeAlt != null
        ? Task.FromResult(false)
        : WriteAsync(FrameCodec.BuildSetRunMode(m));

    public Task<bool> SetTempHysteresis(int diff) => _activeAlt != null
        ? Task.FromResult(false)
        : WriteAsync(FrameCodec.BuildSetTempHysteresis(diff, FrameCodec.TempUnit.Celsius));

    public Task<bool> ReadRegInfo()   => _activeAlt != null
        ? Task.FromResult(false)
        : WriteAsync(FrameCodec.BuildReadRegInfo());

    public Task<bool> ReadTimerInfo() => _activeAlt != null
        ? Task.FromResult(false)
        : WriteAsync(FrameCodec.BuildReadTimerInfo());

    public Task<bool> WriteTimer(IReadOnlyList<WriteTimerSlot> slots) => _activeAlt != null
        ? Task.FromResult(false)
        : WriteAsync(FrameCodec.BuildWriteTimerArea(slots));

    private static async Task<bool> UnwrapAsync(Task<ProtocolResult> t)
    {
        var r = await t.ConfigureAwait(false);
        return r.Ok;
    }

    // --- Notifications ------------------------------------------------

    private void OnNotification(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var buf = args.CharacteristicValue;
        byte[] bytes = new byte[buf.Length];
        DataReader.FromBuffer(buf).ReadBytes(bytes);

        FrameSeen?.Invoke(new RawFrame(Tx: false, Bytes: bytes,
            TimestampMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));

        var t = FrameCodec.ParseTelemetry(bytes);
        if (t != null) Telemetry = t;
    }

    // --- Utility ------------------------------------------------------

    private static string FormatMac(ulong addr)
    {
        var s = addr.ToString("X12");
        return string.Join(':',
            s[0..2], s[2..4], s[4..6], s[6..8], s[8..10], s[10..12]);
    }

    private static ulong MacToAddress(string mac)
    {
        var clean = mac.Replace(":", "").Replace("-", "").Replace(" ", "");
        return Convert.ToUInt64(clean, 16);
    }

    private void Fail(string reason)
    {
        Log.W("ble", $"FAIL: {reason}");
        LastError = reason;
        State = ConnectionState.Failed;
    }

    private void ClearError()
    {
        if (_lastError.Length > 0) LastError = "";
    }

    public async ValueTask DisposeAsync()
    {
        StopScan();
        await TearDownAsync().ConfigureAwait(false);
    }
}
