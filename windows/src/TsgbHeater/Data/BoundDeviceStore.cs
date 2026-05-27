using System.IO;
using System.Text.Json;

namespace TsgbHeater.Data;

// Simple JSON-blob store. ~3-row max per real user, so atomic-write semantics
// are fine.
public sealed class BoundDeviceStore
{
    private readonly string _devicesPath;
    private readonly string _currentMacPath;
    private readonly object _lock = new();

    public BoundDeviceStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "TsgbHeater");
        Directory.CreateDirectory(dir);
        _devicesPath    = Path.Combine(dir, "bound_devices.json");
        _currentMacPath = Path.Combine(dir, "current_mac.txt");

        _all = Load();
        _currentMac = File.Exists(_currentMacPath)
            ? File.ReadAllText(_currentMacPath).Trim().NullIfEmpty()
            : null;
    }

    private List<BoundDevice> _all;
    private string? _currentMac;

    public event Action? Changed;

    public IReadOnlyList<BoundDevice> All
    {
        get { lock (_lock) return _all.ToArray(); }
    }
    public string? CurrentMac
    {
        get { lock (_lock) return _currentMac; }
    }

    public void Add(BoundDevice d)
    {
        lock (_lock)
        {
            int idx = _all.FindIndex(x => x.Mac.Equals(d.Mac, StringComparison.OrdinalIgnoreCase));
            if (idx >= 0) _all[idx] = d;
            else _all.Add(d);
            Save();
        }
        Changed?.Invoke();
    }

    public void Remove(string mac)
    {
        lock (_lock)
        {
            _all.RemoveAll(x => x.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
            if (_currentMac?.Equals(mac, StringComparison.OrdinalIgnoreCase) == true)
            {
                _currentMac = null;
                File.Delete(_currentMacPath);
            }
            Save();
        }
        Changed?.Invoke();
    }

    public void Rename(string mac, string newName)
    {
        lock (_lock)
        {
            for (int i = 0; i < _all.Count; i++)
            {
                if (_all[i].Mac.Equals(mac, StringComparison.OrdinalIgnoreCase))
                {
                    _all[i] = _all[i] with { Name = newName };
                }
            }
            Save();
        }
        Changed?.Invoke();
    }

    public void TouchLastSeen(string mac)
    {
        lock (_lock)
        {
            long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            for (int i = 0; i < _all.Count; i++)
            {
                if (_all[i].Mac.Equals(mac, StringComparison.OrdinalIgnoreCase))
                {
                    _all[i] = _all[i] with { LastSeenAtMs = now };
                }
            }
            Save();
        }
        Changed?.Invoke();
    }

    /// <summary>
    /// Update the per-heater fuel-tank config (tank litres, consumption
    /// range). Null arguments keep the existing value, so the call site
    /// can update any subset.
    /// </summary>
    public void UpdateFuelConfig(string mac,
                                  double? tankLitres = null,
                                  double? consumptionLowLph = null,
                                  double? consumptionHighLph = null)
    {
        lock (_lock)
        {
            for (int i = 0; i < _all.Count; i++)
            {
                if (_all[i].Mac.Equals(mac, StringComparison.OrdinalIgnoreCase))
                {
                    _all[i] = _all[i] with
                    {
                        TankLitres         = tankLitres         ?? _all[i].TankLitres,
                        ConsumptionLowLph  = consumptionLowLph  ?? _all[i].ConsumptionLowLph,
                        ConsumptionHighLph = consumptionHighLph ?? _all[i].ConsumptionHighLph,
                    };
                }
            }
            Save();
        }
        Changed?.Invoke();
    }

    /// <summary>Convenience lookup by MAC. Returns null if not bound.</summary>
    public BoundDevice? FindByMac(string mac)
    {
        lock (_lock)
        {
            return _all.FirstOrDefault(b =>
                b.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
        }
    }

    public void SetCurrent(string? mac)
    {
        lock (_lock)
        {
            _currentMac = mac;
            if (mac == null) File.Delete(_currentMacPath);
            else File.WriteAllText(_currentMacPath, mac);
        }
        Changed?.Invoke();
    }

    private List<BoundDevice> Load()
    {
        if (!File.Exists(_devicesPath)) return new();
        try
        {
            var json = File.ReadAllText(_devicesPath);
            if (string.IsNullOrWhiteSpace(json)) return new();
            return JsonSerializer.Deserialize<List<BoundDevice>>(json) ?? new();
        }
        catch { return new(); }
    }

    private void Save()
    {
        File.WriteAllText(_devicesPath,
            JsonSerializer.Serialize(_all, new JsonSerializerOptions { WriteIndented = true }));
    }
}

internal static class StringExt
{
    public static string? NullIfEmpty(this string? s) =>
        string.IsNullOrWhiteSpace(s) ? null : s;
}
