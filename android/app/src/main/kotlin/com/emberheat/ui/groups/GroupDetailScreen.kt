package com.emberheat.ui.groups

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.data.group.GroupController
import com.emberheat.data.store.BoundDevice
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.CoolBlue
import com.emberheat.ui.theme.ErrRed
import com.emberheat.ui.theme.OkGreen

@Composable
fun GroupDetailScreen(
    groupId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val vm: GroupDetailViewModel = viewModel(
        key     = "group-$groupId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GroupDetailViewModel(groupId) as T
        },
    )
    val ui by vm.ui.collectAsState()
    val group = ui.group

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingMember by remember { mutableStateOf(false) }

    var target by remember { mutableIntStateOf(20) }
    var gear   by remember { mutableIntStateOf(5) }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = group?.name ?: "Group",
            subtitle = "${ui.members.size} heaters",
            onBack   = onBack,
            actions = {
                IconButton(onClick = { renaming = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = Color.White)
                }
                IconButton(onClick = { deleting = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete group", tint = Color.White)
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ProgressCard(progress = ui.progress) }

            item {
                TargetStepperCard(
                    target  = target,
                    enabled = ui.members.isNotEmpty(),
                    onMinus = { target = (target - 1).coerceAtLeast(10) },
                    onPlus  = { target = (target + 1).coerceAtMost(40) },
                    onSend  = { vm.setTarget(target) },
                )
            }
            item {
                GearSegmented(
                    selected = gear,
                    enabled  = ui.members.isNotEmpty(),
                    onPick   = { g -> gear = g; vm.setGear(g) },
                )
            }
            item {
                ModeRow(
                    enabled = ui.members.isNotEmpty(),
                    onHeat  = vm::start,
                    onVent  = vm::ventilate,
                    onStop  = vm::stop,
                )
            }

            item { SectionTitle("MEMBERS") }
            items(ui.members, key = { it.mac }) { dev -> MemberRow(dev) { vm.removeMember(dev.mac) } }

            if (ui.orphans.isNotEmpty()) {
                items(ui.orphans, key = { it }) { mac -> OrphanRow(mac) { vm.removeMember(mac) } }
            }

            if (ui.candidates.isNotEmpty()) {
                item {
                    AddMemberButton(onClick = { addingMember = true })
                }
            }
        }
    }

    if (renaming && group != null) {
        RenameDialog(initial = group.name,
            onDismiss = { renaming = false },
            onSave    = { newName -> vm.rename(newName); renaming = false })
    }
    if (deleting && group != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            confirmButton = {
                TextButton(onClick = { vm.delete(); deleting = false; onDeleted() }) { Text("Delete") }
            },
            dismissButton  = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
            title = { Text("Delete ${group.name}?") },
            text  = { Text("Removes the group. The heaters themselves stay paired.") },
        )
    }
    if (addingMember && ui.candidates.isNotEmpty()) {
        AddMemberDialog(
            candidates = ui.candidates,
            onDismiss  = { addingMember = false },
            onPick     = { mac -> vm.addMember(mac); addingMember = false },
        )
    }
}

// --- Cards / rows ------------------------------------------------------

@Composable
private fun ProgressCard(progress: GroupController.Progress) {
    when (progress) {
        is GroupController.Progress.Idle -> { /* nothing */ }
        is GroupController.Progress.Running -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Applying \"${progress.action}\" · ${progress.current}/${progress.total}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(progress.mac,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                }
            }
        }
        is GroupController.Progress.Done -> {
            val okCount = progress.results.count { it.ok }
            val color = if (okCount == progress.results.size) OkGreen else ErrRed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, color), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text  = "${progress.action} · $okCount/${progress.results.size} OK",
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun TargetStepperCard(
    target: Int,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            Text("APPLY TARGET TO ALL",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onSend, enabled = enabled) { Text("Send") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Icons.Filled.Remove, enabled, onMinus)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(target.toString(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("°C",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            CircleButton(Icons.Filled.Add, enabled, onPlus)
        }
    }
}

@Composable
private fun CircleButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else         MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                   else         MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun GearSegmented(selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("APPLY POWER LEVEL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()) {
            (1..10).forEach { g ->
                val isSel = selected == g
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) MaterialTheme.colorScheme.secondary
                                    else        MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = enabled) { onPick(g) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(g.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ModeRow(enabled: Boolean, onHeat: () -> Unit, onVent: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeButton("Heat all",      Icons.Filled.Whatshot,         ErrRed,   enabled, Modifier.weight(1f), onHeat)
        ModeButton("Ventilate all", Icons.Filled.Air,              CoolBlue, enabled, Modifier.weight(1f), onVent)
        ModeButton("Stop all",      Icons.Filled.PowerSettingsNew, MaterialTheme.colorScheme.outline,
                                                                          enabled, Modifier.weight(1f), onStop)
    }
}

@Composable
private fun ModeButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null,
            tint = if (enabled) accent else MaterialTheme.colorScheme.outline)
        Text(label,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else        MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun MemberRow(dev: BoundDevice, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Groups, contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary)
        Column(modifier = Modifier.weight(1f)) {
            Text(dev.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(dev.mac,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Remove, contentDescription = "Remove from group",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OrphanRow(mac: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Forgotten heater",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Text(mac,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Drop",
                tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AddMemberButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary)
        Text("Add member",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AddMemberDialog(
    candidates: List<BoundDevice>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add member") },
        text  = {
            Column {
                candidates.forEach { dev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(dev.mac) }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dev.name, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(dev.mac,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename group") },
        text  = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Group name") },
            )
        },
    )
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
