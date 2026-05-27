package uk.co.twinscrollgridbalancer.tsgbheater.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import uk.co.twinscrollgridbalancer.tsgbheater.data.fuel.FuelTracker
import uk.co.twinscrollgridbalancer.tsgbheater.remote.FuelResp
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.FuelCard
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ErrRed
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.OkGreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ProbeSlate
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbRed

@Composable
fun RemoteControlScreen(serverId: String, onBack: () -> Unit) {
    val vm: RemoteControlViewModel = viewModel(
        factory = RemoteControlVmFactory(serverId,
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application)
    )
    val ui by vm.ui.collectAsState()
    val t = ui.status?.telemetry

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = ui.serverLabel.ifBlank { "Remote" },
            subtitle = ui.serverUrl,
            onBack   = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status row
            item {
                Card {
                    val (label, kind) = when {
                        ui.status?.isReady == true                 -> "Connected"     to OkGreen
                        ui.status?.state in listOf("Connecting",
                            "DiscoveringServices","Reconnecting") -> "Connecting…"   to ProbeSlate
                        ui.status?.state == "Failed"               -> "Link failed"   to ErrRed
                        ui.status == null                          -> "Waiting…"     to ProbeSlate
                        else                                       -> "Disconnected" to ProbeSlate
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(label, kind)
                        Text(
                            text  = ui.status?.lastError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.connect() }) { Text("Reconnect") }
                    }
                    ui.lastError?.let {
                        Text("HTTP: $it",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            // Hero readout
            item {
                Card {
                    val ambient = t?.ambientC
                    Text(
                        text  = t?.runningLabel ?: "—",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text  = ambient?.let { "%.1f °C".format(it) } ?: "— °C",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text  = "ambient",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Run mode picker
            item {
                Card {
                    Text("Run mode", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.onSurface)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeBtn("Auto",       "auto",   Modifier.weight(1f), vm)
                        ModeBtn("Manual",     "manual", Modifier.weight(1f), vm)
                        ModeBtn("Start-Stop", "ss",     Modifier.weight(1f), vm)
                    }
                }
            }

            // Target stepper
            item {
                Card {
                    Text("Target", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { vm.nudgeTarget(-1) }) { Text("–") }
                        Text(
                            text  = "${ui.targetEdit} °C",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.nudgeTarget(+1) }) { Text("+") }
                    }
                }
            }

            // Action row
            item {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.start() },
                        colors = ButtonDefaults.buttonColors(containerColor = TsgbRed),
                        modifier = Modifier.weight(1f)) { Text("Heat on") }
                    OutlinedButton(onClick = { vm.vent() },
                        modifier = Modifier.weight(1f)) { Text("Vent") }
                    Button(onClick = { vm.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = ProbeSlate),
                        modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }

            // Fuel — same card as direct BLE, populated from API.
            ui.status?.fuel?.let { fuel ->
                item {
                    FuelCard(
                        snapshot     = fuel.toFuelSnapshot(),
                        currentGear  = t?.aimGear ?: 5,
                        onRefill     = vm::refillFuel,
                        onSaveConfig = vm::updateFuelConfig,
                    )
                }
            }

            // Stats
            item {
                Card {
                    Text("Telemetry", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.onSurface)
                    StatRow("Target",  t?.targetC?.let { "%.0f °C".format(it) } ?: "—")
                    StatRow("Gear",    t?.aimGear?.let { "Gear $it" } ?: "—")
                    StatRow("Housing", t?.housingC?.let { "%.1f °C".format(it) } ?: "—")
                    StatRow("Battery", t?.batteryV?.let { "%.1f V".format(it) } ?: "—")
                    StatRow("Altitude", t?.altitudeM?.let { "$it m" } ?: "—")
                    StatRow("MAC", ui.status?.currentMac ?: "—")
                }
            }
        }
    }
}

@Composable
private fun ModeBtn(label: String, mode: String, modifier: Modifier, vm: RemoteControlViewModel) {
    OutlinedButton(onClick = { vm.setRunMode(mode) }, modifier = modifier) { Text(label) }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Pill(label: String, tint: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

// VM factory because RemoteControlViewModel needs a constructor arg
// (the serverId). The default viewModel() resolver only handles no-arg
// or Application-only constructors.
private class RemoteControlVmFactory(
    private val serverId: String,
    private val app: android.app.Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RemoteControlViewModel(app, serverId) as T
}

// Bridge the API-side FuelResp into the same FuelSnapshot shape the
// shared FuelCard renders. The Windows server reports the alert level
// as a string ("None"/"Warning"/"Critical"/"Shutdown") matching the
// enum's ToString(); decode it back into AlertLevel.
private fun FuelResp.toFuelSnapshot(): FuelTracker.FuelSnapshot {
    val level = when (alert.lowercase()) {
        "warning"  -> FuelTracker.AlertLevel.WARNING
        "critical" -> FuelTracker.AlertLevel.CRITICAL
        "shutdown" -> FuelTracker.AlertLevel.SHUTDOWN
        else       -> FuelTracker.AlertLevel.NONE
    }
    return FuelTracker.FuelSnapshot(
        mac                 = mac ?: "",
        currentLitres       = currentLitres,
        tankLitres          = tankLitres,
        consumptionLowLph   = consumptionLowLph,
        consumptionHighLph  = consumptionHighLph,
        alert               = level,
    )
}
