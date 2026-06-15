using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Ember.Ble;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed partial class TestModeViewModel : ObservableObject
{
    private readonly HeaterClient _ble = ServiceLocator.Ble;

    [ObservableProperty] private bool   _isReady;
    [ObservableProperty] private string _runMode = "—";
    [ObservableProperty] private int    _pumpSeconds = FrameCodec.ManualPumpDefaultSeconds;
    [ObservableProperty] private string _pumpHint = "";
    [ObservableProperty] private bool   _pumpAllowed;

    public TestModeViewModel()
    {
        _ble.StateChanged     += s => RunOnUi(() => { IsReady = s == ConnectionState.Ready; UpdatePumpGate(); });
        _ble.TelemetryChanged += t => RunOnUi(() => { RunMode = t.RunningMode.Label(); UpdatePumpGate(); });
        IsReady = _ble.State == ConnectionState.Ready;
        if (_ble.Telemetry is { } t) RunMode = t.RunningMode.Label();
        UpdatePumpGate();
    }

    private void UpdatePumpGate()
    {
        var t = _ble.Telemetry;
        var rm = t?.RunningMode ?? RunningMode.Unknown;
        PumpAllowed = IsReady && (rm == RunningMode.Standby || rm == RunningMode.ManualPump);
        PumpHint = PumpAllowed
            ? "Pump ready."
            : rm == RunningMode.Unknown
                ? "Waiting for telemetry — pump unlocks once the heater reports a state."
                : $"Pump only runs in Standby. Heater is in “{rm.Label()}” — stop the burner first.";
    }

    [RelayCommand] private async Task BlowOn()      { if (IsReady) await _ble.BlowOn(); }
    [RelayCommand] private async Task SendStop()    { if (IsReady) await _ble.SendStop(); }
    [RelayCommand] private async Task PumpOn()      { if (PumpAllowed) await _ble.OilPumpOn(PumpSeconds); }
    [RelayCommand] private async Task PumpOff()     { if (IsReady) await _ble.OilPumpOff(); }
    [RelayCommand] private async Task ButtonUp()    { if (IsReady) await _ble.WriteAsync(FrameCodec.BuildButtonUp()); }
    [RelayCommand] private async Task ButtonDown()  { if (IsReady) await _ble.WriteAsync(FrameCodec.BuildButtonDown()); }
    [RelayCommand] private async Task ReadReg()     { if (IsReady) await _ble.ReadRegInfo(); }

    [RelayCommand] private void IncPump()
    {
        PumpSeconds = Math.Min(FrameCodec.ManualPumpMaxSeconds, PumpSeconds + 5);
    }
    [RelayCommand] private void DecPump()
    {
        PumpSeconds = Math.Max(FrameCodec.ManualPumpMinSeconds, PumpSeconds - 5);
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
