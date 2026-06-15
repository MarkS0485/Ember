package com.emberheat.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.ble.ConnectionState
import com.emberheat.data.store.BoundDevice
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.OkGreen
import com.emberheat.ui.theme.EmberNavy
import com.emberheat.ui.theme.EmberRed

@Composable
fun ScanScreen(onOpenBleList: () -> Unit) {
    val vm: ScanViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    var renaming by remember { mutableStateOf<BoundDevice?>(null) }
    var deleting by remember { mutableStateOf<BoundDevice?>(null) }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Ember",
            subtitle = "Pair a heater · or reconnect to one you've used before",
            actions = {
                IconButton(onClick = onOpenBleList) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = "BLE devices", tint = Color.White)
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PairHero(onOpenBleList) }

            if (ui.bound.isNotEmpty()) {
                item {
                    Text(
                        text  = "PREVIOUSLY PAIRED",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(ui.bound, key = { it.mac }) { dev ->
                    BoundRow(
                        device     = dev,
                        isCurrent  = dev.mac == ui.currentMac,
                        liveState  = ui.connectionState.takeIf { dev.mac == ui.currentMac },
                        onReconnect = { vm.reconnect(dev.mac) },
                        onRename    = { renaming = dev },
                        onRemove    = { deleting = dev },
                    )
                }
            }

            item {
                Text(
                    text  = "Long-press the three-dot icon on any heater to rename or " +
                            "remove it. QR-code pairing is on the roadmap; BLE scan " +
                            "covers the same ground.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }

    renaming?.let { dev ->
        RenameDialog(
            initial   = dev.name,
            onDismiss = { renaming = null },
            onSave    = { newName -> vm.rename(dev.mac, newName); renaming = null },
        )
    }
    deleting?.let { dev ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(onClick = { vm.unbind(dev.mac); deleting = null }) { Text("Remove") }
            },
            dismissButton  = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            title = { Text("Remove ${dev.name}?") },
            text  = { Text("Forgets the pairing on this phone (and drops it from any " +
                           "group it was in). You can pair the heater again any time.") },
        )
    }
}

@Composable
private fun PairHero(onOpenBleList: () -> Unit) {
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
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = EmberRed,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Pair a new heater",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = "Power the controller on, then scan from this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onOpenBleList,
            colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null,
                modifier = Modifier.padding(end = 8.dp))
            Text("Scan for BLE heaters")
        }
    }
}

@Composable
private fun BoundRow(
    device: BoundDevice,
    isCurrent: Boolean,
    liveState: ConnectionState?,
    onReconnect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent = if (isCurrent && liveState == ConnectionState.Ready) OkGreen else EmberNavy
    val statusText = when {
        !isCurrent                                       -> "Tap to reconnect"
        liveState == ConnectionState.Ready               -> "Connected"
        liveState == ConnectionState.Connecting          -> "Connecting…"
        liveState == ConnectionState.DiscoveringServices -> "Discovering…"
        liveState == ConnectionState.Failed              -> "Failed — tap to retry"
        else                                             -> "Disconnected"
    }
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(onClick = onReconnect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = device.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = device.mac,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text  = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Remove from this phone") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename heater") },
        text  = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Friendly name") },
            )
        },
    )
}
