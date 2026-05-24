package uk.co.twinscrollgridbalancer.tsgbheater.ui.me

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.CoolBlue
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FlameOrange
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FuelAmber
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ProbeSlate
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbNavy
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbRed
import androidx.compose.ui.graphics.Color

private data class CmdEntry(
    val group: String,
    val name: String,
    val frame: String,
    val description: String,
    val accent: Color,
)

// Hand-curated catalogue of every command this app builds. Source of
// authority is docs/BLE_PROTOCOL.md plus FrameCodec. <rand> is a per-press
// random nonce that the controller echoes in its ACK.
private val CATALOGUE: List<CmdEntry> = listOf(
    CmdEntry("BUTTON (op 0x61)", "Start heater",
        "AA 00 61 01 <rand> 00 crcH crcL",
        "Begin a normal heat cycle. Identical to pressing ON on the front panel.",
        TsgbRed),
    CmdEntry("BUTTON (op 0x61)", "Stop heater",
        "AA 00 61 02 <rand> 00 crcH crcL",
        "Begin a normal cool-down then stop. Also stops ventilation/blow.",
        TsgbRed),
    CmdEntry("BUTTON (op 0x61)", "Vendor key — Up",
        "AA 00 61 03 <rand> 00 crcH crcL",
        "Replays the front-panel up-arrow press. Context-dependent.",
        FlameOrange),
    CmdEntry("BUTTON (op 0x61)", "Vendor key — Down",
        "AA 00 61 04 <rand> 00 crcH crcL",
        "Replays the front-panel down-arrow press. Context-dependent.",
        FlameOrange),
    CmdEntry("BUTTON (op 0x61)", "Blow only",
        "AA 00 61 09 <rand> 00 crcH crcL",
        "Spins the fan with no ignition. Used to clear residual gas.",
        CoolBlue),
    CmdEntry("BUTTON (op 0x61)", "Oil pump on",
        "AA 00 61 0B <rand> 00 crcH crcL",
        "Manually energise the fuel pump (priming).",
        FuelAmber),
    CmdEntry("BUTTON (op 0x61)", "Oil pump off",
        "AA 00 61 0C <rand> 00 crcH crcL",
        "Stop the manual fuel pump.",
        FuelAmber),
    CmdEntry("BUTTON (op 0x61)", "Switch to °C",
        "AA 00 61 08 <rand> 00 crcH crcL",
        "Make the heater report and accept temperatures in Celsius.",
        TsgbNavy),
    CmdEntry("BUTTON (op 0x61)", "Switch to °F",
        "AA 00 61 0A <rand> 00 crcH crcL",
        "Make the heater report and accept temperatures in Fahrenheit.",
        TsgbNavy),

    CmdEntry("PARAM (op 0x66)", "Set target temperature",
        "AA 00 66 01 <unit> <temp> crcH crcL",
        "<unit>: 0 = °C (10-40), 1 = °F (50-104). <temp> is unsigned.",
        TsgbRed),
    CmdEntry("PARAM (op 0x66)", "Set heat level (gear)",
        "AA 00 66 02 00 <gear> crcH crcL",
        "<gear> 1-10 (Manual). 2-10 in Blow mode.",
        FlameOrange),
    CmdEntry("PARAM (op 0x66)", "Set run mode",
        "AA 00 66 00 00 <mode> crcH crcL",
        "<mode>: 0 = Auto, 1 = Manual, 2 = Start-Stop.",
        TsgbNavy),
    CmdEntry("PARAM (op 0x66)", "Set Start-Stop hysteresis",
        "AA 00 66 04 <unit> <diff> crcH crcL",
        "Allowed drift above target before Start-Stop cuts the burn. " +
        "°C 3-15, °F 5-27.",
        TsgbNavy),

    CmdEntry("STREAM (op 0x65)", "Start telemetry stream",
        "AA 00 65 02 0A 00 crcH crcL",
        "Push live regInfoArea frames at roughly 1 Hz.",
        ProbeSlate),
    CmdEntry("STREAM (op 0x65)", "Stop telemetry stream",
        "AA 00 65 02 00 1E crcH crcL",
        "Halt the periodic telemetry stream.",
        ProbeSlate),

    CmdEntry("READ (op 0x64)", "Read regInfoArea",
        "AA 00 64 F2 00 05 crcH crcL",
        "One-shot read of the main live-status block (40 bytes).",
        ProbeSlate),
)

@Composable
fun MenuScreen(onBack: () -> Unit) {
    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Protocol catalogue",
            subtitle = "Every command this app sends",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CATALOGUE.groupBy { it.group }.forEach { (group, entries) ->
                item {
                    Text(
                        text  = group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(entries, key = { it.name }) { CmdCard(it) }
            }
            item {
                Text(
                    text  = "Sync byte AA, length byte 00 for all 8-byte commands. CRC " +
                            "is CRC-16/XMODEM (poly 0x1021, init 0) over bytes 0..5 — see " +
                            "FrameCodec.kt for the implementation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun CmdCard(e: CmdEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text  = "■",
                color = e.accent,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text  = e.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text  = e.frame,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text  = e.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
