package com.emberheat.data.auto

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
import com.emberheat.ble.BleManager
import com.emberheat.ble.ConnectionState
import com.emberheat.data.store.AppSettingsStore
import com.emberheat.data.store.BoundDevice
import com.emberheat.data.store.BoundDeviceStore

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
    // Pro gate. While false (free tier, or a lapsed trial) the engine
    // observes telemetry but never issues Start/Stop — it simply holds.
    private val isProActive: StateFlow<Boolean>,
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
            isProActive,
        ) { tele, state, rules, dev, pro -> Tick(tele, state, rules, dev, pro) }
            .distinctUntilChanged()
            .onEach(::handleTick)
            .launchIn(scope)
    }

    private fun currentBoundDevice() = combine(
        boundDevices.all,
        boundDevices.currentMac,
    ) { list, mac -> list.firstOrNull { it.mac == mac } ?: list.firstOrNull() }

    private data class Tick(
        val telemetry: com.emberheat.ble.HeaterTelemetry?,
        val connection: ConnectionState,
        val rules: com.emberheat.data.store.AutoRules,
        val device: BoundDevice?,
        val pro: Boolean,
    )

    private suspend fun handleTick(t: Tick) {
        // Pro gate. Without entitlement the engine never drives the heater.
        // We hold (rather than NotApplicable) so the UI can explain why.
        if (!t.pro) {
            _lastSignal.value = RuleSignal.Hold("Pro required — unlock to resume auto start/stop")
            return
        }

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
