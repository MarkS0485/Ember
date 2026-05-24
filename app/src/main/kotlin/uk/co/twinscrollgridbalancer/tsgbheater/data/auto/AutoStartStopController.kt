package uk.co.twinscrollgridbalancer.tsgbheater.data.auto

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.BleManager
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppSettingsStore
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDevice
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDeviceStore

// One running pass over [BleManager.telemetry] → [RulesEngine] → BLE
// commands. Lives in ServiceLocator so it's hot from app start; the
// HeaterService just keeps the process alive in the background so the
// flows here keep collecting after the activity closes.
//
// History of recent decisions is buffered so the UI can show what the
// engine is doing without having to be on-screen for the firing moment.
class AutoStartStopController(
    private val ble: BleManager,
    private val settings: AppSettingsStore,
    private val boundDevices: BoundDeviceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lastSignal = MutableStateFlow<RuleSignal>(RuleSignal.NotApplicable)
    val lastSignal: StateFlow<RuleSignal> = _lastSignal.asStateFlow()

    private val _history = MutableStateFlow<List<DecisionLog>>(emptyList())
    val history: StateFlow<List<DecisionLog>> = _history.asStateFlow()

    @Volatile private var lastActionAtMs: Long? = null

    data class DecisionLog(
        val atMs:   Long,
        val signal: String,
        val reason: String,
    )

    fun start() {
        combine(
            ble.telemetry,
            ble.connectionState,
            settings.autoRules,
            currentBoundDevice(),
        ) { tele, state, rules, dev -> Tick(tele, state, rules, dev) }
            .distinctUntilChanged()
            .onEach(::handleTick)
            .launchIn(scope)
    }

    private fun currentBoundDevice() = combine(
        boundDevices.all,
        boundDevices.currentMac,
    ) { list, mac -> list.firstOrNull { it.mac == mac } ?: list.firstOrNull() }

    private data class Tick(
        val telemetry: uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry?,
        val connection: ConnectionState,
        val rules: uk.co.twinscrollgridbalancer.tsgbheater.data.store.AutoRules,
        val device: BoundDevice?,
    )

    private suspend fun handleTick(t: Tick) {
        // Only act when we're actually connected — sending writes off-link
        // is a no-op but pollutes logs.
        if (t.connection != ConnectionState.Ready) {
            _lastSignal.value = RuleSignal.Hold("not connected")
            return
        }

        val signal = RulesEngine.evaluate(
            rules         = t.rules,
            deviceEnabled = t.device?.autoStartStopEnabled == true,
            telemetry     = t.telemetry,
            lastActionAtMs = lastActionAtMs,
            nowMs         = System.currentTimeMillis(),
        )
        _lastSignal.value = signal

        when (signal) {
            is RuleSignal.Start -> fire(signal) { ble.sendStart(); "START" }
            is RuleSignal.Stop  -> fire(signal) { ble.sendStop();  "STOP" }
            else -> { /* nothing to do for Hold / NotApplicable */ }
        }
    }

    private suspend fun fire(signal: RuleSignal, send: suspend () -> String) {
        val label = send()
        lastActionAtMs = System.currentTimeMillis()
        Log.i(TAG, "Auto-fired $label · ${signal.reason}")
        appendHistory(label, signal.reason)
    }

    private fun appendHistory(signal: String, reason: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            val entry = DecisionLog(now, signal, reason)
            val next = (listOf(entry) + _history.value).take(MAX_HISTORY)
            _history.value = next
        }
    }

    private companion object {
        const val TAG = "AutoController"
        const val MAX_HISTORY = 50
    }
}
