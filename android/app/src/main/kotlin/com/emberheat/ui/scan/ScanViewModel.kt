package com.emberheat.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emberheat.ble.ConnectionState
import com.emberheat.data.store.BoundDevice
import com.emberheat.di.ServiceLocator
import com.emberheat.service.HeaterService

data class ScanUiState(
    val bound:           List<BoundDevice>  = emptyList(),
    val currentMac:      String?            = null,
    val connectionState: ConnectionState    = ConnectionState.Idle,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServiceLocator.boundDevices
    private val ble   = ServiceLocator.ble

    val ui: StateFlow<ScanUiState> = combine(
        store.all,
        store.currentMac,
        ble.connectionState,
    ) { list, mac, state -> ScanUiState(list, mac, state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    fun reconnect(mac: String) = viewModelScope.launch {
        store.setCurrent(mac)
        HeaterService.connect(getApplication(), mac)
    }

    fun rename(mac: String, newName: String) = viewModelScope.launch {
        if (newName.isBlank()) return@launch
        store.rename(mac, newName.trim())
    }

    fun unbind(mac: String) = viewModelScope.launch {
        val wasCurrent = ui.value.currentMac == mac
        store.remove(mac)
        ServiceLocator.groups.dropMacEverywhere(mac)
        if (wasCurrent) {
            HeaterService.disconnect(getApplication())
            store.setCurrent(null)
        }
    }
}
