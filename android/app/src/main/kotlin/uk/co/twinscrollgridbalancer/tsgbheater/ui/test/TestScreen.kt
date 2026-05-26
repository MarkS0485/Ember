package uk.co.twinscrollgridbalancer.tsgbheater.ui.test

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.StatusKind
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.StatusPill
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.CoolBlue
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FlameOrange
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FuelAmber
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ProbeSlate

@Composable
fun TestScreen(onBack: () -> Unit) {
    val state by ServiceLocator.ble.connectionState.collectAsState()
    val telemetry by ServiceLocator.ble.telemetry.collectAsState()
    val ready = state == ConnectionState.Ready
    val runningMode = telemetry?.runningMode ?: RunningMode.Unknown
    // Heater silently drops the timed-pump frame outside Standby/ManualPump.
    val pumpAllowed = ready && (runningMode == RunningMode.Standby ||
                                runningMode == RunningMode.ManualPump)
    val scope = rememberCoroutineScope()
    val ble   = ServiceLocator.ble

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Heater test mode",
            subtitle = "Component-level dry-run",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row {
                    StatusPill(
                        label = if (ready) "Ready" else state.name,
                        kind  = if (ready) StatusKind.Online else StatusKind.Offline,
                    )
                }
            }
            item {
                TestCard(
                    title    = "Ventilation only",
                    subtitle = "Spins the fan without igniting. Used to clear residual " +
                               "combustion gas after shutdown.",
                    iconOn   = Icons.Filled.Air,
                    onLabel  = "Start blow",
                    offLabel = "Stop",
                    accent   = CoolBlue,
                    enabled  = ready,
                    onOn     = { scope.launch { ble.blowOn()  } },
                    onOff    = { scope.launch { ble.sendStop() } }, // CMD_OFF doubles as stop-blow
                )
            }
            item {
                var pumpSeconds by remember {
                    mutableStateOf(FrameCodec.MANUAL_PUMP_DEFAULT_S)
                }
                ManualPumpCard(
                    seconds        = pumpSeconds,
                    onSecondsChange = { pumpSeconds = it },
                    onEnabled      = pumpAllowed,
                    offEnabled     = ready,
                    runningMode    = runningMode,
                    onOn           = { scope.launch { ble.oilPumpOn(pumpSeconds) } },
                    onOff          = { scope.launch { ble.oilPumpOff() } },
                )
            }
            item {
                ButtonPairCard(
                    title    = "Vendor up/down keys",
                    subtitle = "Replays the front-panel arrow buttons via the BLE link. " +
                               "Action depends on which screen the controller is on.",
                    accent   = FlameOrange,
                    enabled  = ready,
                    aLabel   = "Up",       aIcon = Icons.Filled.ArrowUpward,
                    bLabel   = "Down",     bIcon = Icons.Filled.ArrowDownward,
                    onA      = { scope.launch { ble.sendRaw(uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec.buildButtonUp())   } },
                    onB      = { scope.launch { ble.sendRaw(uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec.buildButtonDown()) } },
                )
            }
            item {
                TestCard(
                    title    = "Force a register read",
                    subtitle = "Asks the controller for a fresh regInfoArea (tag 0xF2) " +
                               "snapshot. Useful to verify the live stream is alive.",
                    iconOn   = Icons.Filled.Refresh,
                    onLabel  = "Read now",
                    offLabel = "—",
                    accent   = ProbeSlate,
                    enabled  = ready,
                    onOn     = { scope.launch { ble.readRegInfo() } },
                    onOff    = { /* no-off variant */ },
                    singleAction = true,
                )
            }
            item {
                Text(
                    text  = "Test commands are unsafe with the heater unattended. Don't " +
                            "leave the pump running dry.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TestCard(
    title: String,
    subtitle: String,
    iconOn: ImageVector,
    onLabel: String,
    offLabel: String,
    accent: Color,
    enabled: Boolean,
    onOn: () -> Unit,
    onOff: () -> Unit,
    singleAction: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = iconOn,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOn, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(onLabel)
            }
            if (!singleAction) {
                OutlinedButton(onClick = onOff, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Stop, contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp))
                    Text(offLabel)
                }
            }
        }
    }
}

@Composable
private fun ManualPumpCard(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    onEnabled: Boolean,
    offEnabled: Boolean,
    runningMode: RunningMode,
    onOn: () -> Unit,
    onOff: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Filled.LocalGasStation,
                contentDescription = null,
                tint = FuelAmber,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Manual oil pump",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = "Runs the fuel pump for the chosen duration — used to prime " +
                            "the line after a fuel-system service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!onEnabled) {
            // Surface the gating reason; otherwise a disabled button looks
            // identical to a connection failure.
            val hint = when (runningMode) {
                RunningMode.Unknown -> "Waiting for telemetry — pump unlocks once the heater reports a state."
                RunningMode.Standby,
                RunningMode.ManualPump -> "Pump ready."
                else -> "Pump only runs in Standby. Heater is in “${runningMode.label}” — stop the burner first."
            }
            Text(
                text  = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = "Duration",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { onSecondsChange((seconds - 5).coerceAtLeast(FrameCodec.MANUAL_PUMP_MIN_S)) },
                enabled = seconds > FrameCodec.MANUAL_PUMP_MIN_S,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(
                text  = "${seconds}s",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(
                onClick = { onSecondsChange((seconds + 5).coerceAtMost(FrameCodec.MANUAL_PUMP_MAX_S)) },
                enabled = seconds < FrameCodec.MANUAL_PUMP_MAX_S,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOn, enabled = onEnabled, modifier = Modifier.weight(1f)) {
                Text("Pump on")
            }
            OutlinedButton(onClick = onOff, enabled = offEnabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Stop, contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp))
                Text("Pump off")
            }
        }
    }
}

@Composable
private fun ButtonPairCard(
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean,
    aLabel: String, aIcon: ImageVector, onA: () -> Unit,
    bLabel: String, bIcon: ImageVector, onB: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text  = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onA, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(aIcon, contentDescription = null, tint = accent,
                    modifier = Modifier.padding(end = 6.dp))
                Text(aLabel)
            }
            OutlinedButton(onClick = onB, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(bIcon, contentDescription = null, tint = accent,
                    modifier = Modifier.padding(end = 6.dp))
                Text(bLabel)
            }
        }
    }
}
