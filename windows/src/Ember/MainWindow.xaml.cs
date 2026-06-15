using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using Ember.Ble;
using Ember.Services;
using Ember.Ui.Pages;

namespace Ember;

public partial class MainWindow : Window
{
    // One instance per destination so view-models (and BLE observers
    // they own) stay alive. Selecting a destination just swaps which
    // one is in the content area.
    private readonly Dictionary<string, UserControl> _pages = new();
    private string _current = "device";

    public MainWindow()
    {
        InitializeComponent();
        Services.Navigation.Changed += OnOverlayChanged;
        ServiceLocator.Ble.StateChanged     += _ => Dispatcher.BeginInvoke(RefreshStatus);
        ServiceLocator.Ble.TelemetryChanged += _ => Dispatcher.BeginInvoke(RefreshStatus);
        ServiceLocator.Ble.FrameSeen        += _ => Dispatcher.BeginInvoke(RefreshStatus);
        ServiceLocator.BoundDevices.Changed += () => Dispatcher.BeginInvoke(RefreshStatus);

        Select("device");
        RefreshStatus();
    }

    // --- Rail nav ----

    private void OnNav(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleButton t && t.Tag is string key)
            Select(key);
    }

    private void Select(string key)
    {
        _current = key;
        Services.Navigation.PopToRoot();   // pop any overlaid sub-page

        UserControl page = key switch
        {
            "device"   => Cached("device",   () => new DevicePage()),
            "scan"     => Cached("scan",     () => new ScanPage()),
            "schedule" => Cached("schedule", () => new SchedulePage()),
            "auto"     => Cached("auto",     () => new AutoStartStopPage()),
            "groups"   => Cached("groups",   () => new GroupsPage()),
            "switches" => Cached("switches", () => new SwitchesPage()),
            "advance"  => Cached("advance",  () => new AdvancePage()),
            "timer"    => Cached("timer",    () => new HeaterTimerPage()),
            "test"     => Cached("test",     () => new TestModePage()),
            "flags"    => Cached("flags",    () => new FlagsPage()),
            "debug"    => Cached("debug",    () => new DebugBoxPage()),
            "cmd"      => Cached("cmd",      () => new TestCmdPage()),
            "alt"      => Cached("alt",      () => new AltitudeProbePage()),
            "api"      => Cached("api",      () => new ApiServerPage()),
            "menuref"  => Cached("menuref",  () => new MenuRefPage()),
            "about"    => Cached("about",    () => new AboutPage()),
            _          => Cached("device",   () => new DevicePage()),
        };
        ContentHost.Content = page;
        UpdateRailSelection(key);
    }

    private UserControl Cached(string key, Func<UserControl> create)
    {
        if (!_pages.TryGetValue(key, out var p)) _pages[key] = p = create();
        return p;
    }

    private void UpdateRailSelection(string key)
    {
        var rail = new (ToggleButton btn, string key)[]
        {
            (NavDevice, "device"), (NavScan, "scan"), (NavSchedule, "schedule"),
            (NavAuto, "auto"), (NavGroups, "groups"),
            (NavSwitches, "switches"), (NavAdvance, "advance"), (NavTimerRO, "timer"),
            (NavTest, "test"), (NavFlags, "flags"), (NavDebug, "debug"),
            (NavCmd, "cmd"), (NavAlt, "alt"),
            (NavApi, "api"),
            (NavMenuRef, "menuref"), (NavAbout, "about"),
        };
        foreach (var (b, k) in rail) b.IsChecked = (k == key);
    }

    // --- Overlay (pushed sub-pages: Group detail, Group create, Bind) ----

    private void OnOverlayChanged(UserControl? page)
    {
        if (page == null)
        {
            OverlayHost.Content = null;
            OverlayHost.Visibility = Visibility.Collapsed;
        }
        else
        {
            OverlayHost.Content = page;
            OverlayHost.Visibility = Visibility.Visible;
        }
    }

    // --- Status bar + app-bar live status ----

    private void RefreshStatus()
    {
        var s   = ServiceLocator.Ble.State;
        var t   = ServiceLocator.Ble.Telemetry;
        var mac = ServiceLocator.BoundDevices.CurrentMac;
        var name = mac != null
            ? ServiceLocator.BoundDevices.All.FirstOrDefault(x =>
                  string.Equals(x.Mac, mac, StringComparison.OrdinalIgnoreCase))?.Name
            : null;

        var stateLabel = s switch
        {
            ConnectionState.Ready               => "Connected",
            ConnectionState.Connecting          => "Connecting…",
            ConnectionState.DiscoveringServices => "Discovering…",
            ConnectionState.Reconnecting        => "Reconnecting…",
            ConnectionState.Failed              => "Failed",
            ConnectionState.Scanning            => "Scanning…",
            _                                   => "Disconnected",
        };
        StatusInline.Text  = name != null ? $"{stateLabel} · {name}" : stateLabel;
        StatusBarText.Text = StatusInline.Text;
        CurrentMacText.Text = mac ?? "";

        HeroTempInline.Text = t?.AmbientTempC is { } a
            ? $"{a:0.0} °C · {t!.RunningMode.Label()}"
            : "";

        if (t != null)
        {
            var dt = DateTimeOffset.FromUnixTimeMilliseconds(t.UpdatedAtMs).ToLocalTime();
            LastRxText.Text = $"telemetry {dt:HH:mm:ss}";
        }
        else
        {
            LastRxText.Text = "";
        }
    }
}
