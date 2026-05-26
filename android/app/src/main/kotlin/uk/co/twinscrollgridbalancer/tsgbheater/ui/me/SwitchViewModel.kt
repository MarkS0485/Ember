package uk.co.twinscrollgridbalancer.tsgbheater.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator

data class SwitchUiState(
    val telemetry:        HeaterTelemetry? = null,
    val keepAlive:        Boolean          = true,
    val autoStartStop:    Boolean          = false,
)

class SwitchViewModel(app: Application) : AndroidViewModel(app) {

    private val ble      = ServiceLocator.ble
    private val settings = ServiceLocator.settings

    val ui: StateFlow<SwitchUiState> = combine(
        ble.telemetry,
        settings.keepAlivePref,
        settings.autoStartStopMasterEnabled,
    ) { t, k, a -> SwitchUiState(t, k, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SwitchUiState())

    fun setRunMode(mode: FrameCodec.RunMode) = viewModelScope.launch {
        ble.setRunMode(mode)
    }

    fun setTempUnit(useFahrenheit: Boolean) = viewModelScope.launch {
        val payload = if (useFahrenheit) FrameCodec.buildSwitchToFahrenheit()
                      else               FrameCodec.buildSwitchToCelsius()
        ble.sendRaw(payload)
    }

    fun setKeepAlive(v: Boolean) = viewModelScope.launch { settings.setKeepAlive(v) }
    fun setAutoStartStop(v: Boolean) = viewModelScope.launch {
        settings.setAutoStartStopMasterEnabled(v)
    }
}
