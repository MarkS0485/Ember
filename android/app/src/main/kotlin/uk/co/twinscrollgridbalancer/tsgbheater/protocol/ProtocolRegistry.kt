package uk.co.twinscrollgridbalancer.tsgbheater.protocol

import android.content.Context
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.hcalory.HcaloryProtocol
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.heatgenie.HeatGenieProtocol

// Factory + introspection for protocol drivers.
//
// This registry exists but is NOT yet referenced from ServiceLocator.
// When the runtime refactor happens, ServiceLocator will hold one live
// driver per bound device, created via [create] from the device's
// stored ProtocolKind. Until then, calling code continues to use the
// existing HeaterConnection / HeaterService directly.
object ProtocolRegistry {

    /** All drivers available in this build. Useful for the bind-screen picker. */
    val all: List<ProtocolKind> = ProtocolKind.values().toList()

    /** Capabilities for a kind without instantiating the driver — UI gating. */
    fun capabilitiesOf(kind: ProtocolKind): HeaterCapabilities = when (kind) {
        ProtocolKind.HEATGENIE -> HeaterCapabilities.HEATGENIE
        ProtocolKind.HCALORY   -> HeaterCapabilities.HCALORY
    }

    /** Human-friendly label for menus. */
    fun displayName(kind: ProtocolKind): String = when (kind) {
        ProtocolKind.HEATGENIE -> "Heat Genie / TSGB"
        ProtocolKind.HCALORY   -> "HCalory (Tuya)"
    }

    /**
     * Build a driver instance. Cheap — drivers don't open any radio
     * resources until [IHeaterProtocol.connect] is called. Context is
     * needed to construct the underlying BluetoothManager / Gatt.
     */
    fun create(kind: ProtocolKind, ctx: Context): IHeaterProtocol = when (kind) {
        ProtocolKind.HEATGENIE -> HeatGenieProtocol(ctx)
        ProtocolKind.HCALORY   -> HcaloryProtocol(ctx)
    }
}
