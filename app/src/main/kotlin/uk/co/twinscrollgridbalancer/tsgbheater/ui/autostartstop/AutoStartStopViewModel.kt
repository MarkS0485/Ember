package uk.co.twinscrollgridbalancer.tsgbheater.ui.autostartstop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.data.auto.AutoStartStopController
import uk.co.twinscrollgridbalancer.tsgbheater.data.auto.RuleSignal
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AutoRules
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDevice
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator

data class AutoStartStopUiState(
    val rules:     AutoRules                            = AutoRules(),
    val perDevice: List<BoundDevice>                    = emptyList(),
    val signal:    RuleSignal                           = RuleSignal.NotApplicable,
    val history:   List<AutoStartStopController.DecisionLog> = emptyList(),
)

class AutoStartStopViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settings
    private val store    = ServiceLocator.boundDevices
    private val auto     = ServiceLocator.auto

    val ui: StateFlow<AutoStartStopUiState> = combine(
        settings.autoRules,
        store.all,
        auto.lastSignal,
        auto.history,
    ) { rules, list, signal, history -> AutoStartStopUiState(rules, list, signal, history) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoStartStopUiState())

    fun setMaster(v: Boolean)            = viewModelScope.launch { settings.setAutoStartStopMasterEnabled(v) }
    fun setSetpoint(c: Int)              = viewModelScope.launch { settings.setSetpointC(c) }
    fun setMargin(c: Int)                = viewModelScope.launch { settings.setMarginC(c) }
    fun setAmbientStart(c: Int)          = viewModelScope.launch { settings.setAmbientStartC(c) }
    fun setAmbientStop(c: Int)           = viewModelScope.launch { settings.setAmbientStopC(c) }
    fun setBatteryCutoff(v: Double)      = viewModelScope.launch { settings.setBatteryCutoffV(v) }
    fun setBatteryCutoffEnabled(v: Boolean) = viewModelScope.launch { settings.setBatteryCutoffEnabled(v) }
    fun setCooldown(s: Int)              = viewModelScope.launch { settings.setCooldownSec(s) }

    fun setPerDevice(mac: String, enabled: Boolean) = viewModelScope.launch {
        store.setAutoStartStop(mac, enabled)
    }
}
