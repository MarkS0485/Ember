using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Input;

namespace Ember.Ui.Dialogs;

public partial class TimeRangeDialog : Window
{
    public int OnMinute  { get; private set; }
    public int OffMinute { get; private set; }

    public TimeRangeDialog() => InitializeComponent();

    private void Numeric(object sender, TextCompositionEventArgs e)
    {
        e.Handled = !Regex.IsMatch(e.Text, "[0-9]");
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(OnHour.Text,  out int oh) || oh < 0 || oh > 23) { Bad("On hour 0-23"); return; }
        if (!int.TryParse(OnMin.Text,   out int om) || om < 0 || om > 59) { Bad("On min 0-59"); return; }
        if (!int.TryParse(OffHour.Text, out int ofh) || ofh < 0 || ofh > 23) { Bad("Off hour 0-23"); return; }
        if (!int.TryParse(OffMin.Text,  out int ofm) || ofm < 0 || ofm > 59) { Bad("Off min 0-59"); return; }
        int on  = oh * 60 + om;
        int off = ofh * 60 + ofm;
        if (off <= on) { Bad("Off must be after On (split cross-midnight events)"); return; }
        OnMinute = on;
        OffMinute = off;
        DialogResult = true;
    }

    private void Bad(string msg) =>
        MessageBox.Show(this, msg, "Invalid time", MessageBoxButton.OK, MessageBoxImage.Warning);
}
