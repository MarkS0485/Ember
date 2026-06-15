package com.emberheat.ui.me

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.emberheat.ble.FaultCodes
import com.emberheat.ble.HeaterFault
import com.emberheat.di.ServiceLocator
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.ErrRed
import com.emberheat.ui.theme.OkGreen

@Composable
fun FlagScreen(onBack: () -> Unit) {
    val telemetry by ServiceLocator.ble.telemetry.collectAsState()
    val active = FaultCodes.active(telemetry?.faultBits ?: 0)

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Active flags",
            subtitle = if (active.isEmpty()) "All clear" else "${active.size} active",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active.isEmpty()) {
                item { AllClearCard(connected = telemetry != null) }
            }
            items(active, key = { it.code }) { ActiveFaultCard(it) }
            if (active.isNotEmpty()) {
                item { Text(
                    text  = "Heater faults clear automatically when the underlying " +
                            "condition resolves; persistent faults require a power cycle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                ) }
            }
        }
    }
}

@Composable
private fun AllClearCard(connected: Boolean) {
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
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = OkGreen,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = if (connected) "No active faults" else "Not connected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = if (connected) "Heater reports a clean fault bitmask."
                        else           "Connect a heater to see live fault data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveFaultCard(f: HeaterFault) {
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = ErrRed,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text  = f.code,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = ErrRed,
            )
            Text(
                text  = f.short,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text  = f.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
