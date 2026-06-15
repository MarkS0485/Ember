package com.emberheat.ui.fuel

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.components.FuelCard
import com.emberheat.ui.theme.FuelAmber

// Dedicated Pro fuel screen, reachable from the Me tab. Reuses the shared
// [FuelCard] (same card the Device page shows) so there's a single source
// of truth for the readout, refill and config UI, then adds a "set exact
// level" affordance for first-time setup / manual correction.
@Composable
fun FuelScreen(onBack: () -> Unit) {
    val vm: FuelViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Fuel",
            subtitle = ui.deviceName?.let { "Tracking $it" } ?: "No heater paired",
            onBack   = onBack,
        )
        if (ui.mac == null) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val snap = ui.snapshot
                if (snap == null) {
                    item { InfoCard("Reading fuel level…") }
                } else {
                    item {
                        FuelCard(
                            snapshot     = snap,
                            currentGear  = ui.gear,
                            onRefill     = vm::refill,
                            onSaveConfig = vm::saveConfig,
                        )
                    }
                    item { SetLevelCard(current = snap.currentLitres, onSet = vm::setLevel) }
                }
                item { NoteCard() }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.LocalGasStation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                "No heater paired",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Pair a heater first — fuel tracking follows the heater you're connected to.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Set the exact litres currently in the tank — for first-time setup or to
// correct drift. Refill (on the card) adds litres; this overwrites.
@Composable
private fun SetLevelCard(current: Double, onSet: (Double) -> Unit) {
    var input by remember(current) { mutableStateOf("%.2f".format(current)) }
    val value = input.toDoubleOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Set exact level", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Text("Overwrite the tracked litres — useful after a manual dip-check.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Current litres") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { value?.let { onSet(it.coerceAtLeast(0.0)) } },
                enabled = value != null && value >= 0,
                colors = ButtonDefaults.buttonColors(containerColor = FuelAmber),
            ) { Text("Set") }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoteCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.WaterDrop, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            "Estimated from the heater's burn rate — top up the level whenever you " +
            "refuel to keep it accurate. Near empty, the heater stops automatically " +
            "to protect the pump.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
