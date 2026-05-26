package uk.co.twinscrollgridbalancer.tsgbheater.protocol

// Which wire protocol a bound device speaks. The bind flow picks this
// (or auto-detects by service UUID at scan time) and the registry maps
// it to the right driver implementation.
//
// NB: this enum is intentionally NOT yet referenced by BoundDevice or
// any persisted store. Wiring in storage will happen when the runtime
// path actually starts using these drivers.
enum class ProtocolKind {
    /** Heater Genie / TSGB diesel heaters — binary frames, CRC-16/XMODEM. */
    HEATGENIE,
    /** HCalory — Tuya BLE protocol (DP based, byte-sum checksum). */
    HCALORY,
}
