using Ember.Protocol;

namespace Ember.Data;

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
    ProtocolKind Protocol             = ProtocolKind.HeatGenie,
    // --- Fuel tank config (per-heater) ---
    // Total tank capacity, in litres. The default of 5L matches the
    // common 5-litre canister the user runs today; each heater can have
    // a different tank.
    double       TankLitres           = 5.0,
    // Consumption range from the manual: at lowest gear (1) the
    // burner uses ConsumptionLowLph litres/hour; at highest gear (10),
    // ConsumptionHighLph. The FuelTracker linearly interpolates between
    // these for whichever gear is reported in telemetry.
    double       ConsumptionLowLph    = 0.15,
    double       ConsumptionHighLph   = 0.55);
