namespace Ember.Protocol.HeatGenie;

// HeatGenie driver. Today this is a SHELL: it satisfies the
// IHeaterProtocol contract but every method returns ProtocolResult.Fail.
// The runtime path still uses the existing HeaterClient / Services
// directly.
//
// When we cut over, this class will delegate to (or absorb) the
// existing HeaterClient and ViewModels will be migrated one at a time.
// Until then this exists purely so the abstraction COMPILES end-to-end
// and the rest of the protocol package has a concrete reference impl
// to look at.
public sealed class HeatGenieProtocol : IHeaterProtocol
{
    public ProtocolKind        Kind         => ProtocolKind.HeatGenie;
    public HeaterCapabilities  Capabilities => HeaterCapabilities.HeatGenie;

    public bool IsConnected { get; private set; }
    public event Action<bool>?            ConnectionChanged;
    public event Action<CommonTelemetry>? TelemetryChanged;
    public event Action<byte[]>?          RawFrameReceived;

    public CommonTelemetry Telemetry { get; private set; } = CommonTelemetry.Empty;

    // Suppress "event is never used" warnings — these fire after the
    // refactor; today they're declared so callers can subscribe early.
    private void _suppressUnusedWarnings()
    {
        ConnectionChanged?.Invoke(false);
        TelemetryChanged?.Invoke(CommonTelemetry.Empty);
        RawFrameReceived?.Invoke(Array.Empty<byte>());
    }

    private static Task<ProtocolResult> NotWired() => Task.FromResult(
        ProtocolResult.Fail("HeatGenieProtocol is scaffolded but not yet wired into HeaterClient"));

    public Task<ProtocolResult> ConnectAsync(string mac)             => NotWired();
    public Task<ProtocolResult> DisconnectAsync()                    => NotWired();
    public Task<ProtocolResult> StartAsync()                         => NotWired();
    public Task<ProtocolResult> StopAsync()                          => NotWired();
    public Task<ProtocolResult> VentAsync()                          => NotWired();
    public Task<ProtocolResult> SetTargetCAsync(int celsius)         => NotWired();
    public Task<ProtocolResult> SetGearAsync(int gear)               => NotWired();
    public Task<ProtocolResult> SetAltitudeMAsync(int metres)        => NotWired();
    public Task<ProtocolResult> PulsePumpAsync(int seconds)          => NotWired();
    public Task<ProtocolResult> SendRawAsync(string opcode, byte[] payload) => NotWired();
}
