using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using Ember.Ble;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed partial class FlagsViewModel : ObservableObject
{
    private readonly HeaterClient _ble = ServiceLocator.Ble;
    public ObservableCollection<HeaterFault> Active { get; } = new();

    [ObservableProperty] private bool _hasFaults;
    [ObservableProperty] private string _summary = "All clear";

    public FlagsViewModel()
    {
        _ble.TelemetryChanged += t => RunOnUi(() => Rebuild(t.FaultBits));
        Rebuild(_ble.Telemetry?.FaultBits ?? 0);
    }

    private void Rebuild(int bits)
    {
        Active.Clear();
        foreach (var f in FaultCodes.Active(bits)) Active.Add(f);
        HasFaults = Active.Count > 0;
        Summary = HasFaults ? $"{Active.Count} active" : "All clear";
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
