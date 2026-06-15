package com.emberheat.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.emberheat.di.ServiceLocator
import com.emberheat.remote.RemoteHeaterClient
import com.emberheat.remote.StatusResp

data class RemoteControlUi(
    val serverLabel:  String = "",
    val serverUrl:    String = "",
    val status:       StatusResp? = null,
    val polling:      Boolean = false,
    val lastError:    String? = null,
    val targetEdit:   Int     = 20,
)

class RemoteControlViewModel(app: Application, val serverId: String) : AndroidViewModel(app) {

    private val store = ServiceLocator.pairedServers
    private var client: RemoteHeaterClient? = null
    private var poller: Job? = null

    private val _ui = MutableStateFlow(RemoteControlUi())
    val ui: StateFlow<RemoteControlUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val s = store.all.first().firstOrNull { it.id == serverId }
                ?: return@launch
            client = RemoteHeaterClient(s)
            _ui.value = _ui.value.copy(serverLabel = s.label, serverUrl = s.baseUrl)
            startPolling()
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = viewModelScope.launch {
            _ui.value = _ui.value.copy(polling = true)
            while (true) {
                val c = client ?: return@launch
                val r = c.status()
                if (r.isSuccess) {
                    val st = r.getOrThrow()
                    _ui.value = _ui.value.copy(
                        status    = st,
                        lastError = null,
                        targetEdit = st.telemetry?.targetC?.toInt() ?: _ui.value.targetEdit,
                    )
                } else {
                    _ui.value = _ui.value.copy(
                        lastError = r.exceptionOrNull()?.message)
                }
                delay(2000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        poller?.cancel()
    }

    private fun fire(block: suspend (RemoteHeaterClient) -> Result<Unit>) = viewModelScope.launch {
        val c = client ?: return@launch
        val r = block(c)
        if (r.isFailure) _ui.value = _ui.value.copy(lastError = r.exceptionOrNull()?.message)
    }

    fun connect() = fire { it.connect() }
    fun start()   = fire { it.start() }
    fun stop()    = fire { it.stop() }
    fun vent()    = fire { it.vent() }
    fun nudgeTarget(delta: Int) {
        val next = (_ui.value.targetEdit + delta).coerceIn(10, 35)
        _ui.value = _ui.value.copy(targetEdit = next)
        fire { it.setTarget(next) }
    }
    fun setRunMode(mode: String) = fire { it.setRunMode(mode) }

    // --- Fuel (via API) ------------------------------------------

    fun refillFuel(litres: Double) = fire { it.refillFuel(litres) }

    fun updateFuelConfig(tank: Double?, lowLph: Double?, highLph: Double?) =
        fire { it.setFuelConfig(tank, lowLph, highLph) }
}
