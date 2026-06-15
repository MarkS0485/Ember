package com.emberheat.data.entitlement

import com.emberheat.billing.BillingManager
import com.emberheat.billing.Monetization
import com.emberheat.billing.ProductIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single source of truth for "is Pro active and why". Combines the local
 * [EntitlementStore] (legacy grant + trial start) with live Play ownership
 * from [BillingManager], re-evaluated on a slow ticker so a lapsing trial
 * re-locks the UI on its own.
 *
 * Precedence: a real purchase/subscription > legacy grant > active trial > free.
 */
class EntitlementRepository(
    private val store: EntitlementStore,
    private val billing: BillingManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Emits immediately then every minute, so trial expiry is reflected without
    // the user having to leave and re-enter the screen.
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    val entitlement: StateFlow<Entitlement> =
        combine(
            store.legacyPro,
            store.trialStartedAtMs,
            billing.ownedProductIds,
            ticker,
        ) { legacy, trialStart, owned, _ ->
            compute(legacy = legacy, trialStartRaw = trialStart, owned = owned, now = clock())
        }.stateIn(scope, SharingStarted.Eagerly, Entitlement.FREE)

    val isProActive: StateFlow<Boolean> =
        entitlement
            .map { it.proActive }
            .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch { store.ensureInitialised(Monetization.GRANDFATHER_NEW_INSTALLS) }
    }

    /** Begin the self-managed 14-day trial (idempotent). */
    suspend fun startTrial() = store.startTrial(clock())

    /** Re-query Play for owned products (the "Restore purchases" action). */
    suspend fun restore() = billing.refreshPurchases()

    private fun compute(
        legacy: Boolean,
        trialStartRaw: Long,
        owned: Set<String>,
        now: Long,
    ): Entitlement {
        val trialStart = trialStartRaw.takeIf { it > 0L }
        val trialEnds = trialStart?.plus(Monetization.TRIAL_MS)
        val trialActive = trialEnds != null && now < trialEnds

        val ownedPro = ProductIds.ALL_PRO_GRANTING.firstOrNull { it in owned }

        return when {
            ownedPro != null -> Entitlement(
                proActive = true,
                source = if (ownedPro in ProductIds.SUBS) ProSource.SUBSCRIPTION else ProSource.ONE_TIME,
                trialStartedAtMs = trialStart,
                trialEndsAtMs = trialEnds,
                ownedProductId = ownedPro,
            )
            legacy -> Entitlement(
                proActive = true,
                source = ProSource.LEGACY,
                trialStartedAtMs = trialStart,
                trialEndsAtMs = trialEnds,
                ownedProductId = null,
            )
            trialActive -> Entitlement(
                proActive = true,
                source = ProSource.TRIAL,
                trialStartedAtMs = trialStart,
                trialEndsAtMs = trialEnds,
                ownedProductId = null,
            )
            else -> Entitlement(
                proActive = false,
                source = ProSource.NONE,
                trialStartedAtMs = trialStart,
                trialEndsAtMs = trialEnds,
                ownedProductId = null,
            )
        }
    }
}
