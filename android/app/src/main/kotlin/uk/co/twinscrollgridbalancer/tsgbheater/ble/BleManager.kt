package uk.co.twinscrollgridbalancer.tsgbheater.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Single source of truth for the BLE side. ViewModels observe its flows;
// HeaterService keeps a reference so the connection survives the activity.
// Created once at app start and held by ServiceLocator.
class BleManager(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scanner = BleScanner(ctx)
    private val connection = HeaterConnection(ctx)

    // --- Public flows --------------------------------------------------

    val connectionState: StateFlow<ConnectionState> = connection.state
    val telemetry:       StateFlow<HeaterTelemetry?> = connection.telemetry
    val frames:          SharedFlow<RawFrame>        = connection.frames

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // --- Scanning ------------------------------------------------------

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

    // Merge a new advert into the discovered-devices list. Order is stable
    // (first-seen wins) so the row a user is reaching for doesn't jump
    // around as RSSI fluctuates — only the displayed dB number changes.
    private fun mergeDevice(hit: DiscoveredDevice) {
        _devices.update { list ->
            val idx = list.indexOfFirst { it.mac == hit.mac }
            if (idx >= 0) {
                val merged = list[idx].copy(
                    rssi          = hit.rssi,
                    name          = hit.name ?: list[idx].name,
                    isKnownHeater = list[idx].isKnownHeater || hit.isKnownHeater,
                    lastSeenAtMs  = hit.lastSeenAtMs,
                )
                list.toMutableList().also { it[idx] = merged }
            } else {
                list + hit
            }
        }
    }

    // --- Connection ----------------------------------------------------

    fun connect(mac: String) {
        stopScan()
        connection.connect(mac)
    }

    fun disconnect() {
        connection.disconnect()
    }

    // --- Commands ------------------------------------------------------

    suspend fun sendStart(): Boolean = connection.write(FrameCodec.buildStartHeater())
    suspend fun sendStop():  Boolean = connection.write(FrameCodec.buildStopHeater())

    suspend fun setTargetTemp(value: Int, unit: FrameCodec.TempUnit = FrameCodec.TempUnit.Celsius): Boolean =
        connection.write(FrameCodec.buildSetTargetTemp(value, unit))

    suspend fun setGear(gear: Int): Boolean =
        connection.write(FrameCodec.buildSetGear(gear))

    suspend fun setRunMode(mode: FrameCodec.RunMode): Boolean =
        connection.write(FrameCodec.buildSetRunMode(mode))

    suspend fun setTempHysteresis(diff: Int, unit: FrameCodec.TempUnit = FrameCodec.TempUnit.Celsius): Boolean =
        connection.write(FrameCodec.buildSetTempHysteresis(diff, unit))

    suspend fun blowOn():     Boolean = connection.write(FrameCodec.buildBlowOn())

    // The vendor app's manual-prime path sends DB0_DN_MANU_PUMP with a
    // seconds value, not CMD_OIL_PUMP_ON; without the duration the
    // controller ignores the request and the pump never spins. The heater
    // also only accepts this frame in runningMode 5 (Standby) or 7
    // (ManualPump) — see HeaterService.runPumpCommand for the gate.
    suspend fun oilPumpOn(seconds: Int = FrameCodec.MANUAL_PUMP_DEFAULT_S): Boolean =
        connection.write(FrameCodec.buildManualPumpRun(seconds))

    // Vendor's iolPump() off path sends CMD_OFF, NOT CMD_OIL_PUMP_OFF
    // (line 2507 of device.vue). CMD_OIL_PUMP_OFF (12) is left in the
    // FrameCodec for completeness but doesn't actually stop the timed
    // pump on this controller. See docs/BLE_PROTOCOL.md "Manual pump run".
    suspend fun oilPumpOff(): Boolean = connection.write(FrameCodec.buildStopHeater())

    // Push the phone's current day-of-week/hour/minute to the heater so
    // its schedule fires at the right time. Mirrors what the vendor app
    // does ~2 s after connection becomes Ready.
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

    // Push the full 7-day schedule table to the heater. Slots without an
    // entry for a given day default to mode=0 (disabled). Single shot is
    // modeRaw=1; daily/recurring is modeRaw=4.
    suspend fun writeTimer(slots: List<FrameCodec.WriteTimerSlot>): Boolean =
        connection.write(FrameCodec.buildWriteTimerArea(slots))

    suspend fun sendRaw(bytes: ByteArray): Boolean = connection.write(bytes)
}

// Small extension so the MutableStateFlow update boilerplate stays terse.
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
