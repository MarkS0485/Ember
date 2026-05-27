using System.IO;
using System.Text.Json;

namespace TsgbHeater.Data;

// Per-MAC current fuel level. Lives separately from BoundDevice because
// it mutates frequently (every telemetry tick while the heater burns)
// and we don't want to thrash the bound-devices JSON on every change.
//
// Storage: a single JSON object at %APPDATA%\TsgbHeater\fuel.json,
// rewritten lazily — see FuelTracker's persist cadence (every ~30s
// plus on every state-change boundary).
public sealed class FuelStore
{
    public sealed record FuelState(double CurrentLitres, long LastUpdateAtMs);

    private readonly string _path;
    private readonly object _lock = new();
    private Dictionary<string, FuelState> _byMac;

    public event Action? Changed;

    public FuelStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "TsgbHeater");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "fuel.json");
        _byMac = Load();
    }

    /// <summary>Snapshot of current level for the given MAC, or null if never tracked.</summary>
    public FuelState? Get(string mac)
    {
        lock (_lock)
        {
            return _byMac.TryGetValue(NormalizeMac(mac), out var s) ? s : null;
        }
    }

    /// <summary>Replace current level with an exact litres value.</summary>
    public void SetLevel(string mac, double litres)
    {
        var key = NormalizeMac(mac);
        lock (_lock)
        {
            _byMac[key] = new FuelState(
                Math.Max(0.0, litres),
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            Save();
        }
        Changed?.Invoke();
    }

    /// <summary>Add (or subtract, if negative) litres to the current level. Floor at zero.</summary>
    public void AdjustLevel(string mac, double delta)
    {
        var key = NormalizeMac(mac);
        lock (_lock)
        {
            double current = _byMac.TryGetValue(key, out var s) ? s.CurrentLitres : 0.0;
            _byMac[key] = new FuelState(
                Math.Max(0.0, current + delta),
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            // Save handled by caller (FuelTracker) to avoid 1Hz writes.
        }
        Changed?.Invoke();
    }

    /// <summary>Flush to disk. Called by FuelTracker on a slow timer + on state changes.</summary>
    public void Persist()
    {
        lock (_lock) { Save(); }
    }

    private void Save()
    {
        File.WriteAllText(_path,
            JsonSerializer.Serialize(_byMac, new JsonSerializerOptions { WriteIndented = true }));
    }

    private Dictionary<string, FuelState> Load()
    {
        if (!File.Exists(_path)) return new();
        try
        {
            var json = File.ReadAllText(_path);
            if (string.IsNullOrWhiteSpace(json)) return new();
            return JsonSerializer.Deserialize<Dictionary<string, FuelState>>(json) ?? new();
        }
        catch { return new(); }
    }

    private static string NormalizeMac(string mac) => mac.ToUpperInvariant();
}
