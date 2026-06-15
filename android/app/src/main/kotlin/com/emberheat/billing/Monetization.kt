package com.emberheat.billing

/**
 * Central monetization config. Deliberately tiny and all in one place so the
 * commercial model is auditable at a glance and easy to flip on/off.
 *
 * Model (decided 2026-05):
 *   - Free        : full single-heater LOCAL control (scan, connect, manual,
 *                   advance, timer, altitude, faults, monitoring).
 *   - Pro         : Remote, Schedules, Auto Start/Stop, Groups, Widgets, Fuel.
 *                   Unlocked by ANY of: one-time unlock, a supporter tier,
 *                   an active yearly subscription, a legacy (grandfather) grant,
 *                   or an in-progress 14-day trial.
 *   - Trial       : self-managed, opt-in, 14 days. Never auto-charges.
 *   - Grandfather : everyone on the RC keeps Pro free forever.
 *   - New drivers : handled OFF-Play as a "sponsor a heater" email request,
 *                   so it isn't billed as a Play digital good.
 */
object Monetization {

    /** Length of the self-managed free trial. */
    const val TRIAL_DAYS: Long = 14L
    const val TRIAL_MS: Long = TRIAL_DAYS * 24L * 60L * 60L * 1000L

    /**
     * When true, the FIRST launch on a device with no entitlement record is
     * granted a permanent legacy (grandfather) Pro unlock. Leave true through
     * the whole RC; flip to false in the first build that actually charges so
     * fresh installs of the paid version go through the normal trial/purchase
     * flow while RC users keep what they were given.
     */
    const val GRANDFATHER_NEW_INSTALLS: Boolean = true

    /** Off-Play target for the £39.99 "sponsor a new heater driver" request. */
    // TODO: set to your real support address before publishing.
    const val REQUEST_HEATER_EMAIL: String = ""
}

/**
 * Play Console product IDs. These must match what you create in the Play
 * Console (Monetize → Products). Until they exist, BillingManager simply
 * reports no products and the app behaves as Free/Trial/Legacy.
 */
object ProductIds {
    // One-time managed products (in-app).
    const val PRO_UNLOCK   = "pro_unlock"     // baseline Pro, ~£5.99
    const val SUPPORTER_10 = "supporter_999"  // same unlock, pay more (~£9.99)
    const val SUPPORTER_15 = "supporter_1499" // same unlock, pay more (~£14.99)

    // Subscription.
    const val PRO_YEARLY   = "pro_yearly"     // yearly Pro

    val ONE_TIME: List<String> = listOf(PRO_UNLOCK, SUPPORTER_10, SUPPORTER_15)
    val SUBS: List<String>     = listOf(PRO_YEARLY)

    /** Owning any of these grants Pro. */
    val ALL_PRO_GRANTING: List<String> = ONE_TIME + SUBS
}
