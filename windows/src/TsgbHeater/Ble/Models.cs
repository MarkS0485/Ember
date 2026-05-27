namespace TsgbHeater.Ble;

// Running-mode nibble (low 4 bits of machineStatus byte). Labels match the
// vendor app's UI so screenshot diffs across the two clients line up.
public enum RunningMode
{
    Unknown        = -1,
    Boot           = 0,
    Ignition       = 1,
    AutoRun        = 2,
    ManualRun      = 3,
    Cooldown       = 4,
    Standby        = 5,
    Fault          = 6,
    ManualPump     = 7,
    Ventilation    = 8,
    StartStopActive = 9,
    StartStopConfig = 10,
}

public static class RunningModeExt
{
    public static string Label(this RunningMode m) => m switch
    {
        RunningMode.Boot            => "Boot",
        RunningMode.Ignition        => "Ignition",
        RunningMode.AutoRun         => "Running",
        RunningMode.ManualRun       => "Manual",
        RunningMode.Cooldown        => "Cool-down",
        RunningMode.Standby         => "Standby",
        RunningMode.Fault           => "Fault",
        RunningMode.ManualPump      => "Priming pump",
        RunningMode.Ventilation     => "Ventilating",
        RunningMode.StartStopActive => "Auto on",
        RunningMode.StartStopConfig => "Auto configured",
        _                            => "—",
    };

    public static RunningMode FromWire(int v) => v switch
    {
        0  => RunningMode.Boot,
        1  => RunningMode.Ignition,
        2  => RunningMode.AutoRun,
        3  => RunningMode.ManualRun,
        4  => RunningMode.Cooldown,
        5  => RunningMode.Standby,
        6  => RunningMode.Fault,
        7  => RunningMode.ManualPump,
        8  => RunningMode.Ventilation,
        9  => RunningMode.StartStopActive,
        10 => RunningMode.StartStopConfig,
        _  => RunningMode.Unknown,
    };
}

// Snapshot of one decoded regInfoArea frame. Mirrors HeaterTelemetry.kt;
// every field is nullable so a partial frame can still drive the UI.
public sealed record HeaterTelemetry(
    double?     OutletTempC,
    double?     TargetTempC,
    double?     FuelPumpHz,
    int?        FanRpm,
    double?     GlowPlugA,
    double?     BatteryV,
    double?     AmbientTempC,
    double?     HousingTempC,
    double?     IntakeTempC,
    int?        AltitudeM,
    double?     IgnitionWatts,
    RunningMode RunningMode,
    int         FaultBits,
    bool        TempUnitFahrenheit,
    int?        AimGear,
    long        UpdatedAtMs);

// One advert hit during a scan. RSSI updates in-place as we re-see the
// same MAC so the UI can render a live signal bar.
//
// Protocol is filled in by the scanner when it sees a known service UUID
// in the advert. null means "unknown" — could be a HeatGenie that hasn't
// advertised its service yet, could be an unrelated device. The bind
// flow lets the user override.
public sealed record DiscoveredDevice(
    string                            Mac,
    string?                           Name,
    int                               Rssi,
    bool                              IsKnownHeater,
    long                              LastSeenAtMs,
    TsgbHeater.Protocol.ProtocolKind? Protocol = null);

// One slot from a timerInfoArea read.
public sealed record TimerSlot(
    int DayIndex,   // 0..6 (vendor: Mon=0..Sun=6)
    int ModeRaw,    // 0=off, 3=one-shot (folded from 1/2), 4=daily
    int OnHour,
    int OnMin,
    int OffHour,
    int OffMin);

// One slot when writing the timer area.
public sealed record WriteTimerSlot(
    int DayIndex,
    int ModeRaw,
    int OnHour,
    int OnMin,
    int OffHour,
    int OffMin);

// One raw BLE frame in either direction — used by the debug-box console.
public sealed record RawFrame(bool Tx, byte[] Bytes, long TimestampMs);
