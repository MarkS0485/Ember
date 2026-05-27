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
)
