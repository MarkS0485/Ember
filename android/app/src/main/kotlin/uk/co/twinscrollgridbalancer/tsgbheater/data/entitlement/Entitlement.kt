package uk.co.twinscrollgridbalancer.tsgbheater.data.entitlement

/** How a user came to have Pro (or not). */
enum class ProSource { NONE, LEGACY, TRIAL, ONE_TIME, SUBSCRIPTION }

/**
 * Immutable snapshot of the user's commercial entitlement. Computed by
 * [EntitlementRepository] from the local store (legacy flag, trial start) plus
 * live Play ownership, and re-evaluated on a slow ticker so a trial expiry
 * re-locks the UI within ~a minute.
 */
data class Entitlement(
    val proActive: Boolean,
    val source: ProSource,
    val trialStartedAtMs: Long?,  // null = trial never started
    val trialEndsAtMs: Long?,     // null = no trial in play
    val ownedProductId: String?,  // the granting SKU, if a purchase/sub
) {
    val trialActive: Boolean get() = source == ProSource.TRIAL
    val trialEverStarted: Boolean get() = trialStartedAtMs != null

    /** Whole days (rounded up) left on the trial, or 0 if none/expired. */
    fun trialDaysLeft(nowMs: Long): Long {
        val end = trialEndsAtMs ?: return 0L
        val remaining = end - nowMs
        if (remaining <= 0L) return 0L
        val day = 24L * 60L * 60L * 1000L
        return (remaining + day - 1L) / day
    }

    companion object {
        val FREE = Entitlement(
            proActive = false,
            source = ProSource.NONE,
            trialStartedAtMs = null,
            trialEndsAtMs = null,
            ownedProductId = null,
        )
    }
}
