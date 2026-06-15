package com.emberheat.data.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

// Snapshot of every tunable that the Auto Start/Stop rules engine reads
// on each tick. All temperatures are in °C, voltages in V, durations in s.
// Defaults are sane for a UK van / small cabin install on a 12 V battery.
data class AutoRules(
    val masterEnabled:   Boolean = false,
    val setpointC:       Int     = 19,
    val marginC:         Int     = 2,
    val ambientStartC:   Int     = 5,    // hard cold floor
    val ambientStopC:    Int     = 25,   // hard warm ceiling
    val batteryCutoffV:  Double  = 11.0,
    val batteryCutoffEnabled: Boolean = true,
    val cooldownSec:     Int     = 300,  // 5 min between actions
    val staleTelemetrySec: Int   = 10,   // hold if no telemetry for this long
)

class AppSettingsStore(private val ctx: Context) {

    // Existing top-level flags ---------------------------------------

    val autoStartStopMasterEnabled: Flow<Boolean> =
        ctx.appSettingsDataStore.data.map { it[KEY_AUTO_START_STOP] ?: false }

    val keepAlivePref: Flow<Boolean> =
        ctx.appSettingsDataStore.data.map { it[KEY_KEEP_ALIVE] ?: true }

    // App-driven rich scheduler. When enabled, ScheduleController owns the
    // heater's per-day timer slots and rotates them through richer events
    // than the heater's native 1-per-day table can hold.
    val scheduleModeEnabled: Flow<Boolean> =
        ctx.appSettingsDataStore.data.map { it[KEY_SCHEDULE_MODE] ?: false }

    suspend fun setAutoStartStopMasterEnabled(v: Boolean) {
        ctx.appSettingsDataStore.edit { it[KEY_AUTO_START_STOP] = v }
    }

    suspend fun setKeepAlive(v: Boolean) {
        ctx.appSettingsDataStore.edit { it[KEY_KEEP_ALIVE] = v }
    }

    suspend fun setScheduleModeEnabled(v: Boolean) {
        ctx.appSettingsDataStore.edit { it[KEY_SCHEDULE_MODE] = v }
    }

    // Rule config ----------------------------------------------------

    val autoRules: Flow<AutoRules> = ctx.appSettingsDataStore.data.map { p ->
        val defaults = AutoRules()
        AutoRules(
            masterEnabled         = p[KEY_AUTO_START_STOP]      ?: defaults.masterEnabled,
            setpointC             = p[KEY_RULE_SETPOINT]        ?: defaults.setpointC,
            marginC               = p[KEY_RULE_MARGIN]          ?: defaults.marginC,
            ambientStartC         = p[KEY_RULE_AMB_START]       ?: defaults.ambientStartC,
            ambientStopC          = p[KEY_RULE_AMB_STOP]        ?: defaults.ambientStopC,
            batteryCutoffV        = p[KEY_RULE_BATT_CUTOFF_V]   ?: defaults.batteryCutoffV,
            batteryCutoffEnabled  = p[KEY_RULE_BATT_CUTOFF_ON]  ?: defaults.batteryCutoffEnabled,
            cooldownSec           = p[KEY_RULE_COOLDOWN_S]      ?: defaults.cooldownSec,
            staleTelemetrySec     = p[KEY_RULE_STALE_S]         ?: defaults.staleTelemetrySec,
        )
    }

    suspend fun setSetpointC(v: Int)         = ctx.appSettingsDataStore.edit { it[KEY_RULE_SETPOINT]      = v }
    suspend fun setMarginC(v: Int)           = ctx.appSettingsDataStore.edit { it[KEY_RULE_MARGIN]        = v }
    suspend fun setAmbientStartC(v: Int)     = ctx.appSettingsDataStore.edit { it[KEY_RULE_AMB_START]     = v }
    suspend fun setAmbientStopC(v: Int)      = ctx.appSettingsDataStore.edit { it[KEY_RULE_AMB_STOP]      = v }
    suspend fun setBatteryCutoffV(v: Double) = ctx.appSettingsDataStore.edit { it[KEY_RULE_BATT_CUTOFF_V] = v }
    suspend fun setBatteryCutoffEnabled(v: Boolean) = ctx.appSettingsDataStore.edit { it[KEY_RULE_BATT_CUTOFF_ON] = v }
    suspend fun setCooldownSec(v: Int)       = ctx.appSettingsDataStore.edit { it[KEY_RULE_COOLDOWN_S]    = v }
    suspend fun setStaleTelemetrySec(v: Int) = ctx.appSettingsDataStore.edit { it[KEY_RULE_STALE_S]       = v }

    // Persisted preference for the run-mode the user has selected on the
    // Device screen. Drives whether the screen shows the target stepper
    // (Auto / Start-Stop) or the gear segmented control (Manual). Stored
    // as the FrameCodec.RunMode wire value (0/1/2). Default = Auto.
    val selectedRunMode: Flow<Int> =
        ctx.appSettingsDataStore.data.map { it[KEY_SELECTED_RUN_MODE] ?: 0 }

    suspend fun setSelectedRunMode(wire: Int) {
        ctx.appSettingsDataStore.edit { it[KEY_SELECTED_RUN_MODE] = wire }
    }

    // Hidden developer mode. Off by default; unlocked by tapping the version
    // on the About screen. Gates the diagnostic tooling (raw-frame log, test
    // commands, altitude probe, heater test mode) so a normal install never
    // sees it, but it stays one gesture away for field debugging.
    val developerMode: Flow<Boolean> =
        ctx.appSettingsDataStore.data.map { it[KEY_DEVELOPER_MODE] ?: false }

    suspend fun setDeveloperMode(v: Boolean) {
        ctx.appSettingsDataStore.edit { it[KEY_DEVELOPER_MODE] = v }
    }

    private companion object {
        val KEY_AUTO_START_STOP: Preferences.Key<Boolean> = booleanPreferencesKey("auto_start_stop")
        val KEY_KEEP_ALIVE:     Preferences.Key<Boolean>  = booleanPreferencesKey("keep_alive")
        val KEY_SCHEDULE_MODE:  Preferences.Key<Boolean>  = booleanPreferencesKey("schedule_mode")

        val KEY_RULE_SETPOINT:      Preferences.Key<Int>     = intPreferencesKey("rule_setpoint_c")
        val KEY_RULE_MARGIN:        Preferences.Key<Int>     = intPreferencesKey("rule_margin_c")
        val KEY_RULE_AMB_START:     Preferences.Key<Int>     = intPreferencesKey("rule_amb_start_c")
        val KEY_RULE_AMB_STOP:      Preferences.Key<Int>     = intPreferencesKey("rule_amb_stop_c")
        val KEY_RULE_BATT_CUTOFF_V: Preferences.Key<Double>  = doublePreferencesKey("rule_batt_cutoff_v")
        val KEY_RULE_BATT_CUTOFF_ON:Preferences.Key<Boolean> = booleanPreferencesKey("rule_batt_cutoff_on")
        val KEY_RULE_COOLDOWN_S:    Preferences.Key<Int>     = intPreferencesKey("rule_cooldown_s")
        val KEY_RULE_STALE_S:       Preferences.Key<Int>     = intPreferencesKey("rule_stale_s")
        val KEY_SELECTED_RUN_MODE:  Preferences.Key<Int>     = intPreferencesKey("selected_run_mode")
        val KEY_DEVELOPER_MODE:     Preferences.Key<Boolean> = booleanPreferencesKey("developer_mode")
    }
}
