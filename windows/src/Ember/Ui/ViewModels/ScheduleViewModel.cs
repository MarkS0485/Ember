using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Ember.Data.Schedule;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed partial class ScheduleViewModel : ObservableObject
{
    private readonly ScheduleStore      _store    = ServiceLocator.ScheduleStore;
    private readonly ScheduleController _ctl      = ServiceLocator.Scheduler;
    private readonly Data.AppSettings   _settings = ServiceLocator.Settings;

    public sealed class DaySection
    {
        public string Label { get; }
        public int    Day   { get; }
        public ObservableCollection<ScheduleEvent> Events { get; } = new();
        public DaySection(int day, string label) { Day = day; Label = label; }
    }

    public ObservableCollection<DaySection> Days { get; } = new();

    [ObservableProperty] private bool   _enabled;
    [ObservableProperty] private int    _eventCount;
    [ObservableProperty] private string _statusLabel = "Disabled";
    [ObservableProperty] private string _statusDetail = "Turn on schedule mode to start pushing events.";

    public ScheduleViewModel()
    {
        foreach (var (i, label) in new[] {
            (0, "Monday"), (1, "Tuesday"), (2, "Wednesday"), (3, "Thursday"),
            (4, "Friday"), (5, "Saturday"), (6, "Sunday") })
            Days.Add(new DaySection(i, label));

        _store.Changed     += Rebuild;
        _settings.Changed  += Rebuild;
        _ctl.StatusChanged += _ => Rebuild();
        Rebuild();
    }

    private void Rebuild()
    {
        RunOnUi(() =>
        {
            var s = _store.Get();
            EventCount = s.Events.Count;
            Enabled    = _settings.ScheduleModeEnabled;
            foreach (var sec in Days)
            {
                sec.Events.Clear();
                foreach (var e in s.EventsOnDay(sec.Day)) sec.Events.Add(e);
            }
            (StatusLabel, StatusDetail) = _ctl.Status switch
            {
                ScheduleStatus.Disabled         => ("Disabled", "Turn on schedule mode above to start pushing events."),
                ScheduleStatus.WaitingForLink x => ("Waiting for heater", $"{x.EventCount} event(s) ready — will sync when the link is up."),
                ScheduleStatus.WriteFailed   x  => ("Last write failed", "Will retry on the next minute tick."),
                ScheduleStatus.Synced        x  => ("Synced", x.NextEventSummary != null ? $"Next: {x.NextEventSummary}" : "No upcoming events."),
                _                                => ("Disabled", ""),
            };
        });
    }

    [RelayCommand] public void SetEnabled(bool v) => _settings.SetScheduleMode(v);

    public void AddEvent(int day, int onMin, int offMin)
    {
        if (offMin <= onMin) return;
        _store.Mutate(s => s.Add(new ScheduleEvent(day, onMin, offMin)));
    }
    public void UpdateEvent(ScheduleEvent e) => _store.Mutate(s => s.Update(e));
    [RelayCommand] public void Remove(ScheduleEvent e) => _store.Mutate(s => s.Remove(e.Id));
    [RelayCommand] public void ToggleEvent(ScheduleEvent e) =>
        _store.Mutate(s => s.Update(e with { Enabled = !e.Enabled }));
    [RelayCommand] public async Task ClearHeater() => await _ctl.ClearHeaterAsync();

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
