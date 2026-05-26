using TsgbHeater.Ble;
using TsgbHeater.Services;

namespace TsgbHeater.Data;

// Minimal Auto Start/Stop rules engine. Wakes every tick (or on telemetry
// nudge), reads the latest ambient + battery, and decides to start or
// stop the heater. Honours a cooldown so we don't flap.
public sealed class AutoStartStopController : IAsyncDisposable
{
    private readonly HeaterClient   _ble;
    private readonly AppSettings    _settings;
    private readonly SemaphoreSlim  _wake = new(0, int.MaxValue);
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;
    private long _lastActionTicks;

    public AutoStartStopController(HeaterClient ble, AppSettings settings)
    {
        _ble = ble;
        _settings = settings;
    }

    public void Start()
    {
        if (_loop != null) return;
        _ble.TelemetryChanged += _ => Nudge();
        _ble.StateChanged     += _ => Nudge();
        _settings.Changed     += Nudge;
        _loop = Task.Run(() => RunLoop(_cts.Token));
    }

    private void Nudge() => _wake.Release();

    private async Task RunLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await EvaluateAsync().ConfigureAwait(false); }
            catch (Exception ex) { Log.W("auto", $"Evaluate threw: {ex.Message}"); }

            using var tickCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var tick  = Task.Delay(15_000, tickCts.Token);
            var nudge = _wake.WaitAsync(ct);
            await Task.WhenAny(tick, nudge).ConfigureAwait(false);
            tickCts.Cancel();
        }
    }

    private async Task EvaluateAsync()
    {
        var rules = _settings.AutoRules;
        if (!rules.MasterEnabled) return;
        if (_ble.State != ConnectionState.Ready) return;
        var t = _ble.Telemetry;
        if (t == null) return;

        // Telemetry must be fresh.
        long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (nowMs - t.UpdatedAtMs > rules.StaleTelemetrySec * 1000L)
        {
            Log.I("auto", "Telemetry stale — skipping tick");
            return;
        }

        // Cooldown — don't act more than once per CooldownSec.
        long sinceMs = Environment.TickCount64 - _lastActionTicks;
        if (sinceMs < rules.CooldownSec * 1000L)
        {
            return;
        }

        var amb = t.AmbientTempC ?? double.NaN;
        var batt = t.BatteryV    ?? double.NaN;
        if (double.IsNaN(amb)) return;

        // Battery cutoff is a hard veto — stop if we're below.
        if (rules.BatteryCutoffEnabled && !double.IsNaN(batt) && batt < rules.BatteryCutoffV
            && IsHeating(t.RunningMode))
        {
            Log.I("auto", $"Battery {batt:F1}V < cutoff {rules.BatteryCutoffV:F1}V — stopping");
            await _ble.SendStop().ConfigureAwait(false);
            _lastActionTicks = Environment.TickCount64;
            return;
        }

        // Ambient floor: start if we're below setpoint - margin AND below ambientStart hard floor.
        bool tooCold = amb <= rules.AmbientStartC || amb < rules.SetpointC - rules.MarginC;
        bool tooWarm = amb >= rules.AmbientStopC  || amb > rules.SetpointC + rules.MarginC;

        if (tooCold && !IsHeating(t.RunningMode))
        {
            Log.I("auto", $"Ambient {amb:F1}°C below window — starting");
            await _ble.SendStart().ConfigureAwait(false);
            _lastActionTicks = Environment.TickCount64;
        }
        else if (tooWarm && IsHeating(t.RunningMode))
        {
            Log.I("auto", $"Ambient {amb:F1}°C above window — stopping");
            await _ble.SendStop().ConfigureAwait(false);
            _lastActionTicks = Environment.TickCount64;
        }
    }

    private static bool IsHeating(RunningMode m) =>
        m is RunningMode.Ignition or RunningMode.AutoRun
          or RunningMode.ManualRun or RunningMode.StartStopActive;

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        if (_loop != null) try { await _loop.ConfigureAwait(false); } catch { }
        _cts.Dispose();
    }
}
