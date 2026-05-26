package uk.co.twinscrollgridbalancer.tsgbheater.protocol.hcalory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.HeaterCapabilities
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.IHeaterProtocol
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind

// HCalory (Tuya BLE) driver — STUB.
//
// The wire-level codec is real (see TuyaBleFrame.kt). What this class
// is missing is the DP catalog — knowing that "DP 1 bool = power" or
// "DP 4 enum = gear" etc. That mapping needs to be derived from the
// decompiled HCalory sources next session (OLDSRC/hcalory/RESEARCH.md
// has the investigation plan).
//
// Until then this class compiles, advertises its capabilities, but
// returns NotImplementedError on every command. The runtime never
// instantiates it — see ProtocolRegistry's comment.
class HcaloryProtocol : IHeaterProtocol {

    override val kind         = ProtocolKind.HCALORY
    override val capabilities = HeaterCapabilities.HCALORY

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _telemetry = MutableStateFlow(CommonTelemetry.Empty)
    override val telemetry: StateFlow<CommonTelemetry> = _telemetry.asStateFlow()

    override val rawFrames: Flow<ByteArray> = emptyFlow()

    private fun todo(): Result<Unit> = Result.failure(
        NotImplementedError("HcaloryProtocol: DP catalog not yet defined — see OLDSRC/hcalory/RESEARCH.md")
    )

    override suspend fun connect(mac: String):           Result<Unit> = todo()
    override suspend fun disconnect():                   Result<Unit> = todo()
    override suspend fun start():                        Result<Unit> = todo()
    override suspend fun stop():                         Result<Unit> = todo()
    override suspend fun vent():                         Result<Unit> = todo()
    override suspend fun setTargetC(c: Int):             Result<Unit> = todo()
    override suspend fun setGear(gear: Int):             Result<Unit> = todo()
    override suspend fun setAltitudeM(metres: Int):      Result<Unit> = todo()
    override suspend fun pulsePump(seconds: Int):        Result<Unit> = todo()
    override suspend fun sendRaw(opcode: String, payload: ByteArray): Result<Unit> = todo()

    companion object {
        // GATT profile UUIDs — extracted from sources/j2/m.java.
        // Either service may be present; scanner subscribes to both.
        val SERVICE_LEGACY      = java.util.UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_LEGACY   = java.util.UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_LEGACY  = java.util.UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")

        val SERVICE_CUSTOM      = java.util.UUID.fromString("0000BD39-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_CUSTOM   = java.util.UUID.fromString("0000BDF7-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_CUSTOM  = java.util.UUID.fromString("0000BDF8-0000-1000-8000-00805F9B34FB")
    }
}
