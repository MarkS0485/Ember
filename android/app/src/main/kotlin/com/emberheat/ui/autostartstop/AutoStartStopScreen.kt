package com.emberheat.ui.autostartstop

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import com.emberheat.data.auto.AutoStartStopController
import com.emberheat.data.auto.RuleSignal
import com.emberheat.data.store.BoundDevice
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.CoolBlue
import com.emberheat.ui.theme.ErrRed
import com.emberheat.ui.theme.FlameOrange
import com.emberheat.ui.theme.FuelAmber
import com.emberheat.ui.theme.OkGreen
import com.emberheat.ui.theme.ProbeSlate
import com.emberheat.ui.theme.EmberNavy

@Composable
fun AutoStartStopScreen(onBack: () -> Unit) {
    val vm: AutoStartStopViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val rules = ui.rules

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Auto Start / Stop",
            subtitle = if (rules.masterEnabled) "Enabled" else "Off",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MasterCard(enabled = rules.masterEnabled, onToggle = vm::setMaster) }

            item { DecisionCard(signal = ui.signal) }

            item { SectionTitle("THERMOSTAT") }
            item {
                IntSliderCard(
                    title    = "Target temperature",
                    subtitle = "Aim for this ambient temperature.",
                    icon     = Icons.Filled.Thermostat,
                    accent   = EmberNavy,
                    value    = rules.setpointC,
                    unit     = "°C",
                    range    = 5..30,
                    onCommit = vm::setSetpoint,
                )
            }
            item {
                IntSliderCard(
                    title    = "Margin (deadband)",
                    subtitle = "Grace around target before the engine acts. Bigger = less " +
                               "cycling, larger swing in cabin temp.",
                    icon     = Icons.Filled.Thermostat,
                    accent   = ProbeSlate,
                    value    = rules.marginC,
                    unit     = "°C",
                    range    = 1..6,
                    onCommit = vm::setMargin,
                )
            }

            item { SectionTitle("OUTER ENVELOPE") }
            item {
                IntSliderCard(
                    title    = "Cold-floor start",
                    subtitle = "If ambient drops below this, force a start regardless of " +
                               "setpoint. Frost protection.",
                    icon     = Icons.Filled.AcUnit,
                    accent   = CoolBlue,
                    value    = rules.ambientStartC,
                    unit     = "°C",
                    range    = -10..15,
                    onCommit = vm::setAmbientStart,
                )
            }
            item {
                IntSliderCard(
                    title    = "Warm-ceiling stop",
                    subtitle = "If ambient rises above this, force a stop. Heat-stroke " +
                               "safety / power saving.",
                    icon     = Icons.Filled.Whatshot,
                    accent   = FlameOrange,
                    value    = rules.ambientStopC,
                    unit     = "°C",
                    range    = 15..35,
                    onCommit = vm::setAmbientStop,
                )
            }

            item { SectionTitle("SAFETY") }
            item {
                BatteryCutoffCard(
                    enabled  = rules.batteryCutoffEnabled,
                    value    = rules.batteryCutoffV,
                    onEnable = vm::setBatteryCutoffEnabled,
                    onValue  = vm::setBatteryCutoff,
                )
            }
            item {
                IntSliderCard(
                    title    = "Cooldown between actions",
                    subtitle = "Engine waits this long after a start/stop before firing " +
                               "again. Prevents rapid cycling.",
                    icon     = Icons.Filled.Timer,
                    accent   = FuelAmber,
                    value    = rules.cooldownSec / 60,
                    unit     = "min",
                    range    = 1..30,
                    onCommit = { mins -> vm.setCooldown(mins * 60) },
                )
            }

            if (ui.perDevice.isNotEmpty()) {
                item { SectionTitle("PER-DEVICE") }
                items(ui.perDevice, key = { it.mac }) { dev ->
                    PerDeviceRow(dev, rules.masterEnabled) { v -> vm.setPerDevice(dev.mac, v) }
                }
            }

            if (ui.history.isNotEmpty()) {
                item { SectionTitle("RECENT ACTIONS") }
                items(ui.history, key = { it.atMs }) { HistoryRow(it) }
            }
        }
    }
}

// --- Cards ------------------------------------------------------------

@Composable
private fun MasterCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Master switch",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "Lets the phone decide when each enabled heater runs, using the " +
                        "rules below. Heater must also be opted in below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun DecisionCard(signal: RuleSignal) {
    val (label, color, icon) = when (signal) {
        is RuleSignal.Start          -> Triple("Engine wants START",  OkGreen,  Icons.Filled.PlayArrow)
        is RuleSignal.Stop           -> Triple("Engine wants STOP",   ErrRed,   Icons.Filled.Stop)
        is RuleSignal.Hold           -> Triple("Engine holding",      MaterialTheme.colorScheme.outline, Icons.Filled.Timer)
        is RuleSignal.NotApplicable  -> Triple("Engine off",          MaterialTheme.colorScheme.outline, Icons.Filled.Timer)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
            Text(
                text  = signal.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun IntSliderCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    value: Int,
    unit: String,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }

    LaunchedEffect(Unit) {
        snapshotFlow { local }
            .drop(1)
            .debounce(300)
            .collect { onCommit(it.toInt()) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text  = "${local.toInt()} $unit",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = accent,
            )
        }
        Slider(
            value         = local,
            onValueChange = { local = it },
            valueRange    = range.first.toFloat()..range.last.toFloat(),
            steps         = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun BatteryCutoffCard(
    enabled: Boolean,
    value: Double,
    onEnable: (Boolean) -> Unit,
    onValue: (Double) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }

    LaunchedEffect(Unit) {
        snapshotFlow { local }
            .drop(1)
            .debounce(300)
            .collect { onValue(((it * 10).toInt() / 10.0)) }  // round to 0.1 V
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Battery3Bar, contentDescription = null, tint = ErrRed,
                modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Low-battery cutoff",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Auto-stop and refuse to start if battery falls below this. " +
                     "12 V systems usually need ≥11 V, 24 V systems ≥22 V.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onEnable)
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    text  = "%.1f V".format(local),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    color = ErrRed,
                )
            }
            Slider(
                value         = local,
                onValueChange = { local = it },
                valueRange    = 10f..24f,
            )
        }
    }
}

@Composable
private fun PerDeviceRow(dev: BoundDevice, masterEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(dev.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(dev.mac,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = dev.autoStartStopEnabled && masterEnabled,
            enabled = masterEnabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun HistoryRow(d: AutoStartStopController.DecisionLog) {
    val color = if (d.signal == "START") OkGreen else ErrRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text  = d.signal,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
        Text(
            text  = formatTimeOfDay(d.atMs),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text  = d.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

private fun formatTimeOfDay(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        cal.get(java.util.Calendar.SECOND),
    )
}
