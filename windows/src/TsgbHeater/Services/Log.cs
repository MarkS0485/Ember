using System.IO;

namespace TsgbHeater.Services;

// Tiny file logger — writes everything to %APPDATA%\TsgbHeater\log.txt so
// we have something to grep when the BLE link refuses to come up. The
// HeaterClient is the busiest writer; the UI mirrors important entries
// into the status row via HeaterClient.LastError.
public static class Log
{
    private static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "TsgbHeater", "log.txt");

    private static readonly object Gate = new();

    static Log()
    {
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            // Truncate at process start so we always have a fresh per-session log.
            File.WriteAllText(Path, $"[{DateTime.Now:O}] log opened\n");
        }
        catch { /* never let the logger throw into hot paths */ }
    }

    public static string LogPath => Path;

    public static void I(string tag, string msg) => Write("I", tag, msg);
    public static void W(string tag, string msg) => Write("W", tag, msg);
    public static void E(string tag, string msg) => Write("E", tag, msg);

    private static void Write(string lvl, string tag, string msg)
    {
        var line = $"[{DateTime.Now:HH:mm:ss.fff}] {lvl}/{tag}  {msg}\n";
        try
        {
            lock (Gate) File.AppendAllText(Path, line);
        }
        catch { /* swallow */ }
        try { System.Diagnostics.Debug.Write(line); } catch { }
    }
}
