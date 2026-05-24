package uk.co.twinscrollgridbalancer.tsgbheater.ui.ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.DiscoveredDevice
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDevice
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.service.HeaterService

class BleListViewModel(app: Application) : AndroidViewModel(app) {

    private val ble    = ServiceLocator.ble
    private val store  = ServiceLocator.boundDevices

    val devices:  StateFlow<List<DiscoveredDevice>> = ble.devices
    val scanning: StateFlow<Boolean>                = ble.scanning

    fun startScan() {
        if (!ble.isBluetoothEnabled()) return
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()

    // Persist the binding, mark it as current, and ask the foreground
    // service to open a GATT link. Returns immediately — the rest happens
    // async; the caller can observe BleManager.connectionState.
    fun bind(device: DiscoveredDevice) {
        viewModelScope.launch {
            val name = device.name?.takeIf { it.isNotBlank() } ?: "Heater ${device.mac.takeLast(5)}"
            store.add(
                BoundDevice(
                    mac = device.mac,
                    name = name,
                    lastSeenAtMs = System.currentTimeMillis(),
                )
            )
            store.setCurrent(device.mac)
            HeaterService.connect(getApplication(), device.mac)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ble.stopScan()
    }
}
