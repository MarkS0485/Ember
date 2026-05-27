// Standalone HCalory driver test runner. No UI. Drives HcaloryProtocol
// directly against the heater, logs every byte in/out, exits cleanly
// after a fixed observation window.
//
// Usage:
//   dotnet run --project windows/src/HcaloryTest                       # uses default MAC
//   dotnet run --project windows/src/HcaloryTest -- 20:25:05:12:08:E3  # override MAC
//
// Run from a vantage point where the laptop's BLE radio can see the
// heater. Log file: %APPDATA%\TsgbHeater\log.txt (the same one the WPF
// app uses — opened on each run).

using TsgbHeater.Protocol.Hcalory;
using TsgbHeater.Services;

const string DefaultMac = "20:25:05:12:08:E3";

string mac = args.Length > 0 ? args[0] : DefaultMac;
int holdSeconds = args.Length > 1 && int.TryParse(args[1], out var s) ? s : 30;

Console.WriteLine($"=== HCalory test driver ===");
Console.WriteLine($"MAC:          {mac}");
Console.WriteLine($"observe for:  {holdSeconds}s");
Console.WriteLine($"log file:     {Log.LogPath}");
Console.WriteLine();

var proto = new HcaloryProtocol();

proto.ConnectionChanged += b =>
    Console.WriteLine($"--- connection: {(b ? "CONNECTED" : "DISCONNECTED")} ---");

proto.TelemetryChanged += t =>
    Console.WriteLine($"--- telemetry: mode={t.Mode} target={t.TargetC} ambient={t.AmbientC} ---");

proto.RawFrameReceived += b =>
    Console.WriteLine($"FRAME [{b.Length}B] {Convert.ToHexString(b)}");

Console.WriteLine($">> ConnectAsync({mac}) ...");
var r = await proto.ConnectAsync(mac);
Console.WriteLine($">> result: ok={r.Ok}  err={r.Error}");

if (!r.Ok)
{
    Console.WriteLine("Connect failed — exiting.");
    return 1;
}

// Stay alive long enough to see the post-connect sequence run and
// observe the eventual peer disconnect (if the heater drops us).
Console.WriteLine();
Console.WriteLine($">> holding connection for {holdSeconds}s — watch frames above");
Console.WriteLine();

await Task.Delay(TimeSpan.FromSeconds(holdSeconds));

Console.WriteLine();
Console.WriteLine(">> DisconnectAsync");
await proto.DisconnectAsync();
Console.WriteLine(">> done.");
return 0;
