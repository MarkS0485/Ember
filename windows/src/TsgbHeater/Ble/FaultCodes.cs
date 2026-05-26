namespace TsgbHeater.Ble;

public sealed record HeaterFault(int Bit, string Code, string Short, string Detail);

// 16-bit fault bitmask from regInfoArea offset 20. Lifted from
// docs/BLE_PROTOCOL.md, identical mapping to android/.../FaultCodes.kt.
public static class FaultCodes
{
    public static readonly IReadOnlyList<HeaterFault> All = new[]
    {
        new HeaterFault(0,  "E-01", "Undervoltage",                   "Low voltage: 24 V below 18 V, 12 V below 10 V."),
        new HeaterFault(1,  "E-02", "Overvoltage",                    "Over voltage: 24 V above 32 V, 12 V above 17 V."),
        new HeaterFault(2,  "E-03", "Ignition plug fault",            "Glow plug short or open circuit."),
        new HeaterFault(3,  "E-04", "Oil pump failure",               "Oil pump short or open circuit."),
        new HeaterFault(4,  "E-05", "Machine overheating",            "Housing temp > 260 °C; check inlet/outlet blockage."),
        new HeaterFault(5,  "E-06", "Motor fault",                    "Fan short / open / hall sensor failure."),
        new HeaterFault(6,  "E-07", "Short-line fault",               "Comms cable / plug to ECU open or loose."),
        new HeaterFault(7,  "E-08", "Flame extinction",               "Oil circuit blocked by air or wax."),
        new HeaterFault(8,  "E-09", "Housing temp sensor fault",      "Housing temp sensor short or open."),
        new HeaterFault(9,  "E-10", "Ignition fault",                 "Two ignition failures; clogged screen / blocked oil / pump jammed / bad fuel."),
        new HeaterFault(10, "E-11", "Ambient sensor fault",           "Ambient temp sensor short or open."),
        new HeaterFault(11, "E-12", "Controller overtemperature",     "Controller temp > 100 °C; check inlet/outlet or ECU damage."),
        new HeaterFault(12, "E-13", "Air inlet high-temp protection", "Self-clears after a short period."),
        new HeaterFault(13, "E-14", "Air outlet high-temp protection","Self-clears after a short period."),
        new HeaterFault(14, "E-15", "Air inlet temp sensor fault",    "Inlet temp sensor failure."),
        new HeaterFault(15, "E-16", "Air outlet temp sensor fault",   "Outlet temp sensor failure."),
    };

    public static IReadOnlyList<HeaterFault> Active(int bits) =>
        All.Where(f => (bits & (1 << f.Bit)) != 0).ToArray();
}
