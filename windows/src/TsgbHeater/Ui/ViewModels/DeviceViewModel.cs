using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using TsgbHeater.Ble;
using TsgbHeater.Data;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.ViewModels;

// Live device control surface. Mirrors the Android Device tab:
//   status pill / hero readout / run-mode segmented /
//   target stepper (Auto + Start-Stop) or gear segmented (Manual) /
//   action row (Heat / Vent / Stop) / fault banner / compact stats.
public sealed partial class DeviceViewModel : ObservableObject
{
    private readonly HeaterClient     _ble      = ServiceLocator.Ble;
    private readonly AppSettings      _settings = ServiceLocator.Settings;
    private readonly FuelTracker      _fuel     = ServiceLocator.FuelCtl;
    private readonly BoundDeviceStore _devices  = ServiceLocator.BoundDevices;

    public DeviceViewModel()
    {
        _ble.TelemetryChanged += OnTelemetry;
        _ble.StateChanged     += OnState;
        _ble.LastErrorChanged += e => RunOnUi(() => LastError = e);
        _settings.Changed     += () => RunOnUi(SyncFromSettings);
        _fuel.SnapshotChanged += s => RunOnUi(() => ApplyFuelSnapshot(s));
        _devices.Changed      += () => RunOnUi(SyncFuel);
        OnState(_ble.State);
        if (_ble.Telemetry != null) OnTelemetry(_ble.Telemetry);
        LastError = _ble.LastError;
        SyncFromSettings();
        SyncFuel();
    }

    // --- Bound state ----

    [ObservableProperty] private string _statusLabel  = "Disconnected";
    [ObservableProperty] private string _lastError    = "";
    [ObservableProperty] private string _modeLabel    = "—";
    [ObservableProperty] private string _ambientLabel = "— °C";
    [ObservableProperty] private string _voltsLabel   = "—";
    [ObservableProperty] private string _altitudeLabel = "—";
    [ObservableProperty] private string _housingLabel = "—";
    [ObservableProperty] private string _faultLabel   = "";
    [ObservableProperty] private bool   _isReady;
    [ObservableProperty] private bool   _isHeating;

    // Run-mode segmented
    [ObservableProperty] private bool _isAuto      = true;
    [ObservableProperty] private bool _isManual;
    [ObservableProperty] private bool _isStartStop;

    // Target stepper / gear segmented
    [ObservableProperty] private int    _targetTempC = 20;
    [ObservableProperty] private int    _gear         = 1;
    [ObservableProperty] private string _gearLabel    = "Gear 1";
    public bool ShowTargetStepper => IsAuto || IsStartStop;
    public bool ShowGearSegmented => IsManual;

    partial void OnIsAutoChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowTargetStepper));
        OnPropertyChanged(nameof(ShowGearSegmented));
    }
    partial void OnIsManualChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowTargetStepper));
        OnPropertyChanged(nameof(ShowGearSegmented));
    }
    partial void OnIsStartStopChanged(bool value)
    {
        OnPropertyChanged(nameof(ShowTargetStepper));
        OnPropertyChanged(nameof(ShowGearSegmented));
    }

    // --- Commands ----

    [RelayCommand] private async Task Start()   { if (IsReady) await _ble.SendStart(); }
    [RelayCommand] private async Task Stop()    { if (IsReady) await _ble.SendStop();  }
    [RelayCommand] private async Task Vent()    { if (IsReady) await _ble.BlowOn();    }
    [RelayCommand] private async Task Refresh() { if (IsReady) await _ble.ReadRegInfo(); }

    [RelayCommand]
    private async Task SelectRunMode(string mode)
    {
        var m = mode switch
        {
            "auto"   => FrameCodec.RunMode.Auto,
            "manual" => FrameCodec.RunMode.Manual,
            "ss"     => FrameCodec.RunMode.StartStop,
            _        => FrameCodec.RunMode.Auto,
        };
        _settings.SetSelectedRunMode((int)m);
        SyncFromSettings();
        if (IsReady) await _ble.SetRunMode(m);
    }

    [RelayCommand]
    private async Task NudgeTarget(string deltaStr)
    {
        if (!int.TryParse(deltaStr, out int delta)) return;
        TargetTempC = Math.Clamp(TargetTempC + delta, TargetMinC, TargetMaxC);
        if (IsReady) await _ble.SetTargetTemp(TargetTempC);
    }

    [RelayCommand]
    private async Task PickGear(string gStr)
    {
        if (!int.TryParse(gStr, out int g)) return;
        Gear = Math.Clamp(g, GearMin, GearMax);
        GearLabel = $"Gear {Gear}";
        if (IsReady) await _ble.SetGear(Gear);
    }

    private const int TargetMinC = 10;
    private const int TargetMaxC = 35;
    private const int GearMin    = 1;
    private const int GearMax    = 10;

    private void SyncFromSettings()
    {
        var m = (FrameCodec.RunMode)_settings.SelectedRunModeWire;
        IsAuto      = m == FrameCodec.RunMode.Auto;
        IsManual    = m == FrameCodec.RunMode.Manual;
        IsStartStop = m == FrameCodec.RunMode.StartStop;
    }

    private void OnState(ConnectionState s)
    {
        RunOnUi(() =>
        {
            StatusLabel = s switch
            {
                ConnectionState.Ready                => "Connected",
                ConnectionState.Connecting           => "Connecting…",
                ConnectionState.DiscoveringServices  => "Discovering services…",
                ConnectionState.Reconnecting         => "Reconnecting…",
                ConnectionState.Failed               => "Connection failed",
                ConnectionState.Scanning             => "Scanning…",
                _                                     => "Disconnected",
            };
            IsReady = s == ConnectionState.Ready;
        });
    }

    private void OnTelemetry(HeaterTelemetry t)
    {
        RunOnUi(() =>
        {
            ModeLabel     = t.RunningMode.Label();
            AmbientLabel  = t.AmbientTempC is { } a ? $"{a:0.0} °C" : "— °C";
            HousingLabel  = t.HousingTempC is { } h ? $"{h:0.0} °C" : "—";
            VoltsLabel    = t.BatteryV     is { } v ? $"{v:0.0} V"  : "—";
            AltitudeLabel = t.AltitudeM    is { } z ? $"{z} m"      : "—";
            if (t.TargetTempC is { } gC)
            {
                int gi = (int)Math.Round(gC);
                if (gi != TargetTempC) TargetTempC = Math.Clamp(gi, TargetMinC, TargetMaxC);
            }
            if (t.AimGear is { } gear) { Gear = gear; GearLabel = $"Gear {gear}"; }
            IsHeating = t.RunningMode is RunningMode.Ignition or RunningMode.AutoRun
                                       or RunningMode.ManualRun or RunningMode.StartStopActive;
            int faultCount = FaultCodes.Active(t.FaultBits).Count;
            FaultLabel = faultCount switch
            {
                0 => "",
                1 => "1 active fault — see Active flags",
                _ => $"{faultCount} active faults — see Active flags",
            };
        });
    }

    // --- Fuel ----

    [ObservableProperty] private double _fuelCurrentLitres;
    [ObservableProperty] private double _fuelTankLitres = 5.0;
    [ObservableProperty] private double _fuelConsumptionLowLph  = 0.15;
    [ObservableProperty] private double _fuelConsumptionHighLph = 0.55;
    [ObservableProperty] private string _fuelLabel              = "—";
    [ObservableProperty] private string _fuelHoursRemainingLabel = "—";
    [ObservableProperty] private string _fuelCurrentConsumptionLabel = "—";
    [ObservableProperty] private string _fuelAlertLabel         = "";
    [ObservableProperty] private string _fuelAlertKind          = "";     // "", "warn", "critical", "shutdown"

    // Refill dialog state
    [ObservableProperty] private bool   _isRefillOpen;
    [ObservableProperty] private string _refillLitresInput      = "5.0";

    // Settings flyout state
    [ObservableProperty] private bool   _isFuelSettingsOpen;
    [ObservableProperty] private string _fuelSettingsTankInput  = "5.0";
    [ObservableProperty] private string _fuelSettingsLowInput   = "0.15";
    [ObservableProperty] private string _fuelSettingsHighInput  = "0.55";

    [RelayCommand]
    private void OpenRefill()
    {
        RefillLitresInput = $"{Math.Max(0.0, FuelTankLitres - FuelCurrentLitres):0.0}";
        IsRefillOpen = true;
    }

    [RelayCommand]
    private void CancelRefill() => IsRefillOpen = false;

    [RelayCommand]
    private void ApplyRefill()
    {
        if (_ble.CurrentMac is not { } mac) { IsRefillOpen = false; return; }
        if (double.TryParse(RefillLitresInput, out var litres) && litres > 0)
        {
            _fuel.Refill(mac, litres);
        }
        IsRefillOpen = false;
    }

    [RelayCommand]
    private void OpenFuelSettings()
    {
        FuelSettingsTankInput = $"{FuelTankLitres:0.##}";
        FuelSettingsLowInput  = $"{FuelConsumptionLowLph:0.###}";
        FuelSettingsHighInput = $"{FuelConsumptionHighLph:0.###}";
        IsFuelSettingsOpen    = true;
    }

    [RelayCommand]
    private void CancelFuelSettings() => IsFuelSettingsOpen = false;

    [RelayCommand]
    private void ApplyFuelSettings()
    {
        if (_ble.CurrentMac is not { } mac) { IsFuelSettingsOpen = false; return; }
        double? tank = double.TryParse(FuelSettingsTankInput, out var t) && t > 0 ? t : null;
        double? lo   = double.TryParse(FuelSettingsLowInput,  out var l) && l > 0 ? l : null;
        double? hi   = double.TryParse(FuelSettingsHighInput, out var h) && h > 0 ? h : null;
        _devices.UpdateFuelConfig(mac, tank, lo, hi);
        IsFuelSettingsOpen = false;
    }

    private void SyncFuel()
    {
        if (_ble.CurrentMac is not { } mac) return;
        var snap = _fuel.Snapshot(mac);
        if (snap != null) ApplyFuelSnapshot(snap);
    }

    private void ApplyFuelSnapshot(FuelTracker.FuelSnapshot s)
    {
        FuelCurrentLitres       = s.CurrentLitres;
        FuelTankLitres          = s.TankLitres;
        FuelConsumptionLowLph   = s.ConsumptionLowLph;
        FuelConsumptionHighLph  = s.ConsumptionHighLph;
        FuelLabel               = $"{s.CurrentLitres:0.00} / {s.TankLitres:0.0} L";

        // Hours remaining at the CURRENT consumption rate. If we don't
        // have a current gear (e.g. heater is off), use the midpoint as
        // a rough "what would I get at gear 5" estimate.
        int gear = _ble.Telemetry?.AimGear ?? 5;
        double lph = FuelTracker.ConsumptionForGear(gear, s.ConsumptionLowLph, s.ConsumptionHighLph);
        FuelCurrentConsumptionLabel = $"{lph:0.00} L/h @ gear {gear}";
        FuelHoursRemainingLabel = lph > 0
            ? $"≈ {(s.CurrentLitres / lph):0.0} h remaining"
            : "—";

        (FuelAlertLabel, FuelAlertKind) = s.Alert switch
        {
            FuelTracker.AlertLevel.Warning  => ("Fuel low — refill soon", "warn"),
            FuelTracker.AlertLevel.Critical => ("Fuel critical", "critical"),
            FuelTracker.AlertLevel.Shutdown => ("Tank near empty — stopping heater", "shutdown"),
            _                                => ("", ""),
        };
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
