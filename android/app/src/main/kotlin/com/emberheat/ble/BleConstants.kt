package com.emberheat.ble

import java.util.UUID

// Canonical values lifted from the reverse-engineered HeatGenie JS bundle.
// See docs/BLE_PROTOCOL.md for the source-line citations.
object BleConstants {

    // Primary service. Repurposed 16-bit assigned UUID (Environmental
    // Sensing); the controller advertises only this one. Char UUIDs are
    // NOT hardcoded — HeaterConnection picks by property bits.
    val HEATER_SERVICE: UUID =
        UUID.fromString("0000181a-0000-1000-8000-00805f9b34fb")

    // Standard CCCD that has to be written 0x01-0x00 to enable notifications.
    val CCCD: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Scan filter from the vendor app's onBluetoothDeviceFound. An advert
    // is accepted if EITHER:
    //   1. The device name is literally "boygu" (legacy firmware), OR
    //   2. The name is a 5-tuple of colon-hex bytes XX:XX:XX:XX:XX whose
    //      first byte equals 0xC0 + customerID and fifth byte equals
    //      0xFF - vendorsID. Defaults customer=vendor=1 → 0xC1:**:**:**:0xFE.
    private const val DEFAULT_CUSTOMER_ID = 1
    private const val DEFAULT_VENDOR_ID   = 1

    fun isHeaterName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (name == "boygu") return true
        val parts = name.split(':')
        if (parts.size != 5) return false
        val first = parts[0].toIntOrNull(16) ?: return false
        val fifth = parts[4].toIntOrNull(16) ?: return false
        return first == (0xC0 + DEFAULT_CUSTOMER_ID)
            && fifth == (0xFF - DEFAULT_VENDOR_ID)
    }
}
