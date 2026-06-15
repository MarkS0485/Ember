package com.emberheat.ui.fuel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emberheat.data.fuel.FuelTracker
import com.emberheat.di.ServiceLocator

/**
 * Backs [FuelScreen]. Resolves the heater whose fuel we're showing (the
 * persisted "current" MAC, falling back to the first bound device), keeps
 * [FuelTracker]'s snapshot pointed at it, and exposes the live telemetry
 * gear so the screen can show consumption / hours-remaining at the current
 * burn rate. Mirrors the Windows DeviceViewModel fuel block.
 */
data class FuelUiState(
    val mac: String? = null,
    val deviceName: String? = null,
    val snapshot: FuelTracker.FuelSnapshot? = null,
    // Live aim-gear from telemetry; midpoint (5) when the heater is idle so
    // the "hours remaining" estimate still shows a sensible figure.
    val gear: Int = 5,
)

class FuelViewModel(app: Application) : AndroidViewModel(app) {

    private val ble     = ServiceLocator.ble
    private val devices = ServiceLocator.boundDevices
    private val fuel    = ServiceLocator.fuelCtl

    // The heater we're tracking fuel for. Prefer the persisted current-MAC
    // pointer (survives a disconnect), else the first paired heater.
    private val effectiveMac: StateFlow<String?> =
        combine(devices.currentMac, devices.all) { cur, all ->
            cur ?: all.firstOrNull()?.mac
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ui: StateFlow<FuelUiState> = combine(
        effectiveMac,
        devices.all,
        fuel.snapshot,
        ble.telemetry,
    ) { mac, all, snap, tele ->
        val device = all.firstOrNull { it.mac == mac }
        // Only trust the tracker's snapshot if it's for the heater we're
        // showing — it's a single shared flow that follows the live link.
        val snapForMac = snap?.takeIf { it.mac.equals(mac, ignoreCase = true) }
        val gear = (tele?.aimGear ?: 0).takeIf { it > 0 } ?: 5
        FuelUiState(
            mac        = mac,
            deviceName = device?.name,
            snapshot   = snapForMac,
            gear       = gear,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FuelUiState())

    init {
        // Keep the tracker's snapshot pointed at the selected heater so the
        // screen has fresh figures even while the heater is idle (no
        // telemetry ticks to drive a recompute).
        viewModelScope.launch {
            effectiveMac.collect { mac -> if (mac != null) fuel.refresh(mac) }
        }
    }

    fun refill(litres: Double) = viewModelScope.launch {
        ui.value.mac?.let { fuel.refill(it, litres) }
    }

    fun setLevel(litres: Double) = viewModelScope.launch {
        ui.value.mac?.let { fuel.setLevel(it, litres) }
    }

    fun saveConfig(tankLitres: Double?, lowLph: Double?, highLph: Double?) = viewModelScope.launch {
        val mac = ui.value.mac ?: return@launch
        devices.updateFuelConfig(mac, tankLitres, lowLph, highLph)
        fuel.refresh(mac)
    }
}
