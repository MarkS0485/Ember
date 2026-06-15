using System.IO;
using System.Text.Json;

namespace Ember.Data.Schedule;

public sealed class ScheduleStore
{
    private readonly string _path;
    private readonly object _lock = new();
    private Schedule _schedule = new();

    public ScheduleStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Ember");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "schedule.json");
        Load();
    }

    public Schedule Get()
    {
        lock (_lock) return _schedule;
    }

    public event Action? Changed;

    public void Set(Schedule s)
    {
        lock (_lock) { _schedule = s; Save(); }
        Changed?.Invoke();
    }

    public void Mutate(Func<Schedule, Schedule> transform) => Set(transform(Get()));

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var b = JsonSerializer.Deserialize<Schedule>(File.ReadAllText(_path));
            if (b != null) _schedule = b;
        }
        catch { /* keep default */ }
    }

    private void Save()
    {
        File.WriteAllText(_path,
            JsonSerializer.Serialize(_schedule, new JsonSerializerOptions { WriteIndented = true }));
    }
}
