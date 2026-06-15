package com.emberheat.protocol

import kotlinx.serialization.Serializable

// Which wire protocol a bound device speaks. The bind flow picks this
// (or auto-detects by service UUID at scan time) and the registry maps
// it to the right driver implementation.
@Serializable
enum class ProtocolKind {
    /** Heater Genie diesel heaters — binary frames, CRC-16/XMODEM. */
    HEATGENIE,
    /** HCalory — Tuya BLE protocol (DP based, byte-sum checksum). */
    HCALORY,
}
