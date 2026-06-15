namespace Ember.Data;

// Snapshot of every tunable that the Auto Start/Stop rules engine reads
// on each tick. All temps in °C, voltage in V, durations in seconds.
// Defaults are sane for a UK van / small cabin install on a 12 V battery.
public sealed record AutoRules(
    bool   MasterEnabled         = false,
    int    SetpointC             = 19,
    int    MarginC               = 2,
    int    AmbientStartC         = 5,
    int    AmbientStopC          = 25,
    double BatteryCutoffV        = 11.0,
    bool   BatteryCutoffEnabled  = true,
    int    CooldownSec           = 300,
    int    StaleTelemetrySec     = 10);
