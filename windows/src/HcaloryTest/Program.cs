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

// Experiment: after 8s, send the COMPLEX-PIPELINE "set temp unit"
// command (E() in the native code) — the heater uses t()/u() for
// real settings, and the response might be different from the
// generic 0B 0C ack we keep getting from a()-based queries.
//
// Native E(°C) bytes worked out by hand from k2/e.java:169 +
// r() + t() + u() in j2/j.java:
//   r("02", true, 10) + d("0B","00",5) + "02" + "00" + "FF"
//   → after t(): patches len bytes at 12-16 and 20-24
//   → after u(): drops "FF", appends checksum of bytes 8..13
//
//   final: 00 02 00 01 00 01 00 07 0B 00 00 02 02 00 0F
//          ^ 7B hdr           ^ payloadLen=7   ^ value: flag=02, unit=00 (°C)
//                                ^ DP 0x0B type 0x00 len 0x0002
await Task.Delay(8000);
Console.WriteLine();
Console.WriteLine(">> sending complex-pipeline command: E(Celsius) — set temp unit");
// Pass without checksum, SendRawTestFrameAsync adds it.
var setTempUnitFrame = new byte[]
{
    0x00, 0x02, 0x00, 0x01, 0x00, 0x01, 0x00,
    0x07,                   // payload length (from byte 8 to end inclusive)
    0x0B,                   // DP id
    0x00,                   // DP type
    0x00, 0x02,             // value length = 2
    0x02,                   // flag byte (from native)
    0x00,                   // unit (00 = Celsius)
};
var rCmd = await proto.SendRawTestFrameAsync(setTempUnitFrame);
Console.WriteLine($">> set-temp-unit result: ok={rCmd.Ok} err={rCmd.Error}");
Console.WriteLine();

await Task.Delay(TimeSpan.FromSeconds(holdSeconds - 8));

Console.WriteLine();
Console.WriteLine(">> DisconnectAsync");
await proto.DisconnectAsync();
Console.WriteLine(">> done.");
return 0;
