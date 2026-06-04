package uk.co.twinscrollgridbalancer.tsgbheater.data.fuel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.BleManager
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.BoundDeviceStore

/**
 * Estimates fuel level by watching the live telemetry flow and
 * integrating consumption(gear) × time while the heater is actually
 * burning. Mirrors `windows/src/TsgbHeater/Services/FuelTracker.cs`.
 *
 * Alert thresholds (debounced — only raise on a worsening crossing):
 *   ≤ 1.00 L  → Warning
 *   ≤ 0.50 L  → Critical
 *   ≤ 0.25 L  → Shutdown — also fires BleManager.sendStop() so the
 *                heater goes through its normal cool-down cycle before
 *                the pump runs the tank dry.
 */
class FuelTracker(
    private val ble: BleManager,
    private val devices: BoundDeviceStore,
    private val store: FuelStore,
    // Pro gate. Fuel tracking (incl. the low-fuel auto-shutdown) is a Pro
    // feature, so when entitlement is absent we stop integrating telemetry.
    private val isProActive: StateFlow<Boolean>,
) {

    enum class AlertLevel { NONE, WARNING, CRITICAL, SHUTDOWN }

    data class FuelSnapshot(
        val mac: String,
        val currentLitres: Double,
        val tankLitres: Double,
        val consumptionLowLph: Double,
        val consumptionHighLph: Double,
        val alert: AlertLevel,
    ) {
        fun lphAtGear(gear: Int): Double =
            consumptionForGear(gear, consumptionLowLph, consumptionHighLph)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Current snapshot for the active MAC. UI observers map this into
    // their own state. Null when nothing's bound / no level recorded.
    private val _snapshot = MutableStateFlow<FuelSnapshot?>(null)
    val snapshot: StateFlow<FuelSnapshot?> = _snapshot.asStateFlow()

    // Fired ONCE per crossing of an alert threshold; level recovery
    // (via refill) silently resets the floor.
    private val _alerts = MutableSharedFlow<FuelSnapshot>(extraBufferCapacity = 8)
    val alerts: SharedFlow<FuelSnapshot> = _alerts.asSharedFlow()

    @Volatile private var lastTickAtMs: Long = 0L
    @Volatile private var lastAlertLevel: AlertLevel = AlertLevel.NONE
    @Volatile private var trackedMac: String? = null

    private var teleJob: Job? = null

    fun start() {
        if (teleJob?.isActive == true) return
        // Telemetry-driven: each frame is an opportunity to tick fuel
        // and re-evaluate alerts. The active MAC is taken from
        // BoundDevices.currentMac since BleManager doesn't expose it
        // directly — but a HeatGenie connection is always against the
        // current MAC, so they line up.
        teleJob = scope.launch {
            ble.telemetry.collect { t -> if (t != null) onTelemetry(t) }
        }
    }

    fun stop() {
        teleJob?.cancel(); teleJob = null
    }

    // --- Public API used by UI ----------------------------------

    suspend fun refresh(mac: String) {
        trackedMac = mac
        val snap = computeSnapshot(mac)
        _snapshot.value = snap
        lastAlertLevel = snap?.alert ?: AlertLevel.NONE
    }

    suspend fun refill(mac: String, litres: Double) {
        val device = devices.findByMac(mac) ?: return
        val existing = store.get(mac)?.currentLitres ?: 0.0
        val next = (existing + litres.coerceAtLeast(0.0)).coerceAtMost(device.tankLitres)
        store.setLevel(mac, next)
        refresh(mac)
    }

    suspend fun setLevel(mac: String, litres: Double) {
        val device = devices.findByMac(mac) ?: return
        store.setLevel(mac, litres.coerceIn(0.0, device.tankLitres))
        refresh(mac)
    }

    // --- Internals ----------------------------------------------

    private suspend fun onTelemetry(t: HeaterTelemetry) {
        // No Pro → don't track. (Free users never had fuel tracking, so this
        // is a feature gate, not a regression of any safety they relied on.)
        if (!isProActive.value) return
        val mac = ble.activeMac() ?: return
        if (mac != trackedMac) trackedMac = mac
        val device = devices.findByMac(mac) ?: return

        val nowMs = System.currentTimeMillis()
        val lastMs = if (lastTickAtMs == 0L) nowMs else lastTickAtMs
        lastTickAtMs = nowMs

        if (isBurningFuel(t.runningMode) && (t.aimGear ?: 0) > 0) {
            // Cap a single window at 5 min so a sleeping device doesn't
            // wipe the tank on resume.
            val elapsedHours = ((nowMs - lastMs) / 1000.0 / 3600.0).coerceIn(0.0, 5.0 / 60.0)
            val lph = consumptionForGear(t.aimGear!!, device.consumptionLowLph, device.consumptionHighLph)
            val used = lph * elapsedHours
            if (used > 0) store.adjust(mac, -used)
        }

        refresh(mac)
        evaluateAlerts(_snapshot.value)
    }

    private suspend fun computeSnapshot(mac: String): FuelSnapshot? {
        val device = devices.findByMac(mac) ?: return null
        val state = store.get(mac)
        val current = state?.currentLitres ?: device.tankLitres
        return FuelSnapshot(
            mac                 = mac,
            currentLitres       = current,
            tankLitres          = device.tankLitres,
            consumptionLowLph   = device.consumptionLowLph,
            consumptionHighLph  = device.consumptionHighLph,
            alert               = classify(current),
        )
    }

    private fun evaluateAlerts(snap: FuelSnapshot?) {
        if (snap == null) return
        val prev = lastAlertLevel
        if (snap.alert == prev) return

        // Only RAISE on a worsening crossing — refill silently resets.
        if (snap.alert.ordinal > prev.ordinal) {
            lastAlertLevel = snap.alert
            _alerts.tryEmit(snap)
            if (snap.alert == AlertLevel.SHUTDOWN) {
                // Fire-and-forget. Stop goes through the heater's normal
                // cool-down cycle which is the right shape for a
                // running-out-of-fuel shutdown.
                scope.launch { ble.sendStop() }
            }
        } else {
            // Level climbed (refill) — reset floor so the next worsening
            // crossing can re-raise.
            lastAlertLevel = snap.alert
        }
    }

    companion object {
        /** L/hour at gear N (1..10), linearly interpolated between low/high. */
        fun consumptionForGear(gear: Int, lowLph: Double, highLph: Double): Double {
            val g = gear.coerceIn(1, 10)
            return lowLph + (g - 1) * (highLph - lowLph) / 9.0
        }

        private fun isBurningFuel(mode: RunningMode): Boolean = when (mode) {
            RunningMode.Ignition, RunningMode.AutoRun,
            RunningMode.ManualRun, RunningMode.StartStopActive -> true
            else -> false
        }

        private fun classify(litres: Double): AlertLevel = when {
            litres <= 0.25 -> AlertLevel.SHUTDOWN
            litres <= 0.50 -> AlertLevel.CRITICAL
            litres <= 1.00 -> AlertLevel.WARNING
            else           -> AlertLevel.NONE
        }
    }
}
