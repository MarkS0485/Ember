using TsgbHeater.Protocol;

namespace TsgbHeater.Data;

// Mirror of android/.../data/store/BoundDevice.kt — same shape so a future
// sync feature can move records between phones and the laptop trivially.
//
// The Protocol field drives which driver HeaterClient instantiates when
// this device is opened. Existing JSON entries from before this field
// existed deserialize with the HeatGenie default (System.Text.Json fills
// missing record params with the parameter default), which is correct
// since every pre-multi-protocol pairing was a HeatGenie heater.
public sealed record BoundDevice(
    string       Mac,
    string       Name,
    long         LastSeenAtMs,
    bool         AutoStartStopEnabled = false,
    ProtocolKind Protocol             = ProtocolKind.HeatGenie);
