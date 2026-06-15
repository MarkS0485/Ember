package com.emberheat.protocol

// Protocol-neutral telemetry snapshot. Every field is nullable because
// different heaters expose different subsets — the UI is expected to
// show "—" for any field that comes back null.
//
// IMPORTANT: this type does NOT yet replace any existing telemetry
// type (HeatGenieFrame, etc). It's the destination shape for the
// future refactor; right now nothing produces or consumes it.

enum class CommonRunningMode {
    UNKNOWN,
    STANDBY,
    STARTING,
    RUNNING,
    VENT,
    BLOWER_ONLY,
    SHUTDOWN,
    FAULT,
    MANUAL_PUMP,
}

data class CommonTelemetry(
    val mode:        CommonRunningMode = CommonRunningMode.UNKNOWN,
    val modeLabel:   String? = null,   // human-readable, possibly localised by driver

    // Temperatures (°C — UI does any °F conversion itself)
    val ambientC:    Double? = null,
    val housingC:    Double? = null,
    val intakeC:     Double? = null,
    val outletC:     Double? = null,
    val targetC:     Double? = null,

    // Power / fuel
    val batteryV:    Double? = null,
    val fanRpm:      Int?    = null,
    val pumpHz:      Double? = null,
    val ignitionW:   Double? = null,

    // Settings (mirrored from device)
    val aimGear:     Int?    = null,
    val altitudeM:   Int?    = null,
    val tempUnitF:   Boolean? = null,

    // Faults — bitfield meaning is driver-specific; UI uses the
    // driver's decoder to render labels.
    val faultBits:   Int?    = null,

    val updatedAtMs: Long    = 0L,
) {
    companion object {
        val Empty = CommonTelemetry()
    }
}
