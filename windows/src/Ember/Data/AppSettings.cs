using System.IO;
using System.Text.Json;

namespace Ember.Data;

// JSON-blob app settings. Equivalent of the Android AppSettingsStore;
// kept atomic-write simple since this is a tiny config.
public sealed class AppSettings
{
    private readonly string _path;
    private readonly object _lock = new();

    public AppSettings()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Ember");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "settings.json");
        Load();
    }

    // --- Stored values ------------------------------------------------

    public bool KeepAlive               { get; private set; } = true;
    public bool ScheduleModeEnabled     { get; private set; }
    public bool AutoStartStopMaster     { get; private set; }
    public int  SelectedRunModeWire     { get; private set; } = 0;
    public AutoRules AutoRules          { get; private set; } = new();

    public event Action? Changed;

    public void SetKeepAlive(bool v)           { lock (_lock) { KeepAlive           = v; Save(); } Changed?.Invoke(); }
    public void SetScheduleMode(bool v)        { lock (_lock) { ScheduleModeEnabled = v; Save(); } Changed?.Invoke(); }
    public void SetAutoStartStopMaster(bool v) {
        lock (_lock) {
            AutoStartStopMaster = v;
            AutoRules = AutoRules with { MasterEnabled = v };
            Save();
        } Changed?.Invoke();
    }
    public void SetSelectedRunMode(int wire)   { lock (_lock) { SelectedRunModeWire = wire; Save(); } Changed?.Invoke(); }
    public void SetAutoRules(AutoRules r)      {
        lock (_lock) {
            AutoRules = r;
            AutoStartStopMaster = r.MasterEnabled;
            Save();
        } Changed?.Invoke();
    }

    private sealed class Blob
    {
        public bool KeepAlive { get; set; } = true;
        public bool ScheduleModeEnabled { get; set; }
        public bool AutoStartStopMaster { get; set; }
        public int  SelectedRunModeWire { get; set; }
        public AutoRules? AutoRules { get; set; }
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var b = JsonSerializer.Deserialize<Blob>(File.ReadAllText(_path));
            if (b == null) return;
            KeepAlive           = b.KeepAlive;
            ScheduleModeEnabled = b.ScheduleModeEnabled;
            AutoStartStopMaster = b.AutoStartStopMaster;
            SelectedRunModeWire = b.SelectedRunModeWire;
            AutoRules           = b.AutoRules ?? new AutoRules(MasterEnabled: AutoStartStopMaster);
        }
        catch { /* keep defaults */ }
    }

    private void Save()
    {
        var b = new Blob
        {
            KeepAlive           = KeepAlive,
            ScheduleModeEnabled = ScheduleModeEnabled,
            AutoStartStopMaster = AutoStartStopMaster,
            SelectedRunModeWire = SelectedRunModeWire,
            AutoRules           = AutoRules,
        };
        File.WriteAllText(_path,
            JsonSerializer.Serialize(b, new JsonSerializerOptions { WriteIndented = true }));
    }
}
