using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Ember.Ble;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed class TimerSlotRow
{
    public string Day { get; init; } = "";
    public string Mode { get; init; } = "";
    public string OnTime { get; init; } = "";
    public string OffTime { get; init; } = "";
    public bool Active => Mode != "Off";
}

public sealed partial class HeaterTimerViewModel : ObservableObject
{
    private readonly HeaterClient _ble = ServiceLocator.Ble;
    public ObservableCollection<TimerSlotRow> Slots { get; } = new();

    [ObservableProperty] private string _lastUpdated = "Never";
    [ObservableProperty] private bool _isReady;
    [ObservableProperty] private bool _refreshing;

    private static readonly string[] DayLabels = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };

    public HeaterTimerViewModel()
    {
        _ble.StateChanged += s => RunOnUi(() => IsReady = s == ConnectionState.Ready);
        _ble.FrameSeen    += OnFrame;
        IsReady = _ble.State == ConnectionState.Ready;
    }

    private void OnFrame(RawFrame f)
    {
        if (f.Tx) return;
        var parsed = FrameCodec.ParseTimerInfo(f.Bytes);
        if (parsed == null) return;
        RunOnUi(() =>
        {
            Slots.Clear();
            foreach (var s in parsed)
            {
                Slots.Add(new TimerSlotRow
                {
                    Day     = DayLabels[s.DayIndex],
                    Mode    = s.ModeRaw switch { 0 => "Off", 1 or 2 or 3 => "One-shot", _ => "Daily" },
                    OnTime  = $"{s.OnHour:00}:{s.OnMin:00}",
                    OffTime = $"{s.OffHour:00}:{s.OffMin:00}",
                });
            }
            LastUpdated = DateTime.Now.ToString("HH:mm:ss");
            Refreshing = false;
        });
    }

    [RelayCommand]
    private async Task Refresh()
    {
        if (!IsReady) return;
        Refreshing = true;
        await _ble.ReadTimerInfo();
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
