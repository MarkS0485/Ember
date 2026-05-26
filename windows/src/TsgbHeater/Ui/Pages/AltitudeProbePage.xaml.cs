using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Ble;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.Pages;

public partial class AltitudeProbePage : UserControl
{
    public AltitudeProbePage()
    {
        InitializeComponent();
        ServiceLocator.Ble.TelemetryChanged += OnTelemetry;
        var t = ServiceLocator.Ble.Telemetry;
        if (t != null) UpdateReadBack(t);
        AltBox.TextChanged += (_, _) => UpdatePreview();
        IndexCombo.SelectionChanged += (_, _) => UpdatePreview();
        EncodingCombo.SelectionChanged += (_, _) => UpdatePreview();
        UpdatePreview();
    }

    private void OnTelemetry(HeaterTelemetry t)
    {
        Dispatcher.BeginInvoke(() => UpdateReadBack(t));
    }
    private void UpdateReadBack(HeaterTelemetry t)
    {
        AltReadBack.Text = t.AltitudeM is { } v ? $"{v} m" : "—";
    }

    private void OnSliderChanged(object? sender, RoutedPropertyChangedEventArgs<double> e)
    {
        AltBox.Text = ((int)e.NewValue).ToString();
    }

    private void UpdatePreview()
    {
        if (!int.TryParse(AltBox.Text, out int alt)) alt = 0;
        int index = 5 + IndexCombo.SelectedIndex;
        int d1, d2;
        switch (EncodingCombo.SelectedIndex)
        {
            case 0: d1 = 0;                  d2 = alt & 0xFF;             break;  // Single byte
            case 2: d1 = (alt >> 8) & 0xFF;  d2 = alt & 0xFF;             break;  // ubeShort
            default: d1 = alt & 0xFF;        d2 = (alt >> 8) & 0xFF;       break;  // uleShort
        }
        var bytes = FrameCodec.BuildShortParaProbe(index, d1, d2);
        FramePreview.Text = FrameCodec.Hex(bytes);
    }

    private async void OnSend(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(AltBox.Text, out int alt)) { ResultLine.Text = "Bad altitude"; return; }
        int index = 5 + IndexCombo.SelectedIndex;
        int d1, d2;
        switch (EncodingCombo.SelectedIndex)
        {
            case 0: d1 = 0;                  d2 = alt & 0xFF;             break;
            case 2: d1 = (alt >> 8) & 0xFF;  d2 = alt & 0xFF;             break;
            default: d1 = alt & 0xFF;        d2 = (alt >> 8) & 0xFF;       break;
        }
        var bytes = FrameCodec.BuildShortParaProbe(index, d1, d2);
        var ok = await ServiceLocator.Ble.WriteAsync(bytes);
        ResultLine.Text = ok ? "Sent. Watch altitudeM." : "Send failed — not connected?";
    }
}
