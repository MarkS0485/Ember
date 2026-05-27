package uk.co.twinscrollgridbalancer.tsgbheater.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDeviceStore
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.IHeaterProtocol
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolKind
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.ProtocolRegistry
import uk.co.twinscrollgridbalancer.tsgbheater.protocol.heatgenie.HeatGenieProtocol

// Single source of truth for the BLE side. ViewModels observe its flows;
// HeaterService keeps a reference so the connection survives the activity.
// Created once at app start and held by ServiceLocator.
//
// Now protocol-aware: on each [connect], BleManager picks the right
// driver (HeatGenie or HCalory) based on the bound device's
// [BoundDevice.protocol] field, and routes commands through it.
//
// The legacy HeatGenie-shaped flows (state: ConnectionState,
// telemetry: HeaterTelemetry?, frames: RawFrame) are still exposed for
// VMs that haven't migrated to CommonTelemetry — they populate only
// when the active driver IS HeatGenie. For HCalory and any future
// protocol, callers should consume [commonTelemetry] and [isConnected].
class BleManager(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scanner = BleScanner(ctx)

    // The currently-active protocol driver. Null until [connect] is called.
    // A driver instance is kept around per MAC for the lifetime of that
    // pairing — calling connect(mac) for the same MAC twice reuses it.
    @Volatile private var activeDriver: IHeaterProtocol? = null
    @Volatile private var activeMac: String? = null

    /** Currently-connected MAC (or null if no active link). Read-only accessor. */
    fun activeMac(): String? = activeMac

    // Subscription jobs for the active driver's flows. Cancelled on
    // disconnect / driver switch so we don't double-publish.
    private var subState: Job?     = null
    private var subTele:  Job?     = null
    private var subRaw:   Job?     = null
    private var subConn:  Job?     = null
    private var subCommon: Job?    = null

    // --- Public flows --------------------------------------------------

    // HeatGenie-shaped flows. Populated only while a HeatGenie driver is
    // active. ViewModels using these break gracefully on HCalory because
    // every consumer already handles the "no telemetry yet" case.
    private val _state = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow<HeaterTelemetry?>(null)
    val telemetry: StateFlow<HeaterTelemetry?> = _telemetry.asStateFlow()

    private val _frames = MutableSharedFlow<RawFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<RawFrame> = _frames.asSharedFlow()

    // Protocol-neutral flows. Populated for every driver. New code
    // should prefer these; over time we'll migrate ViewModels off the
    // HeatGenie-shaped ones above.
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _commonTelemetry = MutableStateFlow(CommonTelemetry.Empty)
    val commonTelemetry: StateFlow<CommonTelemetry> = _commonTelemetry.asStateFlow()

    // Which protocol is currently in charge. UI uses this for capability
    // gating (e.g. "hide Switches page when on HCalory").
    private val _activeProtocol = MutableStateFlow<ProtocolKind?>(null)
    val activeProtocol: StateFlow<ProtocolKind?> = _activeProtocol.asStateFlow()

    // --- Devices (scan output) ----------------------------------------

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (scanJob?.isActive == true) return
        _devices.value = emptyList()
        _scanning.value = true
        scanJob = scope.launch {
            try {
                scanner.scan().collect { hit -> mergeDevice(hit) }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun isBluetoothEnabled(): Boolean = scanner.isBluetoothEnabled()

    private fun mergeDevice(hit: DiscoveredDevice) {
        _devices.update { list ->
            val idx = list.indexOfFirst { it.mac == hit.mac }
            if (idx >= 0) {
                val merged = list[idx].copy(
                    rssi          = hit.rssi,
                    name          = hit.name ?: list[idx].name,
                    isKnownHeater = list[idx].isKnownHeater || hit.isKnownHeater,
                    lastSeenAtMs  = hit.lastSeenAtMs,
                    protocol      = hit.protocol ?: list[idx].protocol,
                )
                list.toMutableList().also { it[idx] = merged }
            } else {
                list + hit
            }
        }
    }

    // --- Connection orchestration ------------------------------------

    // Connect to [mac]. Looks up the bound device to learn which protocol
    // to speak; falls back to HeatGenie for unknown MACs (the historical
    // default, since every pre-multi-protocol pairing was HeatGenie).
    fun connect(mac: String) {
        stopScan()
        scope.launch {
            val kind = boundDevices?.findByMac(mac)?.protocol ?: ProtocolKind.HEATGENIE
            ensureDriver(kind, mac)
            activeDriver?.connect(mac)
        }
    }

    fun disconnect() {
        scope.launch { activeDriver?.disconnect() }
    }

    // Resolve / instantiate the driver. If the same kind+mac is already
    // active, this is a no-op. Switching kind requires tearing down the
    // previous driver first (subscriptions cancelled, BLE link closed).
    private suspend fun ensureDriver(kind: ProtocolKind, mac: String) {
        val current = activeDriver
        if (current != null && current.kind == kind && activeMac == mac) return
        current?.let { runCatching { it.disconnect() } }
        cancelSubscriptions()
        val drv = ProtocolRegistry.create(kind, ctx)
        activeDriver = drv
        activeMac = mac
        _activeProtocol.value = kind
        bindDriverFlows(drv)
    }

    private fun cancelSubscriptions() {
        subState?.cancel();  subState  = null
        subTele?.cancel();   subTele   = null
        subRaw?.cancel();    subRaw    = null
        subConn?.cancel();   subConn   = null
        subCommon?.cancel(); subCommon = null
        _state.value     = ConnectionState.Idle
        _telemetry.value = null
        _isConnected.value = false
        _commonTelemetry.value = CommonTelemetry.Empty
    }

    private fun bindDriverFlows(drv: IHeaterProtocol) {
        // Protocol-neutral flows (always)
        subConn = drv.isConnected.onEach { _isConnected.value = it }.launchIn(scope)
        subCommon = drv.telemetry.onEach { _commonTelemetry.value = it }.launchIn(scope)

        if (drv is HeatGenieProtocol) {
            // HeatGenie path: forward its native flows directly. Lossless.
            subState = drv.nativeState
                .onEach { _state.value = it }
                .launchIn(scope)
            subTele = drv.nativeTelemetry
                .onEach { _telemetry.value = it }
                .launchIn(scope)
            subRaw = drv.nativeFrames
                .onEach { _frames.tryEmit(it) }
                .launchIn(scope)
        } else {
            // Non-HeatGenie (HCalory and future): synthesize HeaterTelemetry
            // from CommonTelemetry so existing ViewModels (DeviceViewModel
            // etc.) continue to work unchanged. Fields the protocol doesn't
            // publish stay null; the UI already renders "—" for those.
            // Connection state collapses to a binary Ready/Idle since
            // non-HeatGenie drivers don't expose the full HeatGenie state
            // machine.
            subState = drv.isConnected
                .onEach { _state.value = if (it) ConnectionState.Ready else ConnectionState.Idle }
                .launchIn(scope)
            subTele = drv.telemetry
                .onEach { _telemetry.value = it.toHeaterTelemetry() }
                .launchIn(scope)
            // Raw frame bytes — wrap in RawFrame(tx=false). HCalory's driver
            // emits both rx and tx via the same flow; close enough for the
            // debug box, which renders direction = "?" when ambiguous.
            subRaw = drv.rawFrames
                .onEach { _frames.tryEmit(RawFrame(tx = false, bytes = it, timestampMs = System.currentTimeMillis())) }
                .launchIn(scope)
        }
    }

    // Convert protocol-neutral CommonTelemetry to the HeatGenie-shaped
    // HeaterTelemetry for the benefit of ViewModels that have not yet
    // migrated to CommonTelemetry. Fields the source doesn't publish
    // remain null; runningMode collapses to its closest HeatGenie peer.
    private fun CommonTelemetry.toHeaterTelemetry(): HeaterTelemetry = HeaterTelemetry(
        outletTempC        = outletC,
        targetTempC        = targetC,
        fuelPumpHz         = pumpHz,
        fanRpm             = fanRpm,
        glowPlugA          = null,
        batteryV           = batteryV,
        ambientTempC       = ambientC,
        housingTempC       = housingC,
        intakeTempC        = intakeC,
        altitudeM          = altitudeM,
        ignitionWatts      = ignitionW,
        runningMode        = mode.toHeatGenieRunningMode(),
        faultBits          = faultBits ?: 0,
        tempUnitFahrenheit = tempUnitF ?: false,
        aimGear            = aimGear,
        updatedAtMs        = if (updatedAtMs > 0) updatedAtMs else System.currentTimeMillis(),
    )

    private fun uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.toHeatGenieRunningMode(): RunningMode = when (this) {
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.STANDBY    -> RunningMode.Standby
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.STARTING   -> RunningMode.Ignition
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.RUNNING    -> RunningMode.AutoRun
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.VENT,
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.BLOWER_ONLY -> RunningMode.Ventilation
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.SHUTDOWN   -> RunningMode.Cooldown
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.FAULT      -> RunningMode.Fault
        uk.co.twinscrollgridbalancer.tsgbheater.protocol.CommonRunningMode.MANUAL_PUMP -> RunningMode.ManualPump
        else                                                                          -> RunningMode.Unknown
    }

    // --- Commands (protocol-neutral surface) -------------------------
    // These were previously bound to HeaterConnection directly; now they
    // route through whichever driver is active. HeatGenie path is
    // unchanged in behaviour (HeatGenieProtocol forwards to FrameCodec).

    suspend fun sendStart(): Boolean = activeDriver?.start()?.isSuccess == true
    suspend fun sendStop():  Boolean = activeDriver?.stop()?.isSuccess == true

    suspend fun setTargetTemp(value: Int, unit: FrameCodec.TempUnit = FrameCodec.TempUnit.Celsius): Boolean {
        // The common surface takes Celsius. For now if a Fahrenheit value
        // is passed (used in some older call-sites), convert here so the
        // driver doesn't have to know about temperature units.
        val celsius = if (unit == FrameCodec.TempUnit.Fahrenheit) ((value - 32) * 5 / 9) else value
        return activeDriver?.setTargetC(celsius)?.isSuccess == true
    }

    suspend fun setGear(gear: Int): Boolean =
        activeDriver?.setGear(gear)?.isSuccess == true

    suspend fun blowOn(): Boolean = activeDriver?.vent()?.isSuccess == true

    // The vendor app's manual-prime path sends DB0_DN_MANU_PUMP with a
    // seconds value, not CMD_OIL_PUMP_ON; without the duration the
    // controller ignores the request and the pump never spins. The heater
    // also only accepts this frame in runningMode 5 (Standby) or 7
    // (ManualPump) — see HeaterService.runPumpCommand for the gate.
    // HCalory has no equivalent; protocol returns Unsupported.
    suspend fun oilPumpOn(seconds: Int = FrameCodec.MANUAL_PUMP_DEFAULT_S): Boolean =
        activeDriver?.pulsePump(seconds)?.isSuccess == true

    // Vendor's iolPump() off path sends CMD_OFF, NOT CMD_OIL_PUMP_OFF
    // (line 2507 of device.vue). For HeatGenie this is stop(); for
    // HCalory pump-off is also implicit-via-stop since HCalory has no
    // separate pump-prime concept.
    suspend fun oilPumpOff(): Boolean = sendStop()

    suspend fun sendRaw(bytes: ByteArray): Boolean =
        activeDriver?.sendRaw("", bytes)?.isSuccess == true

    // --- HeatGenie-specific extras -----------------------------------
    // Each of these requires the active driver to be HeatGenie. For
    // HCalory they're no-ops (returning false) — the UI shouldn't be
    // exposing them in that case because of capability gating.

    private val heatGenie: HeatGenieProtocol?
        get() = activeDriver as? HeatGenieProtocol

    suspend fun setRunMode(mode: FrameCodec.RunMode): Boolean =
        heatGenie?.setRunMode(mode) ?: false

    suspend fun setTempHysteresis(diff: Int, unit: FrameCodec.TempUnit = FrameCodec.TempUnit.Celsius): Boolean =
        heatGenie?.setTempHysteresis(diff, unit) ?: false

    suspend fun setClock(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean =
        heatGenie?.setClock(now) ?: false

    suspend fun startTelemetryStream(): Boolean = heatGenie?.startTelemetryStream() ?: false
    suspend fun stopTelemetryStream():  Boolean = heatGenie?.stopTelemetryStream()  ?: false
    suspend fun readRegInfo():          Boolean = heatGenie?.readRegInfo()          ?: false
    suspend fun readTimerInfo():        Boolean = heatGenie?.readTimerInfo()        ?: false

    suspend fun writeTimer(slots: List<FrameCodec.WriteTimerSlot>): Boolean =
        heatGenie?.writeTimer(slots) ?: false

    // --- ServiceLocator hook -----------------------------------------
    // BoundDeviceStore can't be a constructor dep because of init order
    // (ServiceLocator builds BleManager first, then the store). The
    // store is injected from ServiceLocator after both exist.
    @Volatile private var boundDevices: BoundDeviceStore? = null
    internal fun attachBoundDevices(store: BoundDeviceStore) { boundDevices = store }
}

// Small extension so the MutableStateFlow update boilerplate stays terse.
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
