package com.emberheat.ui.device

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emberheat.ble.ConnectionState
import com.emberheat.ble.FrameCodec
import com.emberheat.ble.HeaterTelemetry
import com.emberheat.data.fuel.FuelTracker
import com.emberheat.data.store.BoundDevice
import com.emberheat.di.ServiceLocator
import com.emberheat.service.HeaterService

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

    private val ble          = ServiceLocator.ble
    private val store        = ServiceLocator.boundDevices
    private val settings     = ServiceLocator.settings
    private val fuelCtl      = ServiceLocator.fuelCtl
    private val entitlements = ServiceLocator.entitlements

    private val currentDevice: kotlinx.coroutines.flow.Flow<BoundDevice?> =
        combine(store.all, store.currentMac) { list, mac ->
            list.firstOrNull { it.mac == mac } ?: list.firstOrNull()
        }

    // Fuel tracking is a Pro feature, so the Device-screen card only
    // surfaces for Pro users. Free users discover it via the Pro menu.
    private val proGatedFuel: kotlinx.coroutines.flow.Flow<FuelTracker.FuelSnapshot?> =
        combine(fuelCtl.snapshot, entitlements.isProActive) { snap, pro -> if (pro) snap else null }

    val ui: StateFlow<DeviceUiState> = combine(
        ble.connectionState,
        ble.telemetry,
        currentDevice,
        settings.selectedRunMode,
        proGatedFuel,
    ) { state, telemetry, current, modeWire, fuel ->
        DeviceUiState(state, telemetry, current, wireToMode(modeWire), fuel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    init {
        // Keep the fuel snapshot populated for the bound heater even when
        // it's idle/disconnected (no telemetry ticks to drive a recompute),
        // so the Device-page fuel card — and its settings cog — are always
        // reachable, not just mid-burn.
        viewModelScope.launch {
            currentDevice
                .map { it?.mac }
                .distinctUntilChanged()
                .collect { mac -> if (mac != null) fuelCtl.refresh(mac) }
        }
    }

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
