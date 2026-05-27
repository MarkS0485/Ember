using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using TsgbHeater.Ui.Controls;

namespace TsgbHeater.Ui;

public sealed class BoolToVisibilityConverter : IValueConverter
{
    public bool Inverted { get; set; }
    public object Convert(object? value, Type t, object? param, CultureInfo c)
    {
        bool b = value is bool x && x;
        if (Inverted) b = !b;
        return b ? Visibility.Visible : Visibility.Collapsed;
    }
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c)
        => throw new NotSupportedException();
}

// Visibility = Visible when the integer count is zero; collapsed otherwise.
// Used to swap an empty-state hint in for an empty ItemsControl.
public sealed class CountZeroToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is int n && n == 0) ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c)
        => throw new NotSupportedException();
}

// Selected → secondary accent; otherwise transparent. Used by the
// run-mode segmented control on DevicePage.
public sealed class BoolToSelectedBgConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is bool b && b)
            ? Application.Current.Resources["TsgbNavy"]!
            : Application.Current.Resources["Body2"]!;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

public sealed class BoolToSelectedFgConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is bool b && b)
            ? System.Windows.Media.Brushes.White
            : (object)Application.Current.Resources["InkHi"]!;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

// Inverted variants: selected when the bound bool is FALSE. Useful when
// the segmented control's "left" cell is the default state.
public sealed class BoolToSelectedBgInvConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is bool b && !b)
            ? Application.Current.Resources["TsgbNavy"]!
            : Application.Current.Resources["Body2"]!;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

public sealed class BoolToSelectedFgInvConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is bool b && !b)
            ? System.Windows.Media.Brushes.White
            : (object)Application.Current.Resources["InkHi"]!;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

// Visible when the bound string is non-empty.
public sealed class StringNotEmptyToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => string.IsNullOrEmpty(value as string) ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c)
        => throw new NotSupportedException();
}

// Maps the FuelTracker alert-level string ("warn" / "critical" /
// "shutdown") to a banner background. Empty/unknown collapses to
// transparent — the banner itself is hidden via StringNotEmptyToVis.
public sealed class FuelAlertKindToBrushConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
    {
        var kind = value as string;
        // Looked up by key so theme switches just work. Amber for the
        // warning, hot red for critical / impending shutdown.
        var key = kind switch
        {
            "warn"     => "FuelAmber",
            "critical" => "ErrRed",
            "shutdown" => "ErrRed",
            _          => "Body2",
        };
        return Application.Current.TryFindResource(key) ?? Brushes.Transparent;
    }
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

public sealed class BoolToReadyLabelConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is bool b && b) ? "Ready" : "Not connected";
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

public sealed class BoolToReadyKindConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
        => (value is bool b && b) ? StatusKind.Online : StatusKind.Offline;
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c) => throw new NotSupportedException();
}

// Maps a friendly status string to the StatusPill colour kind. Kept here
// rather than re-encoded everywhere it appears.
public sealed class StatusLabelToKindConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? param, CultureInfo c)
    {
        var s = value as string ?? "";
        return s switch
        {
            "Connected"      => StatusKind.Online,
            "Connecting…"    => StatusKind.Stale,
            "Discovering…"   => StatusKind.Stale,
            "Reconnecting…"  => StatusKind.Stale,
            "Scanning…"      => StatusKind.Stale,
            "Failed"         => StatusKind.Warning,
            _                => StatusKind.Offline,
        };
    }
    public object ConvertBack(object? value, Type t, object? param, CultureInfo c)
        => throw new NotSupportedException();
}
