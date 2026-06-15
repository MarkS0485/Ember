package com.emberheat.ui.ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.emberheat.ble.DiscoveredDevice
import com.emberheat.data.store.BoundDevice
import com.emberheat.di.ServiceLocator
import com.emberheat.protocol.ProtocolKind
import com.emberheat.service.HeaterService

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
            // If the scanner detected the protocol via service UUID, use
            // that. Otherwise default to HEATGENIE — the historical default
            // since every pre-multi-protocol pairing was a HeatGenie. User
            // can edit in the bind detail screen if mistaken.
            val protocol = device.protocol ?: ProtocolKind.HEATGENIE
            store.add(
                BoundDevice(
                    mac = device.mac,
                    name = name,
                    lastSeenAtMs = System.currentTimeMillis(),
                    protocol = protocol,
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
