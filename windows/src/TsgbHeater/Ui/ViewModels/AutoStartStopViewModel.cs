using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using TsgbHeater.Data;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.ViewModels;

public sealed partial class AutoStartStopViewModel : ObservableObject
{
    private readonly AppSettings _settings = ServiceLocator.Settings;

    [ObservableProperty] private bool _masterEnabled;
    [ObservableProperty] private int  _setpointC;
    [ObservableProperty] private int  _marginC;
    [ObservableProperty] private int  _ambientStartC;
    [ObservableProperty] private int  _ambientStopC;
    [ObservableProperty] private double _batteryCutoffV;
    [ObservableProperty] private bool   _batteryCutoffEnabled;
    [ObservableProperty] private int    _cooldownSec;

    private bool _loading;

    public AutoStartStopViewModel()
    {
        _settings.Changed += () => RunOnUi(LoadFromSettings);
        LoadFromSettings();
    }

    private void LoadFromSettings()
    {
        _loading = true;
        var r = _settings.AutoRules;
        MasterEnabled        = r.MasterEnabled;
        SetpointC            = r.SetpointC;
        MarginC              = r.MarginC;
        AmbientStartC        = r.AmbientStartC;
        AmbientStopC         = r.AmbientStopC;
        BatteryCutoffV       = r.BatteryCutoffV;
        BatteryCutoffEnabled = r.BatteryCutoffEnabled;
        CooldownSec          = r.CooldownSec;
        _loading = false;
    }

    private void Persist()
    {
        if (_loading) return;
        _settings.SetAutoRules(new AutoRules(
            MasterEnabled:        MasterEnabled,
            SetpointC:            SetpointC,
            MarginC:              MarginC,
            AmbientStartC:        AmbientStartC,
            AmbientStopC:         AmbientStopC,
            BatteryCutoffV:       BatteryCutoffV,
            BatteryCutoffEnabled: BatteryCutoffEnabled,
            CooldownSec:          CooldownSec));
    }

    partial void OnMasterEnabledChanged(bool value)        => Persist();
    partial void OnSetpointCChanged(int value)             => Persist();
    partial void OnMarginCChanged(int value)               => Persist();
    partial void OnAmbientStartCChanged(int value)         => Persist();
    partial void OnAmbientStopCChanged(int value)          => Persist();
    partial void OnBatteryCutoffVChanged(double value)     => Persist();
    partial void OnBatteryCutoffEnabledChanged(bool value) => Persist();
    partial void OnCooldownSecChanged(int value)           => Persist();

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
