namespace TsgbHeater.Data;

// Mirror of android/.../data/store/BoundDevice.kt — same shape so a future
// sync feature can move records between phones and the laptop trivially.
public sealed record BoundDevice(
    string Mac,
    string Name,
    long   LastSeenAtMs,
    bool   AutoStartStopEnabled = false);
