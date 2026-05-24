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
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
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
    val ready = state == ConnectionState.Ready
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
                TestCard(
                    title    = "Manual oil pump",
                    subtitle = "Manually energise the fuel pump — used to prime the line " +
                               "after a fuel-system service.",
                    iconOn   = Icons.Filled.LocalGasStation,
                    onLabel  = "Pump on",
                    offLabel = "Pump off",
                    accent   = FuelAmber,
                    enabled  = ready,
                    onOn     = { scope.launch { ble.oilPumpOn()  } },
                    onOff    = { scope.launch { ble.oilPumpOff() } },
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
