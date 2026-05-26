namespace TsgbHeater.Protocol;

// Protocol-neutral telemetry snapshot. Every field is nullable because
// different heaters expose different subsets — the UI is expected to
// show "—" for any field that comes back null.
//
// IMPORTANT: this type does NOT yet replace any existing telemetry
// type. It's the destination shape for the future refactor; right
// now nothing produces or consumes it.

public enum CommonRunningMode
{
    Unknown,
    Standby,
    Starting,
    Running,
    Vent,
    BlowerOnly,
    Shutdown,
    Fault,
    ManualPump,
}

public sealed record CommonTelemetry
{
    public CommonRunningMode Mode       { get; init; } = CommonRunningMode.Unknown;
    public string?           ModeLabel  { get; init; }   // human-readable, possibly localised by driver

    // Temperatures (°C — UI does any °F conversion itself)
    public double?  AmbientC  { get; init; }
    public double?  HousingC  { get; init; }
    public double?  IntakeC   { get; init; }
    public double?  OutletC   { get; init; }
    public double?  TargetC   { get; init; }

    // Power / fuel
    public double?  BatteryV   { get; init; }
    public int?     FanRpm     { get; init; }
    public double?  PumpHz     { get; init; }
    public double?  IgnitionW  { get; init; }

    // Settings (mirrored from device)
    public int?     AimGear     { get; init; }
    public int?     AltitudeM   { get; init; }
    public bool?    TempUnitF   { get; init; }

    // Faults — bitfield meaning is driver-specific; UI uses the
    // driver's decoder to render labels.
    public int?     FaultBits   { get; init; }

    public long     UpdatedAtMs { get; init; }

    public static readonly CommonTelemetry Empty = new();
}
