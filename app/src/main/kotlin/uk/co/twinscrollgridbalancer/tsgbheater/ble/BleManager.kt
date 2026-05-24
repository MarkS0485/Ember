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
            }.sortedByDescending { it.rssi }
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
    suspend fun oilPumpOn():  Boolean = connection.write(FrameCodec.buildOilPumpOn())
    suspend fun oilPumpOff(): Boolean = connection.write(FrameCodec.buildOilPumpOff())

    suspend fun startTelemetryStream(): Boolean = connection.write(FrameCodec.buildStartTelemetryStream())
    suspend fun stopTelemetryStream():  Boolean = connection.write(FrameCodec.buildStopTelemetryStream())
    suspend fun readRegInfo():          Boolean = connection.write(FrameCodec.buildReadRegInfo())
    suspend fun readTimerInfo():        Boolean = connection.write(FrameCodec.buildReadTimerInfo())

    suspend fun sendRaw(bytes: ByteArray): Boolean = connection.write(bytes)
}

// Small extension so the MutableStateFlow update boilerplate stays terse.
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
