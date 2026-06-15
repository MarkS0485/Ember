using Ember.Ble;
using Ember.Data;
using Ember.Services;

namespace Ember.Data.Schedule;

public abstract record ScheduleStatus
{
    public sealed record Disabled       : ScheduleStatus;
    public sealed record WaitingForLink(int EventCount) : ScheduleStatus;
    public sealed record WriteFailed   (int EventCount) : ScheduleStatus;
    public sealed record Synced(int EventCount, long? LastPushedAtMs, string? NextEventSummary) : ScheduleStatus;
}

// Drives the heater's 7-day timer table from the rich in-app schedule.
// One next-event per weekday is selected, and a write fires whenever the
// chosen events change. Mirrors the Android ScheduleController.
public sealed class ScheduleController : IAsyncDisposable
{
    private readonly HeaterClient   _ble;
    private readonly ScheduleStore  _store;
    private readonly AppSettings    _settings;

    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;

    private string? _lastPushedSignature;
    private bool _wasEnabled;

    public ScheduleStatus Status { get; private set; } = new ScheduleStatus.Disabled();
    public event Action<ScheduleStatus>? StatusChanged;

    public ScheduleController(HeaterClient ble, ScheduleStore store, AppSettings settings)
    {
        _ble = ble;
        _store = store;
        _settings = settings;
    }

    public void Start()
    {
        if (_loop != null) return;
        _loop = Task.Run(() => RunLoop(_cts.Token));
        // React to schedule edits + state changes immediately rather than
        // waiting for the 60s tick.
        _store.Changed += OnNudge;
        _settings.Changed += OnNudge;
        _ble.StateChanged += _ => OnNudge();
    }

    private void OnNudge() => _wakeUp.Release();
    private readonly SemaphoreSlim _wakeUp = new(0, int.MaxValue);

    private async Task RunLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await ReconcileAsync().ConfigureAwait(false);
            // Wait for either the 60s tick or a Changed nudge.
            using var tickCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var tickTask = Task.Delay(60_000, tickCts.Token);
            var nudgeTask = _wakeUp.WaitAsync(ct);
            await Task.WhenAny(tickTask, nudgeTask).ConfigureAwait(false);
            tickCts.Cancel();
        }
    }

    private async Task ReconcileAsync()
    {
        var sched   = _store.Get();
        bool enabled = _settings.ScheduleModeEnabled;
        var state   = _ble.State;

        if (!enabled)
        {
            if (_wasEnabled && state == ConnectionState.Ready)
            {
                // Auto-clear on enabled→disabled transition.
                Log.I("sched", "Schedule disabled — clearing heater slots");
                await _ble.WriteTimer(EmptySlots()).ConfigureAwait(false);
            }
            _wasEnabled = false;
            _lastPushedSignature = null;
            SetStatus(new ScheduleStatus.Disabled());
            return;
        }
        _wasEnabled = true;
        if (state != ConnectionState.Ready)
        {
            SetStatus(new ScheduleStatus.WaitingForLink(sched.Events.Count));
            return;
        }

        var now   = DateTime.Now;
        var slots = ComputeSlots(sched, now);
        var sig   = Signature(slots);
        if (sig == _lastPushedSignature)
        {
            SetStatus(new ScheduleStatus.Synced(sched.Events.Count,
                LastPushedAtMs: GetLastPushedMs(),
                NextEventSummary: DescribeNext(slots, now)));
            return;
        }

        Log.I("sched", $"Pushing schedule: {sig}");
        bool ok = await _ble.WriteTimer(slots).ConfigureAwait(false);
        if (ok)
        {
            _lastPushedSignature = sig;
            SetStatus(new ScheduleStatus.Synced(sched.Events.Count,
                LastPushedAtMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                NextEventSummary: DescribeNext(slots, now)));
        }
        else
        {
            Log.W("sched", "writeTimer failed; will retry on next tick");
            SetStatus(new ScheduleStatus.WriteFailed(sched.Events.Count));
        }
    }

    private long? GetLastPushedMs() => Status switch
    {
        ScheduleStatus.Synced s => s.LastPushedAtMs,
        _ => null,
    };

    public async Task<bool> ClearHeaterAsync()
    {
        if (_ble.State != ConnectionState.Ready) return false;
        bool ok = await _ble.WriteTimer(EmptySlots()).ConfigureAwait(false);
        if (ok) _lastPushedSignature = null;
        OnNudge();
        return ok;
    }

    // --- Slot computation ---------------------------------------------

    private static IReadOnlyList<WriteTimerSlot> ComputeSlots(Schedule sched, DateTime now)
    {
        int todayDow = FrameCodec.DayOfWeekToVendor(now.DayOfWeek);
        int nowMin   = now.Hour * 60 + now.Minute;
        return Enumerable.Range(0, 7).Select(day =>
        {
            var pick = PickEvent(sched.EventsOnDay(day), day, todayDow, nowMin);
            if (pick != null)
            {
                return new WriteTimerSlot(
                    DayIndex: day,
                    ModeRaw : ModeDaily,
                    OnHour  : pick.OnHour,
                    OnMin   : pick.OnMin,
                    OffHour : pick.OffHour,
                    OffMin  : pick.OffMin);
            }
            return new WriteTimerSlot(day, ModeOff, 0, 0, 0, 0);
        }).ToArray();
    }

    private static ScheduleEvent? PickEvent(IReadOnlyList<ScheduleEvent> events,
                                            int day, int todayDow, int nowMin)
    {
        if (events.Count == 0) return null;
        if (day != todayDow) return events[0];
        // today: prefer in-progress, then next, then first-next-week.
        var inProgress = events.FirstOrDefault(e => e.OnMinuteOfDay <= nowMin && nowMin < e.OffMinuteOfDay);
        if (inProgress != null) return inProgress;
        var nextToday  = events.FirstOrDefault(e => e.OnMinuteOfDay > nowMin);
        return nextToday ?? events[0];
    }

    private static string Signature(IReadOnlyList<WriteTimerSlot> slots) =>
        string.Join("|", slots.Select(s =>
            $"{s.DayIndex}:{s.ModeRaw}/{s.OnHour}:{s.OnMin}->{s.OffHour}:{s.OffMin}"));

    private static string? DescribeNext(IReadOnlyList<WriteTimerSlot> slots, DateTime now)
    {
        int todayDow = FrameCodec.DayOfWeekToVendor(now.DayOfWeek);
        int nowMin   = now.Hour * 60 + now.Minute;
        var labels = new[] { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        for (int off = 0; off < 7; off++)
        {
            int day = (todayDow + off) % 7;
            var slot = slots.First(s => s.DayIndex == day);
            if (slot.ModeRaw == ModeOff) continue;
            int slotMin = slot.OnHour * 60 + slot.OnMin;
            if (off == 0 && slotMin <= nowMin) continue;
            return $"{labels[day]} {slot.OnHour:00}:{slot.OnMin:00} → {slot.OffHour:00}:{slot.OffMin:00}";
        }
        return null;
    }

    private static IReadOnlyList<WriteTimerSlot> EmptySlots() =>
        Enumerable.Range(0, 7)
            .Select(i => new WriteTimerSlot(i, ModeOff, 0, 0, 0, 0))
            .ToArray();

    private void SetStatus(ScheduleStatus s)
    {
        if (Status != s)
        {
            Status = s;
            StatusChanged?.Invoke(s);
        }
    }

    private const int ModeOff   = 0;
    private const int ModeDaily = 4;

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        if (_loop != null) try { await _loop.ConfigureAwait(false); } catch { }
        _cts.Dispose();
    }
}
