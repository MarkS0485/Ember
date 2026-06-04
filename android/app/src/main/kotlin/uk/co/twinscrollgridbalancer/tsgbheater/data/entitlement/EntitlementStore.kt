package uk.co.twinscrollgridbalancer.tsgbheater.data.entitlement

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.entitlementDataStore by preferencesDataStore(name = "entitlement")

/**
 * Persists only the bits of entitlement that DON'T come from Play: the legacy
 * (grandfather) grant and the self-managed trial start. Actual purchase/sub
 * ownership is always read live from Play via BillingManager so refunds and
 * cancellations are honoured automatically.
 */
class EntitlementStore(private val ctx: Context) {

    val legacyPro: Flow<Boolean> =
        ctx.entitlementDataStore.data.map { it[KEY_LEGACY] ?: false }

    /** Epoch millis the trial was started, or 0 if never started. */
    val trialStartedAtMs: Flow<Long> =
        ctx.entitlementDataStore.data.map { it[KEY_TRIAL_START] ?: 0L }

    /**
     * Run exactly once per install: stamp the record as initialised, and if
     * [grandfatherGrant] is set, hand out a permanent legacy Pro unlock. Later
     * launches are no-ops, so flipping [Monetization.GRANDFATHER_NEW_INSTALLS]
     * off in a future build never revokes an already-granted legacy unlock.
     */
    suspend fun ensureInitialised(grandfatherGrant: Boolean) {
        ctx.entitlementDataStore.edit { p ->
            if (p[KEY_INITIALISED] != true) {
                p[KEY_INITIALISED] = true
                if (grandfatherGrant) p[KEY_LEGACY] = true
            }
        }
    }

    /** Start the trial clock. Idempotent — a second call won't reset it. */
    suspend fun startTrial(nowMs: Long) {
        ctx.entitlementDataStore.edit { p ->
            if ((p[KEY_TRIAL_START] ?: 0L) == 0L) p[KEY_TRIAL_START] = nowMs
        }
    }

    /** Test/support hook to force a legacy grant. */
    suspend fun setLegacyPro(v: Boolean) {
        ctx.entitlementDataStore.edit { it[KEY_LEGACY] = v }
    }

    private companion object {
        val KEY_INITIALISED: Preferences.Key<Boolean> = booleanPreferencesKey("initialised")
        val KEY_LEGACY:      Preferences.Key<Boolean> = booleanPreferencesKey("legacy_pro")
        val KEY_TRIAL_START: Preferences.Key<Long>    = longPreferencesKey("trial_started_at")
    }
}
