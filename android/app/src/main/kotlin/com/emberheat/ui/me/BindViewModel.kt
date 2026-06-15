package com.emberheat.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.emberheat.data.store.BoundDevice
import com.emberheat.di.ServiceLocator
import com.emberheat.service.HeaterService

data class BindUiState(
    val devices: List<BoundDevice> = emptyList(),
    val currentMac: String? = null,
)

class BindViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServiceLocator.boundDevices

    val ui: StateFlow<BindUiState> =
        combine(store.all, store.currentMac) { list, mac -> BindUiState(list, mac) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BindUiState())

    fun setCurrent(mac: String) = viewModelScope.launch {
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
        // Drop the MAC from every group so a stale member never breaks a
        // broadcast later.
        ServiceLocator.groups.dropMacEverywhere(mac)
        if (wasCurrent) {
            HeaterService.disconnect(getApplication())
            store.setCurrent(null)
        }
    }
}
