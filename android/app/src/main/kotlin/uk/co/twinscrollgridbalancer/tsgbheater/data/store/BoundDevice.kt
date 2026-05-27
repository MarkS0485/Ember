package uk.co.twinscrollgridbalancer.tsgbheater.data.store

import kotlinx.serialization.Serializable
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind

// One persisted pairing. The [protocol] field drives which driver
// BleManager instantiates when this device is opened — see
// ProtocolRegistry. Existing JSON entries from before this field
// existed deserialize with the default (HEATGENIE), which is correct
// since every pre-multi-protocol pairing was a HeatGenie heater.
@Serializable
data class BoundDevice(
    val mac: String,
    val name: String,
    val lastSeenAtMs: Long,
    val autoStartStopEnabled: Boolean = false,
    val protocol: ProtocolKind = ProtocolKind.HEATGENIE,
    // --- Fuel tank config (per-heater) ---
    // Mirrors the C# BoundDevice on the Windows side. The defaults
    // match the user's current setup: 5 L tank, 0.15–0.55 L/h
    // consumption across 10 linearly-interpolated gears.
    val tankLitres: Double = 5.0,
    val consumptionLowLph: Double = 0.15,
    val consumptionHighLph: Double = 0.55,
)
