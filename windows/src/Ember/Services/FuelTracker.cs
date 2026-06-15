using Ember.Ble;
using Ember.Data;

namespace Ember.Services;

// Estimates how much fuel is left in the tank by watching telemetry and
// integrating consumption(gear) × time while the heater is burning.
//
// The model is intentionally simple: linear gear→consumption map taken
// from the manual (0.15..0.55 L/h across 10 gears by default; each
// heater can override). Real consumption fluctuates with altitude,
// glow-plug state, fuel viscosity, ambient temp etc. — but for "have I
// got enough to make it through the night" this is plenty accurate,
// and we top up the level manually when we refill anyway.
//
// Thresholds (raised through events so the UI can decide presentation):
//   ≤ 1.00 L  → Warning
//   ≤ 0.50 L  → Critical
//   ≤ 0.25 L  → Shutdown — we additionally call SendStop() so the
//                heater goes through its proper cool-down cycle before
//                the pump actually runs the tank dry.
//
// The shutdown is debounced: we won't fire it twice for the same MAC
// until the level rises above 0.25L again (i.e. a refill happens).
public sealed class FuelTracker : IAsyncDisposable
{
    public enum AlertLevel { None, Warning, Critical, Shutdown }

    public sealed record FuelSnapshot(
        string Mac,
        double CurrentLitres,
        double TankLitres,
        double ConsumptionLowLph,
        double ConsumptionHighLph,
        AlertLevel Alert);

    private readonly HeaterClient      _ble;
    private readonly BoundDeviceStore  _devices;
    private readonly FuelStore         _store;

    private CancellationTokenSource? _cts;
    private Task? _loopTask;

    // Track when we last ticked per MAC so we can do correct elapsed-
    // time integration across telemetry events that come at irregular
    // intervals (typically every 2-5s).
    private readonly Dictionary<string, long> _lastTickAtMs = new(StringComparer.OrdinalIgnoreCase);

    // Track which alert level we last raised per MAC, so we don't spam
    // the user with duplicate warnings.
    private readonly Dictionary<string, AlertLevel> _lastAlert = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Fired whenever the level changes meaningfully (≥10 ml delta) or alert state changes.</summary>
    public event Action<FuelSnapshot>? SnapshotChanged;

    /// <summary>Fired ONCE per crossing of an alert threshold (debounced until level recovers).</summary>
    public event Action<FuelSnapshot>? AlertRaised;

    public FuelTracker(HeaterClient ble, BoundDeviceStore devices, FuelStore store)
    {
        _ble      = ble;
        _devices  = devices;
        _store    = store;
    }

    public void Start()
    {
        if (_cts != null) return;
        _cts = new CancellationTokenSource();
        _ble.TelemetryChanged += OnTelemetry;
        // Backup tick — flushes the store and re-evaluates even if no
        // telemetry has come in (covers the "heater idle on the link" case).
        _loopTask = Task.Run(() => BackgroundLoop(_cts.Token));
    }

    public async ValueTask DisposeAsync()
    {
        _ble.TelemetryChanged -= OnTelemetry;
        _cts?.Cancel();
        if (_loopTask != null)
        {
            try { await _loopTask.ConfigureAwait(false); } catch { }
        }
        _store.Persist();
        _cts?.Dispose();
    }

    // --- Public API used by the UI ----------------------------------

    /// <summary>Snapshot for [mac], computed from current persisted state + bound config.</summary>
    public FuelSnapshot? Snapshot(string mac)
    {
        var device = _devices.FindByMac(mac);
        if (device == null) return null;
        var state = _store.Get(mac);
        var current = state?.CurrentLitres ?? device.TankLitres;
        return new FuelSnapshot(
            Mac:                mac,
            CurrentLitres:      current,
            TankLitres:         device.TankLitres,
            ConsumptionLowLph:  device.ConsumptionLowLph,
            ConsumptionHighLph: device.ConsumptionHighLph,
            Alert:              ClassifyAlert(current));
    }

    /// <summary>Refill the tank by [litres] (capped at the configured tank size).</summary>
    public void Refill(string mac, double litres)
    {
        var device = _devices.FindByMac(mac);
        if (device == null) return;
        var existing = _store.Get(mac)?.CurrentLitres ?? 0.0;
        var next = Math.Min(device.TankLitres, existing + Math.Max(0.0, litres));
        _store.SetLevel(mac, next);
        _lastAlert[mac] = ClassifyAlert(next);  // reset debounce if we climbed back
        RaiseSnapshot(mac);
    }

    /// <summary>Set the current level outright (used by the Settings flyout).</summary>
    public void SetLevel(string mac, double litres)
    {
        var device = _devices.FindByMac(mac);
        if (device == null) return;
        _store.SetLevel(mac, Math.Min(device.TankLitres, Math.Max(0.0, litres)));
        _lastAlert[mac] = ClassifyAlert(_store.Get(mac)!.CurrentLitres);
        RaiseSnapshot(mac);
    }

    // --- Gear → consumption -----------------------------------------

    /// <summary>L/hour at gear [gear] (1..10), linearly interpolated between low/high.</summary>
    public static double ConsumptionForGear(int gear, double lowLph, double highLph)
    {
        int g = Math.Clamp(gear, 1, 10);
        return lowLph + (g - 1) * (highLph - lowLph) / 9.0;
    }

    // --- Internals --------------------------------------------------

    private void OnTelemetry(HeaterTelemetry t)
    {
        var mac = _ble.CurrentMac;
        if (mac == null) return;
        var device = _devices.FindByMac(mac);
        if (device == null) return;

        var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        long lastMs = _lastTickAtMs.TryGetValue(mac, out var v) ? v : nowMs;
        _lastTickAtMs[mac] = nowMs;

        if (IsBurningFuel(t.RunningMode) && t.AimGear is int gear && gear > 0)
        {
            var elapsedHours = Math.Max(0.0, (nowMs - lastMs) / 1000.0 / 3600.0);
            // Cap a single elapsed window at 5 minutes so a long pause
            // (e.g. computer asleep) doesn't bleed the tank instantly.
            elapsedHours = Math.Min(elapsedHours, 5.0 / 60.0);

            var lph = ConsumptionForGear(gear, device.ConsumptionLowLph, device.ConsumptionHighLph);
            var litresUsed = lph * elapsedHours;
            if (litresUsed > 0)
            {
                _store.AdjustLevel(mac, -litresUsed);
                EvaluateAlerts(mac);
                RaiseSnapshot(mac);
            }
        }
        else
        {
            // Not burning — don't accumulate consumption. Still tick the
            // alert state in case a refill changed it via the UI.
            EvaluateAlerts(mac);
        }
    }

    private async Task BackgroundLoop(CancellationToken ct)
    {
        // Persist every 30s — keeps fuel.json fresh without thrashing
        // the disk on every telemetry frame.
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false);
                _store.Persist();
            }
        }
        catch (OperationCanceledException) { /* expected on shutdown */ }
    }

    private static bool IsBurningFuel(RunningMode m) =>
        m is RunningMode.Ignition
            or RunningMode.AutoRun
            or RunningMode.ManualRun
            or RunningMode.StartStopActive;

    private static AlertLevel ClassifyAlert(double litres) =>
        litres <= 0.25 ? AlertLevel.Shutdown
        : litres <= 0.50 ? AlertLevel.Critical
        : litres <= 1.00 ? AlertLevel.Warning
        : AlertLevel.None;

    private void EvaluateAlerts(string mac)
    {
        var snap = Snapshot(mac);
        if (snap == null) return;
        var previous = _lastAlert.TryGetValue(mac, out var prev) ? prev : AlertLevel.None;
        if (snap.Alert == previous) return;

        // Only RAISE on a worsening crossing — recovery (level climbs
        // back) silently resets the debounce. Avoids spam on the way up.
        if ((int)snap.Alert > (int)previous)
        {
            _lastAlert[mac] = snap.Alert;
            AlertRaised?.Invoke(snap);
            if (snap.Alert == AlertLevel.Shutdown)
            {
                Log.W("fuel", $"{mac}: fuel critical ({snap.CurrentLitres:F2} L) — sending stop");
                // Fire-and-forget. Stop goes through the heater's normal
                // cool-down cycle which is the right shape for a
                // running-out-of-fuel shutdown.
                _ = _ble.SendStop();
            }
        }
        else
        {
            // Level climbed (refill) — reset the floor so the next
            // worsening crossing can re-raise.
            _lastAlert[mac] = snap.Alert;
        }
    }

    private void RaiseSnapshot(string mac)
    {
        var snap = Snapshot(mac);
        if (snap != null) SnapshotChanged?.Invoke(snap);
    }
}
