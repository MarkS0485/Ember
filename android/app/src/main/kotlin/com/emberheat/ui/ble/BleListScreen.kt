package com.emberheat.ui.ble

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.ble.BlePermissions
import com.emberheat.ble.DiscoveredDevice
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.components.PermissionGate
import com.emberheat.ui.theme.CoolBlue
import com.emberheat.ui.theme.OkGreen

@Composable
fun BleListScreen(onBack: () -> Unit) {
    val vm: BleListViewModel = viewModel()
    val devices  by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Nearby BLE devices",
            subtitle = if (scanning) "Live scan" else "Tap refresh to scan",
            onBack   = onBack,
            actions = {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 12.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = { vm.startScan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            },
        )
        PermissionGate(
            required = BlePermissions.SCAN_AND_CONNECT,
            rationale = "Ember needs Bluetooth scan and connect to find your heater. " +
                        "Location is not used.",
        ) {
            // Kick off the scan the first time the gate clears.
            LaunchedEffect(Unit) { vm.startScan() }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (devices.isEmpty()) {
                    item {
                        Text(
                            text  = if (scanning) "Listening for adverts…"
                                    else          "No devices found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(devices, key = { it.mac }) { d ->
                    DeviceRow(d) { vm.bind(d) }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DiscoveredDevice, onTap: () -> Unit) {
    val accent = if (d.isKnownHeater) OkGreen else CoolBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(onClick = onTap)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),

    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = d.name?.takeIf { it.isNotBlank() } ?: "Unnamed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = d.mac,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text  = "${d.rssi} dBm",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
