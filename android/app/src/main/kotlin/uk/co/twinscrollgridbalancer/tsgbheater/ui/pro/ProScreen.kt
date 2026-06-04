package uk.co.twinscrollgridbalancer.tsgbheater.ui.pro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.billing.Monetization
import uk.co.twinscrollgridbalancer.tsgbheater.billing.ProductIds
import uk.co.twinscrollgridbalancer.tsgbheater.data.entitlement.Entitlement
import uk.co.twinscrollgridbalancer.tsgbheater.data.entitlement.ProSource
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FuelAmber
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbNavy

private val FREE_FEATURES = listOf(
    "Scan & connect to your heater over Bluetooth",
    "Manual control — on/off, gear, target temperature",
    "Advance / boost and the read-only heater timer",
    "Altitude tuning + live fault & warning codes",
    "Background monitoring with status notifications",
)

private val PRO_FEATURES = listOf(
    "Remote control via your Windows companion (away from the heater)",
    "Schedules — multiple on/off times per day",
    "Auto Start / Stop — the phone decides when it runs",
    "Groups — drive several heaters as one",
    "Home-screen widgets (status / control / blower)",
    "Fuel tracking with low-fuel alerts & auto-shutdown",
)

private data class BuyOption(
    val id: String,
    val label: String,
    val fallbackPrice: String,
    val primary: Boolean,
)

private val BUY_OPTIONS = listOf(
    BuyOption(ProductIds.PRO_UNLOCK,   "Unlock Pro",  "£5.99",    primary = true),
    BuyOption(ProductIds.SUPPORTER_10, "Supporter",   "£9.99",    primary = false),
    BuyOption(ProductIds.SUPPORTER_15, "Supporter+",  "£14.99",   primary = false),
    BuyOption(ProductIds.PRO_YEARLY,   "Yearly",      "£4.99/yr", primary = false),
)

@Composable
fun ProScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ServiceLocator.entitlements }
    val billing = remember { ServiceLocator.billing }

    val entitlement by repo.entitlement.collectAsState()
    val products by billing.products.collectAsState()
    val now = System.currentTimeMillis()

    // True once the user owns a real purchase/sub — they don't need to see the
    // buy buttons again (legacy & trial users still see them so they can choose
    // to support / convert before expiry).
    val purchased = entitlement.source == ProSource.ONE_TIME ||
        entitlement.source == ProSource.SUBSCRIPTION

    fun priceFor(id: String, fallback: String): String =
        products.firstOrNull { it.productId == id }?.formattedPrice
            ?.takeIf { it.isNotBlank() } ?: fallback

    Column(modifier = Modifier) {
        BrandTopBar(title = "TSGB Heater Pro", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(entitlement, now) }

            // Opt-in trial CTA — only before any purchase and only if never started.
            if (!entitlement.proActive && !entitlement.trialEverStarted) {
                item {
                    Button(
                        onClick = { scope.launch { repo.startTrial() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try Pro free for ${Monetization.TRIAL_DAYS} days")
                    }
                }
            }

            item { FeatureCard("In the free app", FREE_FEATURES) }
            item { FeatureCard("Pro unlocks", PRO_FEATURES) }

            if (!purchased) {
                item {
                    SectionCard("Get Pro") {
                        if (products.isEmpty()) {
                            Text(
                                "Purchase options appear here once products are live " +
                                    "in the Play Console.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BUY_OPTIONS.forEach { opt ->
                            val label = "${opt.label} — ${priceFor(opt.id, opt.fallbackPrice)}"
                            val onBuy = {
                                ctx.findActivity()?.let { billing.launchPurchase(it, opt.id) }
                                Unit
                            }
                            if (opt.primary) {
                                Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            } else {
                                OutlinedButton(onClick = onBuy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            }
                        }
                        Text(
                            "One-time unlocks are forever. The yearly option is a " +
                                "subscription you can cancel any time.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard("Manage") {
                    TextButton(
                        onClick = { scope.launch { repo.restore() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Restore purchases") }

                    if (entitlement.source == ProSource.SUBSCRIPTION) {
                        TextButton(
                            onClick = { ctx.openManageSubscription() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Manage subscription") }
                    }
                }
            }

            item {
                SectionCard("Heater not supported?") {
                    Text(
                        "If your heater speaks a protocol the app doesn't yet handle, I can " +
                            "write a driver for it. A new device on a protocol that's already " +
                            "supported is usually free; a brand-new protocol is a one-off " +
                            "sponsorship (around £39.99) once I've confirmed it's feasible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { ctx.requestHeater() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Request a heater") }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatusCard(entitlement: Entitlement, now: Long) {
    val (headline, sub) = when (entitlement.source) {
        ProSource.SUBSCRIPTION -> "Pro (yearly) active" to "Thank you for supporting the app."
        ProSource.ONE_TIME     -> "Pro unlocked" to "Thank you for supporting the app."
        ProSource.LEGACY       -> "Pro unlocked — early supporter" to "You were here during the RC. Pro is yours for good."
        ProSource.TRIAL        -> "Pro trial active" to "${entitlement.trialDaysLeft(now)} day(s) left. No charge — it just ends."
        ProSource.NONE         ->
            if (entitlement.trialEverStarted) "Trial ended" to "Unlock Pro below to keep the Pro features."
            else "Free version" to "Full local control. Try or unlock Pro any time."
    }
    SectionCard(title = null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = FuelAmber,
                modifier = Modifier.size(34.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TsgbNavy,
                )
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, lines: List<String>) {
    SectionCard(title) {
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = FuelAmber,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String?, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

// --- Intent helpers -------------------------------------------------------

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun Context.openManageSubscription() {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=${ProductIds.PRO_YEARLY}&package=$packageName"
    )
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun Context.requestHeater() {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Monetization.REQUEST_HEATER_EMAIL}")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "TSGB Heater — new heater support request")
        putExtra(
            Intent.EXTRA_TEXT,
            "Heater make & model:\n" +
                "Where it's sold / app it normally uses:\n" +
                "Anything you know about how it pairs:\n",
        )
    }
    runCatching { startActivity(intent) }
}
