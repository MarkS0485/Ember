using System.Windows;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using TsgbHeater.Ble;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.ViewModels;

// Debounced slider commits. Mirrors the Android AdvanceScreen — a drag
// across the range only emits one BLE write 400 ms after the user lets go.
public sealed partial class AdvanceViewModel : ObservableObject
{
    private readonly HeaterClient _ble = ServiceLocator.Ble;

    private readonly DispatcherTimer _targetTimer  = new() { Interval = TimeSpan.FromMilliseconds(400) };
    private readonly DispatcherTimer _diffTimer    = new() { Interval = TimeSpan.FromMilliseconds(400) };

    [ObservableProperty] private bool _isReady;
    [ObservableProperty] private int  _targetTempC = 20;
    [ObservableProperty] private int  _tempDiffC   = 5;

    public int TargetMinC => 10;
    public int TargetMaxC => 35;
    public int DiffMinC   => 3;
    public int DiffMaxC   => 15;

    public AdvanceViewModel()
    {
        _ble.StateChanged     += s => RunOnUi(() => IsReady = s == ConnectionState.Ready);
        _ble.TelemetryChanged += t => RunOnUi(() =>
        {
            if (t.TargetTempC is { } tc)
                TargetTempC = Math.Clamp((int)Math.Round(tc), TargetMinC, TargetMaxC);
        });
        IsReady = _ble.State == ConnectionState.Ready;
        if (_ble.Telemetry?.TargetTempC is { } tc0)
            TargetTempC = Math.Clamp((int)Math.Round(tc0), TargetMinC, TargetMaxC);

        _targetTimer.Tick += async (_, _) =>
        {
            _targetTimer.Stop();
            if (IsReady) await _ble.SetTargetTemp(TargetTempC);
        };
        _diffTimer.Tick += async (_, _) =>
        {
            _diffTimer.Stop();
            if (IsReady) await _ble.SetTempHysteresis(TempDiffC);
        };
    }

    partial void OnTargetTempCChanged(int value) { _targetTimer.Stop(); _targetTimer.Start(); }
    partial void OnTempDiffCChanged(int value)   { _diffTimer.Stop();   _diffTimer.Start();   }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
