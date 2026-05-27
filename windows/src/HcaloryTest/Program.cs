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

// Stay alive long enough to see the post-connect sequence run.
Console.WriteLine();
Console.WriteLine($">> holding connection for {holdSeconds}s — watch frames above");
Console.WriteLine();

// After 5 seconds, send a safe, non-destructive command and watch
// the response. J() in the native code (k2/e.java:234) — set
// distance unit, value 0, METER. Layout (15 wire bytes, last is
// checksum we add):
//   00 02 00 01 00 01 00 07 06 00 00 02 00 00
//   ^ 7-byte hdr ^ ^len ^ DP 0x0706 ^   ^value=00 unit=00 (METER)
//
// If the heater echoes back anything other than the standard
// 19-byte ack, that's a clue the command was actually understood.
await Task.Delay(5000);
Console.WriteLine();
Console.WriteLine(">> sending test command: J(0, METER) — set distance unit");
var distanceUnitFrame = new byte[]
{
    0x00, 0x02, 0x00, 0x01, 0x00, 0x01, 0x00,
    0x07,                   // payload length (from byte 8 onwards)
    0x06,                   // continued DP id (so 0x0706)
    0x00,                   // DP type
    0x00, 0x02,             // value length = 2
    0x00,                   // value (0)
    0x00,                   // unit (METER)
};
var r2 = await proto.SendRawTestFrameAsync(distanceUnitFrame);
Console.WriteLine($">> distance unit result: ok={r2.Ok} err={r2.Error}");
Console.WriteLine();

await Task.Delay(TimeSpan.FromSeconds(holdSeconds - 5));

Console.WriteLine();
Console.WriteLine(">> DisconnectAsync");
await proto.DisconnectAsync();
Console.WriteLine(">> done.");
return 0;
