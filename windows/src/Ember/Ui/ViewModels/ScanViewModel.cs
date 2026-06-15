using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Ember.Ble;
using Ember.Data;
using Ember.Services;

namespace Ember.Ui.ViewModels;

// Combines paired devices + live scan list into one screen, same as
// android Scan tab. Live RSSI updates in place; row order is stable
// (no RSSI-driven reshuffle that makes targets jump under the user).
public sealed partial class ScanViewModel : ObservableObject
{
    private readonly HeaterClient _ble = ServiceLocator.Ble;
    private readonly BoundDeviceStore _store = ServiceLocator.BoundDevices;

    public ObservableCollection<BoundDevice>      Paired   { get; } = new();
    public ObservableCollection<DiscoveredDevice> Scanned  { get; } = new();

    [ObservableProperty] private bool _scanning;
    [ObservableProperty] private string _statusLabel = "Idle";
    [ObservableProperty] private string _currentMac = "";
    [ObservableProperty] private string _lastError = "";

    public ScanViewModel()
    {
        _ble.StateChanged      += OnStateChanged;
        _ble.ScanningChanged   += s => RunOnUi(() => Scanning = s);
        _ble.DevicesChanged    += RebuildScanned;
        _ble.LastErrorChanged  += e => RunOnUi(() => LastError = e);
        _store.Changed         += RebuildPaired;

        RebuildPaired();
        RebuildScanned();
        OnStateChanged(_ble.State);
        LastError = _ble.LastError;
    }

    private void OnStateChanged(ConnectionState s)
    {
        RunOnUi(() =>
        {
            StatusLabel = s switch
            {
                ConnectionState.Ready                => "Connected",
                ConnectionState.Connecting           => "Connecting…",
                ConnectionState.DiscoveringServices  => "Discovering…",
                ConnectionState.Reconnecting         => "Reconnecting…",
                ConnectionState.Failed               => "Failed",
                ConnectionState.Scanning             => "Scanning…",
                _                                     => "Disconnected",
            };
        });
    }

    private void RebuildPaired()
    {
        RunOnUi(() =>
        {
            Paired.Clear();
            foreach (var d in _store.All) Paired.Add(d);
            CurrentMac = _store.CurrentMac ?? "";
        });
    }

    private void RebuildScanned()
    {
        RunOnUi(() =>
        {
            Scanned.Clear();
            foreach (var d in _ble.Devices) Scanned.Add(d);
        });
    }

    [RelayCommand] public void StartScan() => _ble.StartScan();
    [RelayCommand] public void StopScan()  => _ble.StopScan();

    [RelayCommand]
    public Task Bind(DiscoveredDevice d)
    {
        // Bind = persist + mark current. We deliberately do NOT auto-
        // connect: clicking Bind and then clicking Connect on the now-
        // paired row would otherwise race the idempotency check and the
        // second click would be a no-op. The user does an explicit
        // Connect on the paired list.
        var name = !string.IsNullOrWhiteSpace(d.Name) ? d.Name! : $"Heater {d.Mac[^5..]}";
        // If the scanner detected the protocol via service UUID, use that.
        // Otherwise default to HeatGenie — the historical default since
        // every pre-multi-protocol pairing was a HG heater. User can edit
        // later if mistaken.
        var protocol = d.Protocol ?? Ember.Protocol.ProtocolKind.HeatGenie;
        _store.Add(new BoundDevice(
            Mac: d.Mac, Name: name,
            LastSeenAtMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Protocol: protocol));
        _store.SetCurrent(d.Mac);
        // Stop the scan: keeping the watcher running while we connect
        // adds noise to the BLE radio and isn't useful once a device is
        // picked.
        _ble.StopScan();
        return Task.CompletedTask;
    }

    [RelayCommand]
    public async Task Reconnect(BoundDevice d)
    {
        _store.SetCurrent(d.Mac);
        await _ble.ConnectAsync(d.Mac);
    }

    [RelayCommand]
    public void Unbind(BoundDevice d)
    {
        _store.Remove(d.Mac);
        if (_ble.State != ConnectionState.Idle && _store.CurrentMac == null)
        {
            _ = _ble.DisconnectAsync();
        }
    }

    private static void RunOnUi(Action a)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher == null || dispatcher.CheckAccess()) a();
        else dispatcher.BeginInvoke(a);
    }
}
