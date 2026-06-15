namespace Ember.Protocol;

// Which wire protocol a bound device speaks. The bind flow picks this
// (or auto-detects by service UUID at scan time) and the registry maps
// it to the right driver implementation.
//
// NB: this enum is intentionally NOT yet referenced by any persisted
// store. Wiring into storage will happen when the runtime path
// actually starts using these drivers.
public enum ProtocolKind
{
    /// <summary>Heat Genie diesel heaters — binary frames, CRC-16/XMODEM.</summary>
    HeatGenie,
    /// <summary>HCalory — Tuya BLE protocol (DP based, byte-sum checksum).</summary>
    Hcalory,
}
