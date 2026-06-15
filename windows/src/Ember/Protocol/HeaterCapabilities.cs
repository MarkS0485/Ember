namespace Ember.Protocol;

// Per-driver capability declaration. Describes what the heater itself
// can do natively — NOT what the app can do on top.
//
// Distinction matters: Schedule, Groups, AutoStartStop are app-driven
// features that only need Start()/Stop() to work. They are gated on
// `HasStart && HasStop`, not on a dedicated flag here.
//
// The UI consumes this to gate buttons, hide whole pages, show "—"
// for unsupported telemetry fields, etc.
public sealed record HeaterCapabilities
{
    // --- Core control surface ----------------------------------------
    public bool HasStart       { get; init; }
    public bool HasStop        { get; init; }
    public bool HasVent        { get; init; }
    public bool HasTargetTemp  { get; init; }
    public bool HasGear        { get; init; }
    public bool HasRunModes    { get; init; }

    // --- Diagnostic / advanced surface -------------------------------
    public bool HasAltitude        { get; init; }
    public bool HasFaultBits       { get; init; }
    public bool HasManualPump      { get; init; }
    public bool HasNativeSchedule  { get; init; }   // heater stores its own table — read-only mirror
    public bool HasNativeSwitches  { get; init; }   // auto-restart / child-lock / fault-lockout
    public bool HasRawFrameStream  { get; init; }   // debug box wants this

    // --- Telemetry richness ------------------------------------------
    public bool TelemAmbient   { get; init; }
    public bool TelemHousing   { get; init; }
    public bool TelemIntake    { get; init; }
    public bool TelemOutlet    { get; init; }
    public bool TelemBattery   { get; init; }
    public bool TelemFanRpm    { get; init; }
    public bool TelemPumpHz    { get; init; }
    public bool TelemIgnition  { get; init; }

    /// <summary>HeatGenie reports everything — the reference for "fully featured".</summary>
    public static readonly HeaterCapabilities HeatGenie = new()
    {
        HasStart          = true,
        HasStop           = true,
        HasVent           = true,
        HasTargetTemp     = true,
        HasGear           = true,
        HasRunModes       = true,
        HasAltitude       = true,
        HasFaultBits      = true,
        HasManualPump     = true,
        HasNativeSchedule = true,
        HasNativeSwitches = true,
        HasRawFrameStream = true,
        TelemAmbient      = true,
        TelemHousing      = true,
        TelemIntake       = true,
        TelemOutlet       = true,
        TelemBattery      = true,
        TelemFanRpm       = true,
        TelemPumpHz       = true,
        TelemIgnition     = true,
    };

    /// <summary>
    /// HCalory baseline. Conservative — assumes Start/Stop + target temp +
    /// a few common telemetry DPs. Confirm with DP catalog work next session;
    /// flags here are intentionally cautious so the UI hides things we're
    /// unsure about rather than showing them broken.
    /// </summary>
    public static readonly HeaterCapabilities Hcalory = new()
    {
        HasStart       = true,
        HasStop        = true,
        HasTargetTemp  = true,
        HasGear        = true,    // most HCalory units expose gear/level DP
        HasRunModes    = true,
        TelemAmbient   = true,
        TelemBattery   = true,
        // Vent, altitude, pump, fault bits, native schedule/switches,
        // intake/housing/outlet temps — left false until confirmed.
    };
}
