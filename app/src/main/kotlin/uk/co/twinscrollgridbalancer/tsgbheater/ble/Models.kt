package uk.co.twinscrollgridbalancer.tsgbheater.ble

// One advert seen during a scan. RSSI is updated in place as we re-see the
// same device, so the UI can render a live signal-strength bar.
data class DiscoveredDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val isKnownHeater: Boolean,
    val lastSeenAtMs: Long,
)

// One-shot enum that the GATT state machine emits. Names match the vendor
// app's status text so log readers comparing the two apps don't get lost.
enum class ConnectionState {
    Idle,           // never tried / explicitly disconnected
    Scanning,       // device-list flow only
    Connecting,
    DiscoveringServices,
    Ready,
    Reconnecting,
    Failed,
}

// Decoded live telemetry frame. Every field is nullable so a partially
// parsed frame can still drive the UI. See docs/BLE_PROTOCOL.md for the
// regInfoArea (tag 0xF2) byte layout this is decoded from.
data class HeaterTelemetry(
    val outletTempC:        Double?       = null,
    val targetTempC:        Double?       = null,
    val fuelPumpHz:         Double?       = null,
    val fanRpm:             Int?          = null,
    val glowPlugA:          Double?       = null,
    val batteryV:           Double?       = null,
    val ambientTempC:       Double?       = null,
    val housingTempC:       Double?       = null,
    val intakeTempC:        Double?       = null,
    val altitudeM:          Int?          = null,
    val ignitionWatts:      Double?       = null,
    val runningMode:        RunningMode   = RunningMode.Unknown,
    val faultBits:          Int           = 0,
    val tempUnitFahrenheit: Boolean       = false,
    val aimGear:            Int?          = null,
    val updatedAtMs:        Long          = System.currentTimeMillis(),
)

// One slot from the timerInfoArea (tag 0xF5). Slots are weekday-indexed
// starting Sunday (index 0). The vendor app folds raw mode 1/2 to display
// value 3 and raw ≥4 to display value 4; both raw and folded are exposed
// so future protocol experiments can map the raw values back to behaviour.
data class TimerSlot(
    val dayIndex: Int,        // 0..6
    val modeRaw:  Int,
    val mode:     TimerMode,
    val onHour:   Int,
    val onMin:    Int,
    val offHour:  Int,
    val offMin:   Int,
) {
    val onLabel:  String get() = "%02d:%02d".format(onHour, onMin)
    val offLabel: String get() = "%02d:%02d".format(offHour, offMin)
}

enum class TimerMode(val folded: Int, val label: String) {
    Off(0,          "Off"),
    SingleShot(3,   "One-shot"),   // raw 1 or 2 folds here
    Recurring(4,    "Daily"),      // raw ≥4 folds here
    Unknown(-1,     "—");
    companion object {
        fun fromFolded(v: Int): TimerMode = entries.firstOrNull { it.folded == v } ?: Unknown
    }
}

// One BLE frame in either direction — kept as opaque bytes so the debug
// console can render hex even when the codec doesn't recognise the opcode.
data class RawFrame(
    val tx: Boolean,
    val bytes: ByteArray,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawFrame) return false
        if (tx != other.tx) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return timestampMs == other.timestampMs
    }
    override fun hashCode(): Int {
        var r = tx.hashCode()
        r = 31 * r + bytes.contentHashCode()
        r = 31 * r + timestampMs.hashCode()
        return r
    }
}
