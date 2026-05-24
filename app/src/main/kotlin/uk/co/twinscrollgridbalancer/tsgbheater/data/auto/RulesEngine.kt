package uk.co.twinscrollgridbalancer.tsgbheater.data.auto

import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AutoRules

// The signal the engine emits on every tick. `Hold` is the most common
// outcome — most ticks land in the deadband or already match the desired
// state. The `reason` string is shown to the user on the Auto Start/Stop
// screen so they can see why nothing has fired.
sealed class RuleSignal(val reason: String) {
    object NotApplicable : RuleSignal("auto-mode off")
    class Hold(reason: String) : RuleSignal(reason)
    class Start(reason: String): RuleSignal(reason)
    class Stop(reason: String) : RuleSignal(reason)
}

// Pure-function rule evaluator. Inputs are immutable snapshots so unit
// tests can drive every branch without spinning up Flows. Used by the
// AutoStartStopController which feeds it telemetry on each frame.
object RulesEngine {

    // RunningMode values that mean "flame is alive" — combustion happening
    // or about to. We treat any of these as "heater is on" for the
    // already-in-desired-state short-circuit.
    private val RUNNING_MODES = setOf(
        RunningMode.Ignition,
        RunningMode.AutoRun,
        RunningMode.ManualRun,
        RunningMode.StartStopActive,
    )

    fun evaluate(
        rules: AutoRules,
        deviceEnabled: Boolean,
        telemetry: HeaterTelemetry?,
        lastActionAtMs: Long?,
        nowMs: Long,
    ): RuleSignal {
        if (!rules.masterEnabled || !deviceEnabled) return RuleSignal.NotApplicable

        if (telemetry == null) return RuleSignal.Hold("no telemetry yet")

        // Stale-telemetry guard. If the last frame arrived more than the
        // configured window ago, don't trust any reading — hold until we
        // have a fresh sample.
        val ageMs = nowMs - telemetry.updatedAtMs
        if (ageMs > rules.staleTelemetrySec * 1000L) {
            return RuleSignal.Hold("stale telemetry (${ageMs / 1000}s old)")
        }

        // Safety: battery cutoff overrides every other rule.
        val voltage = telemetry.batteryV
        if (rules.batteryCutoffEnabled && voltage != null && voltage < rules.batteryCutoffV) {
            return RuleSignal.Stop(
                "battery %.1f V < cutoff %.1f V".format(voltage, rules.batteryCutoffV)
            )
        }

        val ambient = telemetry.ambientTempC
            ?: return RuleSignal.Hold("no ambient reading")

        val currentlyRunning = telemetry.runningMode in RUNNING_MODES

        // Decide what we WANT to do. Outer envelope (ambientStart/Stop) wins
        // over inner thermostat — Stop always wins overall.
        val desired: Pair<RuleSignal, Boolean> = when {
            ambient > rules.ambientStopC ->
                RuleSignal.Stop(
                    "ambient %.1f°C above warm ceiling %d°C".format(ambient, rules.ambientStopC)
                ) to false   // want stopped
            ambient < rules.ambientStartC ->
                RuleSignal.Start(
                    "ambient %.1f°C below cold floor %d°C".format(ambient, rules.ambientStartC)
                ) to true    // want running
            ambient > rules.setpointC + rules.marginC ->
                RuleSignal.Stop(
                    "ambient %.1f°C above setpoint %d°C + margin %d°C"
                        .format(ambient, rules.setpointC, rules.marginC)
                ) to false
            ambient < rules.setpointC - rules.marginC ->
                RuleSignal.Start(
                    "ambient %.1f°C below setpoint %d°C − margin %d°C"
                        .format(ambient, rules.setpointC, rules.marginC)
                ) to true
            else ->
                RuleSignal.Hold(
                    "ambient %.1f°C inside deadband (%d°C ± %d°C)"
                        .format(ambient, rules.setpointC, rules.marginC)
                ) to currentlyRunning   // hold whatever state we're in
        }

        val (signal, wantRunning) = desired

        // No-op short-circuit: if already in the desired state, hold.
        if (signal is RuleSignal.Start && currentlyRunning) {
            return RuleSignal.Hold(signal.reason + " · already running")
        }
        if (signal is RuleSignal.Stop && !currentlyRunning) {
            return RuleSignal.Hold(signal.reason + " · already stopped")
        }
        if (signal is RuleSignal.Hold) return signal

        // Cooldown gate — if we acted recently, hold off and let the system settle.
        if (lastActionAtMs != null) {
            val sinceMs = nowMs - lastActionAtMs
            val needMs  = rules.cooldownSec * 1000L
            if (sinceMs < needMs) {
                val remain = ((needMs - sinceMs) / 1000).toInt()
                return RuleSignal.Hold("cooldown — ${remain}s remaining")
            }
        }

        // Reference `wantRunning` here so the linter doesn't flag it as
        // unused; the controller will read the signal type directly.
        @Suppress("UNUSED_VARIABLE") val _wr = wantRunning
        return signal
    }
}
