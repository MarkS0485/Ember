namespace TsgbHeater.Protocol.Hcalory;

// HCalory (Tuya BLE) driver — STUB.
//
// The wire-level codec is real (see TuyaBleFrame.cs). What this class
// is missing is the DP catalog — knowing that "DP 1 bool = power" or
// "DP 4 enum = gear" etc. That mapping needs to be derived from the
// decompiled HCalory sources (OLDSRC/hcalory/RESEARCH.md has the
// investigation plan).
//
// Until then this class compiles, advertises its capabilities, but
// returns ProtocolResult.Fail on every command. The runtime never
// instantiates it — see ProtocolRegistry's comment.
public sealed class HcaloryProtocol : IHeaterProtocol
{
    public ProtocolKind        Kind         => ProtocolKind.Hcalory;
    public HeaterCapabilities  Capabilities => HeaterCapabilities.Hcalory;

    public bool IsConnected { get; private set; }
    public event Action<bool>?            ConnectionChanged;
    public event Action<CommonTelemetry>? TelemetryChanged;
    public event Action<byte[]>?          RawFrameReceived;

    public CommonTelemetry Telemetry { get; private set; } = CommonTelemetry.Empty;

    private void _suppressUnusedWarnings()
    {
        ConnectionChanged?.Invoke(false);
        TelemetryChanged?.Invoke(CommonTelemetry.Empty);
        RawFrameReceived?.Invoke(Array.Empty<byte>());
    }

    private static Task<ProtocolResult> Todo() => Task.FromResult(
        ProtocolResult.Fail("HcaloryProtocol: DP catalog not yet defined — see OLDSRC/hcalory/RESEARCH.md"));

    public Task<ProtocolResult> ConnectAsync(string mac)             => Todo();
    public Task<ProtocolResult> DisconnectAsync()                    => Todo();
    public Task<ProtocolResult> StartAsync()                         => Todo();
    public Task<ProtocolResult> StopAsync()                          => Todo();
    public Task<ProtocolResult> VentAsync()                          => Todo();
    public Task<ProtocolResult> SetTargetCAsync(int celsius)         => Todo();
    public Task<ProtocolResult> SetGearAsync(int gear)               => Todo();
    public Task<ProtocolResult> SetAltitudeMAsync(int metres)        => Todo();
    public Task<ProtocolResult> PulsePumpAsync(int seconds)          => Todo();
    public Task<ProtocolResult> SendRawAsync(string opcode, byte[] payload) => Todo();

    // GATT profile UUIDs — extracted from sources/j2/m.java. Either
    // service may be present; scanner subscribes to both.
    public static readonly Guid ServiceLegacy     = new("0000FFF0-0000-1000-8000-00805F9B34FB");
    public static readonly Guid WriteCharLegacy   = new("0000FFF2-0000-1000-8000-00805F9B34FB");
    public static readonly Guid NotifyCharLegacy  = new("0000FFF1-0000-1000-8000-00805F9B34FB");

    public static readonly Guid ServiceCustom     = new("0000BD39-0000-1000-8000-00805F9B34FB");
    public static readonly Guid WriteCharCustom   = new("0000BDF7-0000-1000-8000-00805F9B34FB");
    public static readonly Guid NotifyCharCustom  = new("0000BDF8-0000-1000-8000-00805F9B34FB");
}
