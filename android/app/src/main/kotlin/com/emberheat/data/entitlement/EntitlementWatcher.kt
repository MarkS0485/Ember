package com.emberheat.data.entitlement

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.emberheat.data.store.AppSettingsStore
import com.emberheat.data.store.BoundDeviceStore
import com.emberheat.service.HeaterNotification

/**
 * Safety net for the automation "expiry cliff": when Pro stops being active
 * (typically a free trial running out) the schedule + auto-start/stop engines
 * silently stop managing the heater. That's the correct behaviour, but a user
 * who'd come to rely on it shouldn't find out by the cabin going cold.
 *
 * This watcher observes [EntitlementRepository.isProActive] for a true→false
 * transition and, *only if* an automation feature was actually in use, posts a
 * heads-up notification so the change is visible.
 *
 * Detection is in-memory (no persisted "was pro" flag). That's sufficient
 * because the automation features only run while the foreground service keeps
 * the process alive — and while it's alive, the entitlement repository's 60s
 * ticker flips this flow the moment a trial lapses, so the transition is seen.
 */
class EntitlementWatcher(
    private val appContext: Context,
    private val entitlements: EntitlementRepository,
    private val settings: AppSettingsStore,
    private val boundDevices: BoundDeviceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun start() {
        scope.launch {
            // Seed from the first emission without firing, so the normal
            // startup transition (false → true for a Pro/legacy user) is
            // never mistaken for a lapse.
            var wasPro: Boolean? = null
            entitlements.isProActive.collect { pro ->
                val prev = wasPro
                wasPro = pro
                if (prev == true && !pro) onProLapsed()
            }
        }
    }

    private suspend fun onProLapsed() {
        val scheduleOn = settings.scheduleModeEnabled.first()
        val autoOn = settings.autoStartStopMasterEnabled.first() &&
            boundDevices.all.first().any { it.autoStartStopEnabled }

        if (!scheduleOn && !autoOn) return   // nothing was being managed

        val features = buildList {
            if (scheduleOn) add("scheduled heating")
            if (autoOn) add("Auto Start / Stop")
        }.joinToString(" and ")

        HeaterNotification.notifyProLapsed(appContext, features)
    }
}
