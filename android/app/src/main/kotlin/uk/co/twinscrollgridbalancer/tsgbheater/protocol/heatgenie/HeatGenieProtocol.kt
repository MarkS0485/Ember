package uk.co.twinscrollgridbalancer.tsgbheater.protocol.heatgenie

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.HeaterCapabilities
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.IHeaterProtocol
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind

// HeatGenie / TSGB driver. Today this is a SHELL: it satisfies the
// IHeaterProtocol contract but every method returns a "not yet wired"
// failure. The runtime path still uses the existing HeaterConnection /
// HeaterService directly — see ServiceLocator.
//
// When we cut over, this class will delegate to the existing
// HeaterConnection (or absorb it) and the ViewModels will be migrated
// one at a time. Until then this exists purely so the abstraction
// COMPILES end-to-end and the rest of the protocol package has a
// concrete reference implementation to look at.
class HeatGenieProtocol : IHeaterProtocol {

    override val kind         = ProtocolKind.HEATGENIE
    override val capabilities = HeaterCapabilities.HEATGENIE

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _telemetry = MutableStateFlow(CommonTelemetry.Empty)
    override val telemetry: StateFlow<CommonTelemetry> = _telemetry.asStateFlow()

    override val rawFrames: Flow<ByteArray> = emptyFlow()

    private fun notWired(): Result<Unit> = Result.failure(
        NotImplementedError("HeatGenieProtocol is scaffolded but not yet wired into HeaterConnection")
    )

    override suspend fun connect(mac: String):           Result<Unit> = notWired()
    override suspend fun disconnect():                   Result<Unit> = notWired()
    override suspend fun start():                        Result<Unit> = notWired()
    override suspend fun stop():                         Result<Unit> = notWired()
    override suspend fun vent():                         Result<Unit> = notWired()
    override suspend fun setTargetC(c: Int):             Result<Unit> = notWired()
    override suspend fun setGear(gear: Int):             Result<Unit> = notWired()
    override suspend fun setAltitudeM(metres: Int):      Result<Unit> = notWired()
    override suspend fun pulsePump(seconds: Int):        Result<Unit> = notWired()
    override suspend fun sendRaw(opcode: String, payload: ByteArray): Result<Unit> = notWired()
}
