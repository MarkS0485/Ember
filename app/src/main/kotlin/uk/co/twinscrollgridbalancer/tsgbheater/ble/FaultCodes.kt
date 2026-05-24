package uk.co.twinscrollgridbalancer.tsgbheater.ble

// Fault-bit dictionary lifted from the HeatGenie English i18n table. The
// errorCode field in regInfoArea is a 16-bit little-endian bitmask; the
// vendor app shows the first set bit. We expose them all so the user can
// see compound faults.
data class HeaterFault(val bit: Int, val code: String, val short: String, val detail: String)

object FaultCodes {

    val ALL: List<HeaterFault> = listOf(
        HeaterFault(0x0001, "E-01",
            "Undervoltage",
            "Low voltage: 24V system below 18V, or 12V system below 10V."),
        HeaterFault(0x0002, "E-02",
            "Overvoltage",
            "Over voltage: 24V system above 32V, or 12V system above 17V."),
        HeaterFault(0x0004, "E-03",
            "Ignition plug fault",
            "Glow plug short circuit or open circuit."),
        HeaterFault(0x0008, "E-04",
            "Oil pump failure",
            "Oil pump short or open circuit."),
        HeaterFault(0x0010, "E-05",
            "Machine overheating",
            "Housing temperature exceeded 260 °C — check inlet/outlet for blockage."),
        HeaterFault(0x0020, "E-06",
            "Motor fault",
            "Fan short, open, or hall sensor failed to read fan speed."),
        HeaterFault(0x0040, "E-07",
            "Short line fault",
            "Comms cable / plug from control panel to ECU is open or loose."),
        HeaterFault(0x0080, "E-08",
            "Flame extinction",
            "Oil circuit blocked by air or wax — flame went out."),
        HeaterFault(0x0100, "E-09",
            "Sensor fault",
            "Housing temperature sensor short or open."),
        HeaterFault(0x0200, "E-10",
            "Ignition fault",
            "Two failed ignitions in a row — clogged volatile screen, blocked oil, jammed pump, or bad fuel."),
        HeaterFault(0x0400, "E-11",
            "Temperature sensor fault",
            "Ambient temperature sensor short or open."),
        HeaterFault(0x0800, "E-12",
            "Overtemperature",
            "Controller temp exceeded 100 °C — check inlet/outlet blockage or ECU damage."),
        HeaterFault(0x1000, "E-13",
            "Inlet high temp protection",
            "Air-inlet high-temperature protection active. Self-clears."),
        HeaterFault(0x2000, "E-14",
            "Outlet high temp protection",
            "Air-outlet high-temperature protection active. Self-clears."),
        HeaterFault(0x4000, "E-15",
            "Inlet sensor fault",
            "Air-inlet temperature sensor failure."),
        HeaterFault(0x8000, "E-16",
            "Outlet sensor fault",
            "Air-outlet temperature sensor failure."),
    )

    fun active(bitmask: Int): List<HeaterFault> =
        ALL.filter { (bitmask and it.bit) != 0 }
}
