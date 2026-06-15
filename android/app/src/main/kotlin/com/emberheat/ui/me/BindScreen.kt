package com.emberheat.ui.me

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.data.store.BoundDevice
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.OkGreen
import com.emberheat.ui.theme.EmberNavy

@Composable
fun BindScreen(onBack: () -> Unit) {
    val vm: BindViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    var renaming by remember { mutableStateOf<BoundDevice?>(null) }
    var deleting by remember { mutableStateOf<BoundDevice?>(null) }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Bound devices",
            subtitle = "${ui.devices.size} paired",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.devices.isEmpty()) {
                item {
                    Text(
                        "No heaters paired yet. Pair one from the Scan tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(ui.devices, key = { it.mac }) { d ->
                BoundRow(
                    device     = d,
                    isCurrent  = ui.currentMac == d.mac,
                    onSelect   = { vm.setCurrent(d.mac) },
                    onRename   = { renaming = d },
                    onUnbind   = { deleting = d },
                )
            }
        }
    }

    renaming?.let { dev ->
        RenameDialog(dev,
            onDismiss = { renaming = null },
            onSave    = { newName -> vm.rename(dev.mac, newName); renaming = null },
        )
    }
    deleting?.let { dev ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(onClick = { vm.unbind(dev.mac); deleting = null }) { Text("Unbind") }
            },
            dismissButton  = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            title = { Text("Unbind ${dev.name}?") },
            text  = { Text("Removes the saved pairing. You can pair it again from the Scan tab.") },
        )
    }
}

@Composable
private fun BoundRow(
    device: BoundDevice,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onUnbind: () -> Unit,
) {
    val accent = if (isCurrent) OkGreen else EmberNavy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = if (isCurrent) Icons.Filled.Link else Icons.Filled.LinkOff,
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
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onUnbind) {
            Icon(Icons.Filled.Delete, contentDescription = "Unbind",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RenameDialog(dev: BoundDevice, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(dev.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename") },
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
