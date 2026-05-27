package uk.co.twinscrollgridbalancer.tsgbheater.protocol.heatgenie

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterConnection
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RawFrame
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.HeaterCapabilities
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.IHeaterProtocol
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind

// HeatGenie driver. Adapts the existing [HeaterConnection] (which still
// owns the BLE plumbing and HeatGenie-specific frame codec) to the
// protocol-neutral IHeaterProtocol surface.
//
// The underlying [HeaterConnection] is exposed publicly so BleManager
// can keep forwarding its native HeatGenie flows (HeaterTelemetry,
// RawFrame, ConnectionState) for code paths that haven't been migrated
// to CommonTelemetry yet — the Debug Box, the Flags page, the
// HeatGenie-specific Schedule controller. New code should prefer the
// IHeaterProtocol surface.
class HeatGenieProtocol(ctx: Context) : IHeaterProtocol {

    override val kind         = ProtocolKind.HEATGENIE
    override val capabilities = HeaterCapabilities.HEATGENIE

    // Owned outright by this driver. The previous code path constructed
    // a HeaterConnection inside BleManager directly; with the driver
    // model it lives here instead, one connection per driver instance.
    val connection: HeaterConnection = HeaterConnection(ctx)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- IHeaterProtocol: live data --------------------------------

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _telemetry = MutableStateFlow(CommonTelemetry.Empty)
    override val telemetry: StateFlow<CommonTelemetry> = _telemetry.asStateFlow()

    override val rawFrames: Flow<ByteArray> = connection.frames.map { it.bytes }

    init {
        // Bridge: HeatGenie state → boolean connected. Ready = "we can write".
        connection.state
            .onEach { _isConnected.value = (it == ConnectionState.Ready) }
            .launchIn(scope)

        // Bridge: HeaterTelemetry → CommonTelemetry. Lossless one direction;
        // anything outside CommonTelemetry stays accessible via the native
        // flow on [connection].
        connection.telemetry
            .onEach { t -> _telemetry.value = t?.toCommon() ?: CommonTelemetry.Empty }
            .launchIn(scope)
    }

    // --- IHeaterProtocol: lifecycle --------------------------------

    override suspend fun connect(mac: String): Result<Unit> = runCatching {
        connection.connect(mac)   // idempotent; the existing impl handles re-entry
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        connection.disconnect()
    }

    // --- IHeaterProtocol: commands ---------------------------------

    override suspend fun start():                Result<Unit> = okIf(connection.write(FrameCodec.buildStartHeater()))
    override suspend fun stop():                 Result<Unit> = okIf(connection.write(FrameCodec.buildStopHeater()))
    override suspend fun vent():                 Result<Unit> = okIf(connection.write(FrameCodec.buildBlowOn()))
    override suspend fun setTargetC(c: Int):     Result<Unit> = okIf(
        connection.write(FrameCodec.buildSetTargetTemp(c, FrameCodec.TempUnit.Celsius)))
    override suspend fun setGear(gear: Int):     Result<Unit> = okIf(connection.write(FrameCodec.buildSetGear(gear)))
    override suspend fun setAltitudeM(metres: Int): Result<Unit> =
        Result.failure(NotImplementedError("HeatGenie altitude setter not yet identified — see altitude probe page"))
    override suspend fun pulsePump(seconds: Int): Result<Unit> = okIf(
        connection.write(FrameCodec.buildManualPumpRun(seconds)))
    override suspend fun sendRaw(opcode: String, payload: ByteArray): Result<Unit> = okIf(
        connection.write(payload))

    // --- HeatGenie-specific extras ---------------------------------
    // These are NOT part of the common interface; BleManager calls them
    // after a type-check (`activeDriver as? HeatGenieProtocol`).

    suspend fun setRunMode(mode: FrameCodec.RunMode): Boolean =
        connection.write(FrameCodec.buildSetRunMode(mode))

    suspend fun setTempHysteresis(diff: Int, unit: FrameCodec.TempUnit = FrameCodec.TempUnit.Celsius): Boolean =
        connection.write(FrameCodec.buildSetTempHysteresis(diff, unit))

    suspend fun setClock(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val day = FrameCodec.calendarDayOfWeekToVendor(now.get(java.util.Calendar.DAY_OF_WEEK))
        val hr  = now.get(java.util.Calendar.HOUR_OF_DAY)
        val min = now.get(java.util.Calendar.MINUTE)
        return connection.write(FrameCodec.buildSetClock(day, hr, min))
    }

    suspend fun startTelemetryStream(): Boolean = connection.write(FrameCodec.buildStartTelemetryStream())
    suspend fun stopTelemetryStream():  Boolean = connection.write(FrameCodec.buildStopTelemetryStream())
    suspend fun readRegInfo():          Boolean = connection.write(FrameCodec.buildReadRegInfo())
    suspend fun readTimerInfo():        Boolean = connection.write(FrameCodec.buildReadTimerInfo())

    suspend fun writeTimer(slots: List<FrameCodec.WriteTimerSlot>): Boolean =
        connection.write(FrameCodec.buildWriteTimerArea(slots))

    // Native flow accessors — used while the rest of the codebase still
    // speaks HeaterTelemetry / RawFrame. To be retired once consumers
    // migrate to CommonTelemetry + rawFrames above.
    val nativeState:     StateFlow<ConnectionState>    get() = connection.state
    val nativeTelemetry: StateFlow<HeaterTelemetry?>   get() = connection.telemetry
    val nativeFrames:    SharedFlow<RawFrame>          get() = connection.frames

    // --- Helpers ---------------------------------------------------

    private fun okIf(b: Boolean): Result<Unit> =
        if (b) Result.success(Unit) else Result.failure(RuntimeException("HeatGenie write failed"))
}

// Convert HeatGenie's rich telemetry to the protocol-neutral shape.
// Keep this private to the driver — the conversion is HeatGenie-specific.
private fun HeaterTelemetry.toCommon(): CommonTelemetry = CommonTelemetry(
    mode        = runningMode.toCommonMode(),
    modeLabel   = runningMode.name,
    ambientC    = ambientTempC,
    housingC    = housingTempC,
    intakeC     = intakeTempC,
    outletC     = outletTempC,
    targetC     = targetTempC,
    batteryV    = batteryV,
    fanRpm      = fanRpm,
    pumpHz      = fuelPumpHz,
    ignitionW   = ignitionWatts,
    aimGear     = aimGear,
    altitudeM   = altitudeM,
    tempUnitF   = tempUnitFahrenheit,
    faultBits   = faultBits,
    updatedAtMs = updatedAtMs,
)

private fun RunningMode.toCommonMode(): CommonRunningMode = when (this) {
    RunningMode.Standby          -> CommonRunningMode.STANDBY
    RunningMode.Boot,
    RunningMode.Ignition         -> CommonRunningMode.STARTING
    RunningMode.AutoRun,
    RunningMode.ManualRun,
    RunningMode.StartStopActive  -> CommonRunningMode.RUNNING
    RunningMode.Ventilation      -> CommonRunningMode.VENT
    RunningMode.Cooldown         -> CommonRunningMode.SHUTDOWN
    RunningMode.Fault            -> CommonRunningMode.FAULT
    RunningMode.ManualPump       -> CommonRunningMode.MANUAL_PUMP
    else                         -> CommonRunningMode.UNKNOWN
}
