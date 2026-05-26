using System.IO;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Storage.Streams;
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
        bool isHeater = name?.Contains("KZQ", StringComparison.OrdinalIgnoreCase) == true
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
                };
            }
            else
            {
                _devices[mac] = new DiscoveredDevice(mac, name, rssi, isHeater, now);
            }
        }
        DevicesChanged?.Invoke();
    }

    // --- Connect ------------------------------------------------------

    public async Task ConnectAsync(string mac)
    {
        // Outer guard: anything we miss below turns into a clean Fail()
        // instead of a process-killing dispatcher exception. The .NET 10
        // WinRT bindings throw assorted COMExceptions when Bluetooth is
        // toggling or the radio is wedged, and we'd rather show a red
        // line than crash the window.
        try { await ConnectInnerAsync(mac).ConfigureAwait(false); }
        catch (Exception ex) { Fail($"Connect threw: {ex.GetType().Name}: {ex.Message}"); }
    }

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

    public async Task DisconnectAsync() => await TearDownAsync().ConfigureAwait(false);

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

    public Task<bool> SendStart()     => WriteAsync(FrameCodec.BuildStartHeater());
    public Task<bool> SendStop()      => WriteAsync(FrameCodec.BuildStopHeater());
    public Task<bool> BlowOn()        => WriteAsync(FrameCodec.BuildBlowOn());
    public Task<bool> OilPumpOn(int s = FrameCodec.ManualPumpDefaultSeconds)
                                      => WriteAsync(FrameCodec.BuildManualPumpRun(s));
    public Task<bool> OilPumpOff()    => WriteAsync(FrameCodec.BuildStopHeater());

    public Task<bool> SetTargetTemp(int c)
        => WriteAsync(FrameCodec.BuildSetTargetTemp(c, FrameCodec.TempUnit.Celsius));
    public Task<bool> SetGear(int g)     => WriteAsync(FrameCodec.BuildSetGear(g));
    public Task<bool> SetRunMode(FrameCodec.RunMode m) => WriteAsync(FrameCodec.BuildSetRunMode(m));
    public Task<bool> SetTempHysteresis(int diff)
        => WriteAsync(FrameCodec.BuildSetTempHysteresis(diff, FrameCodec.TempUnit.Celsius));

    public Task<bool> ReadRegInfo()    => WriteAsync(FrameCodec.BuildReadRegInfo());
    public Task<bool> ReadTimerInfo()  => WriteAsync(FrameCodec.BuildReadTimerInfo());

    public Task<bool> WriteTimer(IReadOnlyList<WriteTimerSlot> slots)
        => WriteAsync(FrameCodec.BuildWriteTimerArea(slots));

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
