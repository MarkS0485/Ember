namespace TsgbHeater.Protocol;

// The thin contract every heater driver implements. Designed so the
// rest of the app (ViewModels, Schedule controller, Auto-Start/Stop,
// Groups, remote API server, etc.) can talk to ANY heater through
// this interface without knowing which protocol is on the wire.
//
// Lifecycle: drivers own their own connection state. The same instance
// can be told to ConnectAsync → use → DisconnectAsync → reconnect.
// Closing means "drop the reference and let GC handle it" — no Dispose
// to keep the contract small (impls can still implement IDisposable
// privately if they need to).
//
// Errors: command methods are async + return ProtocolResult. Drivers
// MAY return Unsupported for calls outside their capability set, but
// the right thing is for the UI to gate these calls in advance.
//
// Thread safety: every member is safe to invoke from any thread.
// Drivers serialise internally.
public interface IHeaterProtocol
{
    ProtocolKind        Kind         { get; }
    HeaterCapabilities  Capabilities { get; }

    // --- Connection lifecycle ----------------------------------------

    /// <summary>Connect to the heater at <paramref name="mac"/>. Idempotent if already connected.</summary>
    Task<ProtocolResult> ConnectAsync(string mac);

    /// <summary>Disconnect cleanly. Idempotent if already disconnected.</summary>
    Task<ProtocolResult> DisconnectAsync();

    /// <summary>Current connection state. <c>true</c> = ready to send commands.</summary>
    bool IsConnected { get; }
    event Action<bool>? ConnectionChanged;

    // --- Live data ---------------------------------------------------

    /// <summary>Latest telemetry snapshot. Always non-null (defaults to <see cref="CommonTelemetry.Empty"/>).</summary>
    CommonTelemetry Telemetry { get; }
    event Action<CommonTelemetry>? TelemetryChanged;

    /// <summary>
    /// Raw notification frames as they arrive — for the Debug Box page.
    /// Drivers without raw frames simply never fire this event; UI gates
    /// on <see cref="HeaterCapabilities.HasRawFrameStream"/>.
    /// </summary>
    event Action<byte[]>? RawFrameReceived;

    // --- Commands ----------------------------------------------------

    Task<ProtocolResult> StartAsync();
    Task<ProtocolResult> StopAsync();
    Task<ProtocolResult> VentAsync();
    Task<ProtocolResult> SetTargetCAsync(int celsius);
    Task<ProtocolResult> SetGearAsync(int gear);
    Task<ProtocolResult> SetAltitudeMAsync(int metres);
    Task<ProtocolResult> PulsePumpAsync(int seconds);

    /// <summary>
    /// Escape hatch for protocol-specific commands the common interface
    /// doesn't model (test mode, raw hex sends, vendor diagnostics). The
    /// <paramref name="opcode"/> is driver-defined.
    /// </summary>
    Task<ProtocolResult> SendRawAsync(string opcode, byte[] payload);
}

public readonly record struct ProtocolResult(bool Ok, string? Error = null)
{
    public static ProtocolResult Success           => new(true);
    public static ProtocolResult Fail(string msg)  => new(false, msg);
    public static ProtocolResult Unsupported(string what) =>
        new(false, $"protocol does not support {what}");
}
