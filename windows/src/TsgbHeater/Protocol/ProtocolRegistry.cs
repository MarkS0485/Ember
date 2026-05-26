using TsgbHeater.Protocol.HeatGenie;
using TsgbHeater.Protocol.Hcalory;

namespace TsgbHeater.Protocol;

// Factory + introspection for protocol drivers.
//
// This registry exists but is NOT yet referenced from ServiceLocator.
// When the runtime refactor happens, ServiceLocator will hold one live
// driver per bound device, created via Create() from the device's
// stored ProtocolKind. Until then, calling code continues to use the
// existing HeaterClient directly.
public static class ProtocolRegistry
{
    /// <summary>All drivers available in this build. Useful for bind-screen pickers.</summary>
    public static IReadOnlyList<ProtocolKind> All { get; } =
        Enum.GetValues<ProtocolKind>();

    /// <summary>Capabilities for a kind without instantiating the driver — UI gating.</summary>
    public static HeaterCapabilities CapabilitiesOf(ProtocolKind kind) => kind switch
    {
        ProtocolKind.HeatGenie => HeaterCapabilities.HeatGenie,
        ProtocolKind.Hcalory   => HeaterCapabilities.Hcalory,
        _                      => throw new ArgumentOutOfRangeException(nameof(kind)),
    };

    /// <summary>Human-friendly label for menus.</summary>
    public static string DisplayName(ProtocolKind kind) => kind switch
    {
        ProtocolKind.HeatGenie => "Heat Genie / TSGB",
        ProtocolKind.Hcalory   => "HCalory (Tuya)",
        _                      => throw new ArgumentOutOfRangeException(nameof(kind)),
    };

    /// <summary>
    /// Build a driver instance. Cheap — drivers don't open any radio
    /// resources until <see cref="IHeaterProtocol.ConnectAsync"/> is called.
    /// </summary>
    public static IHeaterProtocol Create(ProtocolKind kind) => kind switch
    {
        ProtocolKind.HeatGenie => new HeatGenieProtocol(),
        ProtocolKind.Hcalory   => new HcaloryProtocol(),
        _                      => throw new ArgumentOutOfRangeException(nameof(kind)),
    };
}
