package uk.co.twinscrollgridbalancer.tsgbheater.ui.device

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.data.fuel.FuelTracker
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDevice
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.service.HeaterService

data class DeviceUiState(
    val connectionState: ConnectionState     = ConnectionState.Idle,
    val telemetry:       HeaterTelemetry?    = null,
    val current:         BoundDevice?        = null,
    // User-selected run mode (Auto / Manual / Start-Stop). Drives whether
    // the Device screen shows the target stepper or the gear segmented.
    // Persisted via AppSettingsStore so it survives restart.
    val selectedMode:    FrameCodec.RunMode  = FrameCodec.RunMode.Auto,
    // Fuel-tracking snapshot. Null when no device is currently bound or
    // no telemetry has arrived yet — UI hides the card in that case.
    val fuel:            FuelTracker.FuelSnapshot? = null,
)

class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    private val ble      = ServiceLocator.ble
    private val store    = ServiceLocator.boundDevices
    private val settings = ServiceLocator.settings
    private val fuelCtl  = ServiceLocator.fuelCtl

    private val currentDevice: kotlinx.coroutines.flow.Flow<BoundDevice?> =
        combine(store.all, store.currentMac) { list, mac ->
            list.firstOrNull { it.mac == mac } ?: list.firstOrNull()
        }

    val ui: StateFlow<DeviceUiState> = combine(
        ble.connectionState,
        ble.telemetry,
        currentDevice,
        settings.selectedRunMode,
        fuelCtl.snapshot,
    ) { state, telemetry, current, modeWire, fuel ->
        DeviceUiState(state, telemetry, current, wireToMode(modeWire), fuel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    fun reconnect() {
        viewModelScope.launch {
            val mac = ui.value.current?.mac ?: return@launch
            HeaterService.connect(getApplication(), mac)
        }
    }

    fun disconnect() = HeaterService.disconnect(getApplication())

    fun start()     = viewModelScope.launch { ble.sendStart() }
    fun stop()      = viewModelScope.launch { ble.sendStop()  }
    fun ventilate() = viewModelScope.launch { ble.blowOn()    }

    // Switches the selected mode locally AND sends setRunMode to the
    // heater so the next telemetry frame reflects the choice. Local
    // persistence lets the conditional control survive a process restart
    // even before telemetry comes back.
    fun selectRunMode(mode: FrameCodec.RunMode) = viewModelScope.launch {
        settings.setSelectedRunMode(mode.wire)
        ble.setRunMode(mode)
    }

    fun setGear(g: Int) = viewModelScope.launch {
        ble.setGear(g.coerceIn(GEAR_MIN, GEAR_MAX))
    }

    fun nudgeTargetTemp(delta: Int) = viewModelScope.launch {
        val current = ui.value.telemetry?.targetTempC?.toInt() ?: DEFAULT_TARGET_C
        val next = (current + delta).coerceIn(TARGET_MIN_C, TARGET_MAX_C)
        ble.setTargetTemp(next)
    }

    // --- Fuel ---------------------------------------------------

    fun refillFuel(litres: Double) = viewModelScope.launch {
        val mac = ui.value.current?.mac ?: return@launch
        fuelCtl.refill(mac, litres)
    }

    fun setFuelLevel(litres: Double) = viewModelScope.launch {
        val mac = ui.value.current?.mac ?: return@launch
        fuelCtl.setLevel(mac, litres)
    }

    fun updateFuelConfig(tank: Double?, lowLph: Double?, highLph: Double?) =
        viewModelScope.launch {
            val mac = ui.value.current?.mac ?: return@launch
            store.updateFuelConfig(mac, tank, lowLph, highLph)
            // Re-evaluate snapshot now that consumption math may have changed.
            fuelCtl.refresh(mac)
        }

    private fun wireToMode(w: Int): FrameCodec.RunMode = when (w) {
        1    -> FrameCodec.RunMode.Manual
        2    -> FrameCodec.RunMode.StartStop
        else -> FrameCodec.RunMode.Auto
    }

    companion object {
        const val TARGET_MIN_C     = 10
        const val TARGET_MAX_C     = 35
        const val DEFAULT_TARGET_C = 20
        const val GEAR_MIN         = 1
        const val GEAR_MAX         = 10
    }
}
