// Standalone HCalory driver test runner / interactive prober. No UI.
// Drives HcaloryProtocol directly against the heater over the LAPTOP's BLE
// radio, logs every byte in/out, and (in interactive mode) reads frames
// from stdin so we can probe the protocol in a tight loop without rebuilds.
//
// Usage:
//   dotnet run --project windows/src/HcaloryTest                 # interactive, default MAC
//   dotnet run --project windows/src/HcaloryTest -- <MAC>        # interactive, given MAC
//   dotnet run --project windows/src/HcaloryTest -- <MAC> hold 30   # legacy: just observe 30s
//
// Interactive stdin commands (one per line):
//   <hex>            send frame, appending the payload checksum (csum mode)
//   x <hex>          send frame EXACTLY as given (no checksum)
//   s                print the latest decoded STATE line
//   q                quit
// Whitespace/colons in hex are ignored.
//
// Headless batch mode (drivable from a script):
//   dotnet run --project windows/src/HcaloryTest -- <MAC> batch <hex1> <hex2> ...
//   Connects, waits for auth, then fires each frame (csum appended) with a
//   2.5s gap, printing STATE after each, then disconnects. Prefix a frame
//   with 'x:' to send it verbatim (no checksum). Frames may use ':' or '-'
//   internally; they're stripped.

using System.Diagnostics;
using Windows.Devices.Bluetooth.Advertisement;
using Ember.Protocol;
using Ember.Protocol.Hcalory;
using Ember.Services;

const string DefaultMac = "20:25:05:12:08:E3";

string mac = args.Length > 0 && args[0].Contains(':') ? args[0] : DefaultMac;
bool legacyHold = args.Any(a => a.Equals("hold", StringComparison.OrdinalIgnoreCase));
int batchIdx = Array.FindIndex(args, a => a.Equals("batch", StringComparison.OrdinalIgnoreCase));
string[] batchFrames = batchIdx >= 0 ? args[(batchIdx + 1)..] : Array.Empty<string>();
int holdSeconds = 30;
for (int i = 0; i < args.Length - 1; i++)
    if (args[i].Equals("hold", StringComparison.OrdinalIgnoreCase)) int.TryParse(args[i + 1], out holdSeconds);

Console.WriteLine("=== HCalory prober ===");
Console.WriteLine($"MAC:      {mac}");
Console.WriteLine($"log file: {Log.LogPath}");
Console.WriteLine($"mode:     {(legacyHold ? $"observe {holdSeconds}s" : "interactive (stdin)")}");
Console.WriteLine();

var proto = new HcaloryProtocol();
CommonTelemetry latest = CommonTelemetry.Empty;
string lastState = "";

proto.ConnectionChanged += b =>
    Console.WriteLine($"--- connection: {(b ? "CONNECTED" : "DISCONNECTED")} ---");

proto.TelemetryChanged += t =>
{
    latest = t;
    var s = $"dev={t.Mode} target={t.TargetC} ambient={t.AmbientC} batt={t.BatteryV} gear={t.AimGear} fault={t.FaultBits}";
    if (s != lastState) { lastState = s; Console.WriteLine($"STATE {s}"); }
};

proto.RawFrameReceived += b =>
{
    // Only print non-repeating frames so the 1 Hz keepalive doesn't flood.
    var hex = Convert.ToHexString(b);
    if (hex != _lastRaw) { _lastRaw = hex; Console.WriteLine($"RX/TX [{b.Length}B] {hex}"); }
};

// Warm up Windows' BLE cache: FromBluetoothAddressAsync returns null for a
// device Windows hasn't seen advertise recently. Run an advertisement watcher
// until we spot the heater's address, then connect. (The WPF app gets this for
// free by connecting from inside its scan handler; the standalone runner must
// do it explicitly.)
Console.WriteLine(">> warming BLE cache (scanning for heater advert)…");
if (!await WarmUpAsync(mac, TimeSpan.FromSeconds(20)))
    Console.WriteLine("!! never saw the heater advertise — connect will likely fail. (phone still connected? out of range?)");

Console.WriteLine($">> ConnectAsync({mac}) ...");
var r = await proto.ConnectAsync(mac);
Console.WriteLine($">> result: ok={r.Ok}  err={r.Error}");
if (!r.Ok) { Console.WriteLine("Connect failed — exiting."); return 1; }

if (legacyHold)
{
    await Task.Delay(TimeSpan.FromSeconds(holdSeconds));
    await proto.DisconnectAsync();
    return 0;
}

// Give the post-connect auth handshake a moment before sending anything.
await Task.Delay(2500);

if (batchFrames.Length > 0)
{
    Console.WriteLine($">> BATCH: {batchFrames.Length} frame(s)");
    Console.WriteLine($"   baseline STATE: {lastState}");
    foreach (var raw in batchFrames)
    {
        bool ex = raw.StartsWith("x:", StringComparison.OrdinalIgnoreCase);
        var bytes = ParseHex(ex ? raw[2..] : raw);
        if (bytes == null) { Console.WriteLine($"!! bad hex: {raw}"); continue; }
        var before = lastState;
        var res = ex ? await proto.DebugSendExactAsync(bytes) : await proto.SendRawTestFrameAsync(bytes);
        Console.WriteLine($">> SENT {(ex ? "exact " : "")}{Convert.ToHexString(bytes)} ok={res.Ok}");
        await Task.Delay(2500);
        Console.WriteLine(before == lastState
            ? $"   NO CHANGE: {lastState}"
            : $"   CHANGED -> {lastState}");
    }
    Console.WriteLine(">> batch done, disconnecting");
    await proto.DisconnectAsync();
    return 0;
}
Console.WriteLine();
Console.WriteLine(">> interactive. commands: <hex> | x <hex> | s | q");
Console.WriteLine();

string? line;
while ((line = Console.ReadLine()) != null)
{
    line = line.Trim();
    if (line.Length == 0) continue;
    if (line == "q") break;
    if (line == "s") { Console.WriteLine($"STATE {lastState}"); continue; }

    bool exact = false;
    var payload = line;
    if (line.StartsWith("x ", StringComparison.OrdinalIgnoreCase)) { exact = true; payload = line[2..]; }

    var bytes = ParseHex(payload);
    if (bytes == null) { Console.WriteLine("!! bad hex"); continue; }

    var res = exact
        ? await proto.DebugSendExactAsync(bytes)
        : await proto.SendRawTestFrameAsync(bytes);
    Console.WriteLine($">> sent ok={res.Ok} err={res.Error} (waiting 2s for STATE…)");
    await Task.Delay(2000);
    Console.WriteLine($"   now: {lastState}");
}

Console.WriteLine(">> DisconnectAsync");
await proto.DisconnectAsync();
return 0;

// Scan for advertisements until the target MAC is seen (or timeout). Seeing
// the advert populates the OS cache so FromBluetoothAddressAsync resolves.
static async Task<bool> WarmUpAsync(string mac, TimeSpan timeout)
{
    ulong target = MacToUlong(mac);
    var seen = new TaskCompletionSource<bool>();
    var watcher = new BluetoothLEAdvertisementWatcher
    {
        ScanningMode = BluetoothLEScanningMode.Active,
    };
    watcher.Received += (_, e) =>
    {
        if (e.BluetoothAddress == target)
        {
            Console.WriteLine($"   advert seen: {mac} rssi={e.RawSignalStrengthInDBm}dBm");
            seen.TrySetResult(true);
        }
    };
    watcher.Start();
    var done = await Task.WhenAny(seen.Task, Task.Delay(timeout));
    watcher.Stop();
    return done == seen.Task;
}

static ulong MacToUlong(string mac)
{
    var hex = new string(mac.Where(Uri.IsHexDigit).ToArray());
    return Convert.ToUInt64(hex, 16);
}

static byte[]? ParseHex(string s)
{
    var clean = new string(s.Where(Uri.IsHexDigit).ToArray());
    if (clean.Length == 0 || clean.Length % 2 != 0) return null;
    var b = new byte[clean.Length / 2];
    for (int i = 0; i < b.Length; i++) b[i] = Convert.ToByte(clean.Substring(i * 2, 2), 16);
    return b;
}

// module-level field for RawFrameReceived dedupe
partial class Program { static string _lastRaw = ""; }
