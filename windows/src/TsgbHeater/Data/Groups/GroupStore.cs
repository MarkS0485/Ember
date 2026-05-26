using System.IO;
using System.Text.Json;

namespace TsgbHeater.Data.Groups;

public sealed class GroupStore
{
    private readonly string _path;
    private readonly object _lock = new();
    private List<HeaterGroup> _all = new();

    public GroupStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "TsgbHeater");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "groups.json");
        Load();
    }

    public event Action? Changed;

    public IReadOnlyList<HeaterGroup> All { get { lock (_lock) return _all.ToArray(); } }

    public HeaterGroup? Get(string id)
    {
        lock (_lock) return _all.FirstOrDefault(g => g.Id == id);
    }

    public void Add(HeaterGroup g)
    {
        lock (_lock) { _all.Add(g); Save(); }
        Changed?.Invoke();
    }
    public void Update(HeaterGroup g)
    {
        lock (_lock)
        {
            for (int i = 0; i < _all.Count; i++)
                if (_all[i].Id == g.Id) _all[i] = g;
            Save();
        }
        Changed?.Invoke();
    }
    public void Remove(string id)
    {
        lock (_lock) { _all.RemoveAll(g => g.Id == id); Save(); }
        Changed?.Invoke();
    }
    public void DropMacEverywhere(string mac)
    {
        lock (_lock)
        {
            for (int i = 0; i < _all.Count; i++)
            {
                var g = _all[i];
                if (g.MemberMacs.Contains(mac, StringComparer.OrdinalIgnoreCase))
                {
                    _all[i] = g with { MemberMacs = g.MemberMacs.Where(m =>
                        !string.Equals(m, mac, StringComparison.OrdinalIgnoreCase)).ToArray() };
                }
            }
            Save();
        }
        Changed?.Invoke();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var b = JsonSerializer.Deserialize<List<HeaterGroup>>(File.ReadAllText(_path));
            if (b != null) _all = b;
        }
        catch { }
    }
    private void Save()
    {
        File.WriteAllText(_path,
            JsonSerializer.Serialize(_all, new JsonSerializerOptions { WriteIndented = true }));
    }
}
