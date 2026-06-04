package uk.co.twinscrollgridbalancer.tsgbheater.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.twinscrollgridbalancer.tsgbheater.data.fuel.FuelTracker

// Fuel card used on both the Device screen (direct BLE path) and the
// Remote control screen (API path). The card itself is dumb — it
// renders whatever FuelTracker.FuelSnapshot you give it and bubbles
// user interactions out through the callbacks.
//
// Config edit is optional: if [onSaveConfig] is null the settings cog
// is hidden (useful on the remote screen if we ever want read-only).
@Composable
fun FuelCard(
    snapshot: FuelTracker.FuelSnapshot,
    currentGear: Int,
    onRefill: (Double) -> Unit,
    onSaveConfig: ((tank: Double?, lowLph: Double?, highLph: Double?) -> Unit)? = null,
) {
    var showRefill   by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val lphAtGear  = snapshot.lphAtGear(currentGear)
    val hoursLeft  = if (lphAtGear > 0) snapshot.currentLitres / lphAtGear else null
    val fillFraction = if (snapshot.tankLitres > 0)
        (snapshot.currentLitres / snapshot.tankLitres).toFloat().coerceIn(0f, 1f)
    else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.LocalGasStation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text  = "Fuel",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { showRefill = true }) { Text("Refill") }
            if (onSaveConfig != null) {
                Spacer(modifier = Modifier.padding(start = 6.dp))
                OutlinedButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Fuel config")
                }
            }
        }

        // Big level + hours remaining
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text  = "%.2f / %.1f L".format(snapshot.currentLitres, snapshot.tankLitres),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = hoursLeft?.let { "≈ %.1f h left".format(it) } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Level bar — colour-shifts with the alert level so the user can
        // catch it from across the room.
        LinearProgressIndicator(
            progress = { fillFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = when (snapshot.alert) {
                FuelTracker.AlertLevel.CRITICAL,
                FuelTracker.AlertLevel.SHUTDOWN -> MaterialTheme.colorScheme.error
                FuelTracker.AlertLevel.WARNING  -> Color(0xFFB45309)  // amber
                else                            -> MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Text(
            text  = "%.2f L/h @ gear %d".format(lphAtGear, currentGear),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Alert banner — only shown when we're actively in a warning state.
        val alertText = when (snapshot.alert) {
            FuelTracker.AlertLevel.WARNING  -> "Fuel low — refill soon"
            FuelTracker.AlertLevel.CRITICAL -> "Fuel critical"
            FuelTracker.AlertLevel.SHUTDOWN -> "Tank near empty — stopping heater"
            else                            -> null
        }
        if (alertText != null) {
            val bg = when (snapshot.alert) {
                FuelTracker.AlertLevel.WARNING -> Color(0xFFB45309)
                else                           -> MaterialTheme.colorScheme.error
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(alertText, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showRefill) {
        RefillDialog(
            tankSize  = snapshot.tankLitres,
            current   = snapshot.currentLitres,
            onDismiss = { showRefill = false },
            onConfirm = { litres ->
                showRefill = false
                onRefill(litres)
            },
        )
    }

    if (showSettings && onSaveConfig != null) {
        FuelSettingsDialog(
            tank   = snapshot.tankLitres,
            lowLph = snapshot.consumptionLowLph,
            highLph = snapshot.consumptionHighLph,
            onDismiss = { showSettings = false },
            onSave    = { t, l, h ->
                showSettings = false
                onSaveConfig(t, l, h)
            },
        )
    }
}

@Composable
private fun RefillDialog(
    tankSize: Double,
    current: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    // Default the input to "fill up" — the deficit from current to full.
    var input by remember { mutableStateOf("%.1f".format((tankSize - current).coerceAtLeast(0.0))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Refill tank") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How many litres did you add?",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("Litres added") },
                )
                Text("Current: %.2f / %.1f L".format(current, tankSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                val litres = input.toDoubleOrNull() ?: return@Button
                if (litres > 0) onConfirm(litres)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FuelSettingsDialog(
    tank: Double,
    lowLph: Double,
    highLph: Double,
    onDismiss: () -> Unit,
    onSave: (tank: Double?, lowLph: Double?, highLph: Double?) -> Unit,
) {
    var tankS by remember { mutableStateOf("%.2f".format(tank)) }
    var loS   by remember { mutableStateOf("%.3f".format(lowLph)) }
    var hiS   by remember { mutableStateOf("%.3f".format(highLph)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Fuel config") },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = tankS, onValueChange = { tankS = it },
                    singleLine = true, label = { Text("Tank size (L)") })
                OutlinedTextField(value = loS, onValueChange = { loS = it },
                    singleLine = true, label = { Text("Flow at level 1 (L/h)") })
                OutlinedTextField(value = hiS, onValueChange = { hiS = it },
                    singleLine = true, label = { Text("Flow at level 10 (L/h)") })
                Text("Enter the fuel flow your heater reports at its lowest (level 1) " +
                     "and highest (level 10) power. Levels in between are interpolated " +
                     "linearly.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    tankS.toDoubleOrNull()?.takeIf { it > 0 },
                    loS.toDoubleOrNull()?.takeIf { it > 0 },
                    hiS.toDoubleOrNull()?.takeIf { it > 0 },
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
