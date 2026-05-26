using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using TsgbHeater.Ble;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.ViewModels;

public sealed class FrameRow
{
    public string Time { get; init; } = "";
    public string Dir  { get; init; } = "";   // TX / RX
    public string Hex  { get; init; } = "";
    public string Dir2 => Dir;
}

// Tail of the last N frames seen. Mirrors the Android DebugBoxScreen.
public sealed partial class DebugBoxViewModel : ObservableObject
{
    private readonly HeaterClient _ble = ServiceLocator.Ble;
    public ObservableCollection<FrameRow> Rows { get; } = new();
    private const int MaxRows = 200;

    public DebugBoxViewModel()
    {
        _ble.FrameSeen += OnFrame;
    }

    private void OnFrame(RawFrame f)
    {
        var ts = DateTimeOffset.FromUnixTimeMilliseconds(f.TimestampMs)
                    .ToLocalTime().ToString("HH:mm:ss.fff");
        var row = new FrameRow
        {
            Time = ts,
            Dir  = f.Tx ? "TX" : "RX",
            Hex  = FrameCodec.Hex(f.Bytes),
        };
        RunOnUi(() =>
        {
            Rows.Add(row);
            while (Rows.Count > MaxRows) Rows.RemoveAt(0);
        });
    }

    [RelayCommand] private void Clear() => RunOnUi(() => Rows.Clear());

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
