using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Ember.Ble;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed partial class SwitchesViewModel : ObservableObject
{
    private readonly HeaterClient   _ble      = ServiceLocator.Ble;
    private readonly Data.AppSettings _settings = ServiceLocator.Settings;

    [ObservableProperty] private bool _keepAlive;
    [ObservableProperty] private bool _autoStartStopMaster;
    [ObservableProperty] private bool _isAuto;
    [ObservableProperty] private bool _isManual;
    [ObservableProperty] private bool _isStartStop;
    [ObservableProperty] private bool _heaterShowsF;     // mirrors telemetry.tempUnitFahrenheit
    [ObservableProperty] private string _runModeLabel = "—";
    [ObservableProperty] private bool _isReady;

    public SwitchesViewModel()
    {
        _ble.TelemetryChanged += t => RunOnUi(() =>
        {
            HeaterShowsF = t.TempUnitFahrenheit;
            RunModeLabel = t.RunningMode.Label();
        });
        _ble.StateChanged += s => RunOnUi(() => IsReady = s == ConnectionState.Ready);
        _settings.Changed += () => RunOnUi(Sync);
        Sync();
        IsReady = _ble.State == ConnectionState.Ready;
        if (_ble.Telemetry is { } tn)
        {
            HeaterShowsF = tn.TempUnitFahrenheit;
            RunModeLabel = tn.RunningMode.Label();
        }
    }

    private void Sync()
    {
        KeepAlive            = _settings.KeepAlive;
        AutoStartStopMaster  = _settings.AutoStartStopMaster;
        var m = (FrameCodec.RunMode)_settings.SelectedRunModeWire;
        IsAuto      = m == FrameCodec.RunMode.Auto;
        IsManual    = m == FrameCodec.RunMode.Manual;
        IsStartStop = m == FrameCodec.RunMode.StartStop;
    }

    [RelayCommand] private async Task SetRunMode(string mode)
    {
        var m = mode switch {
            "auto" => FrameCodec.RunMode.Auto,
            "manual" => FrameCodec.RunMode.Manual,
            "ss" => FrameCodec.RunMode.StartStop,
            _ => FrameCodec.RunMode.Auto,
        };
        _settings.SetSelectedRunMode((int)m);
        if (IsReady) await _ble.SetRunMode(m);
    }

    [RelayCommand] private async Task SetTempUnit(string unit)
    {
        if (!IsReady) return;
        if (unit == "f") await _ble.WriteAsync(FrameCodec.BuildSwitchToFahrenheit());
        else             await _ble.WriteAsync(FrameCodec.BuildSwitchToCelsius());
    }

    public void ToggleKeepAlive(bool v)            => _settings.SetKeepAlive(v);
    public void ToggleAutoStartStopMaster(bool v)  => _settings.SetAutoStartStopMaster(v);

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
