namespace Ember.Data.Schedule;

// One scheduled on→off pulse. Stored richer-than-heater: any number of
// pulses per weekday. The ScheduleController collapses these down to the
// heater's single per-day slot at runtime.
//
// Day index follows the vendor's Mon=0..Sun=6 convention so it lines up
// with what the heater actually stores.
public sealed record ScheduleEvent(
    string Id,
    int    DayOfWeek,        // 0 = Mon … 6 = Sun
    int    OnMinuteOfDay,    // 0..1439
    int    OffMinuteOfDay,   // > OnMinuteOfDay, ≤ 1439
    bool   Enabled = true)
{
    public ScheduleEvent(int dayOfWeek, int onMin, int offMin, bool enabled = true)
        : this(Guid.NewGuid().ToString("N"), dayOfWeek, onMin, offMin, enabled) { }

    public int OnHour  => OnMinuteOfDay  / 60;
    public int OnMin   => OnMinuteOfDay  % 60;
    public int OffHour => OffMinuteOfDay / 60;
    public int OffMin  => OffMinuteOfDay % 60;

    public string TimeRange =>
        $"{OnHour:00}:{OnMin:00} → {OffHour:00}:{OffMin:00}";
}

public sealed record Schedule(IReadOnlyList<ScheduleEvent> Events)
{
    public Schedule() : this(Array.Empty<ScheduleEvent>()) { }

    public IReadOnlyList<ScheduleEvent> EventsOnDay(int day) =>
        Events.Where(e => e.Enabled && e.DayOfWeek == day)
              .OrderBy(e => e.OnMinuteOfDay)
              .ToArray();

    public Schedule Add(ScheduleEvent e)       => new(Events.Append(e).ToArray());
    public Schedule Remove(string id)          => new(Events.Where(x => x.Id != id).ToArray());
    public Schedule Update(ScheduleEvent e)    => new(Events.Select(x => x.Id == e.Id ? e : x).ToArray());
}
