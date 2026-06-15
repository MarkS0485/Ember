package com.emberheat.ui.schedule

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.data.schedule.ScheduleEvent
import com.emberheat.data.schedule.ScheduleStatus
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.OkGreen
import com.emberheat.ui.theme.WarnAmber

private val DAY_LABELS = listOf("Monday", "Tuesday", "Wednesday", "Thursday",
                                 "Friday", "Saturday", "Sunday")

@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val vm: ScheduleViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var pendingDay by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<ScheduleEvent?>(null) }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Schedule",
            subtitle = "Multiple on/off times per day",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MasterToggleCard(
                    enabled       = ui.enabled,
                    onToggle      = vm::setEnabled,
                    eventCount    = ui.schedule.events.size,
                )
            }
            item { StatusCard(ui.status) }
            item { ClearHeaterCard(onClick = vm::clearHeaterNow) }

            items((0..6).toList()) { day ->
                DaySection(
                    day        = day,
                    events     = ui.schedule.eventsOnDay(day),
                    onAdd      = { pendingDay = day },
                    onEdit     = { editing = it },
                    onDelete   = { vm.removeEvent(it.id) },
                    onToggle   = { vm.toggleEnabled(it) },
                )
            }

            item { Spacer() }
        }
    }

    // Add-event dialog: pick start time, then end time.
    pendingDay?.let { day ->
        TwoStepTimePicker(
            title = "New event — ${DAY_LABELS[day]}",
            onConfirm = { onMin, offMin ->
                vm.addEvent(day, onMin, offMin)
                pendingDay = null
            },
            onDismiss = { pendingDay = null },
        )
    }

    // Edit-event dialog.
    editing?.let { e ->
        TwoStepTimePicker(
            title = "Edit event — ${DAY_LABELS[e.dayOfWeek]}",
            initialOnMinute  = e.onMinuteOfDay,
            initialOffMinute = e.offMinuteOfDay,
            onConfirm = { onMin, offMin ->
                vm.updateEvent(e.copy(
                    onMinuteOfDay  = onMin,
                    offMinuteOfDay = offMin,
                ))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

// --- Top cards -------------------------------------------------------

@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    eventCount: Int,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Schedule mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = "App-driven scheduler. Pushes the next event into " +
                            "the heater's per-day slot as events fire. " +
                            "$eventCount event${if (eventCount == 1) "" else "s"} defined.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatusCard(status: ScheduleStatus) {
    val (label, detail, accent) = when (status) {
        is ScheduleStatus.Disabled       ->
            Triple("Disabled", "Turn on schedule mode above to start pushing events.", MaterialTheme.colorScheme.onSurfaceVariant)
        is ScheduleStatus.WaitingForLink ->
            Triple("Waiting for heater", "${status.eventCount} event(s) ready — will sync when connection comes up.", WarnAmber)
        is ScheduleStatus.WriteFailed    ->
            Triple("Last write failed", "Will retry on the next minute tick.", MaterialTheme.colorScheme.error)
        is ScheduleStatus.Synced         ->
            Triple(
                "Synced",
                status.nextEventSummary?.let { "Next: $it" }
                    ?: "No upcoming events programmed.",
                OkGreen,
            )
    }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            Text(
                text  = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClearHeaterCard(onClick: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text  = "Clear heater schedule",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "Pushes an all-off schedule to the heater. Use this if the " +
                        "heater shows stale times after turning Schedule mode off, " +
                        "or if a write got out of sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirming = true }) {
                Text("Clear heater now")
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear heater schedule?") },
            text  = {
                Text("This pushes an all-off table to the heater's 7-day slots. " +
                     "Your app-side events are kept and will be re-pushed if " +
                     "Schedule mode is enabled.")
            },
            confirmButton = {
                Button(onClick = { confirming = false; onClick() }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

// --- Day sections ----------------------------------------------------

@Composable
private fun DaySection(
    day: Int,
    events: List<ScheduleEvent>,
    onAdd: () -> Unit,
    onEdit: (ScheduleEvent) -> Unit,
    onDelete: (ScheduleEvent) -> Unit,
    onToggle: (ScheduleEvent) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = DAY_LABELS[day],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add event",
                    modifier = Modifier.size(16.dp).padding(end = 4.dp))
                Text("Add")
            }
        }
        if (events.isEmpty()) {
            Text(
                text  = "No events.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.forEach { e ->
                EventRow(
                    event    = e,
                    onClick  = { onEdit(e) },
                    onToggle = { onToggle(e) },
                    onDelete = { onDelete(e) },
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: ScheduleEvent,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (event.enabled) MaterialTheme.colorScheme.surfaceVariant
                else                MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = "%02d:%02d → %02d:%02d".format(
                event.onHour, event.onMin, event.offHour, event.offMin
            ),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (event.enabled) MaterialTheme.colorScheme.onSurface
                    else                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = event.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete,
                 contentDescription = "Delete",
                 tint = MaterialTheme.colorScheme.error)
        }
    }
}

// --- Two-step time picker --------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TwoStepTimePicker(
    title: String,
    initialOnMinute:  Int = 6 * 60,
    initialOffMinute: Int = 8 * 60,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var onMin by remember { mutableStateOf(initialOnMinute) }
    val onState  = rememberTimePickerState(
        initialHour   = initialOnMinute / 60,
        initialMinute = initialOnMinute % 60,
        is24Hour      = true,
    )
    val offState = rememberTimePickerState(
        initialHour   = initialOffMinute / 60,
        initialMinute = initialOffMinute % 60,
        is24Hour      = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "$title — start" else "$title — end") },
        text = {
            if (step == 0) {
                TimePicker(state = onState)
            } else {
                TimePicker(state = offState)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (step == 0) {
                    onMin = onState.hour * 60 + onState.minute
                    step = 1
                } else {
                    val offMin = offState.hour * 60 + offState.minute
                    if (offMin > onMin) onConfirm(onMin, offMin) else onDismiss()
                }
            }) {
                Text(if (step == 0) "Next" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// --- Reusable card chrome --------------------------------------------

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

// Compose's own ColumnScope is awkward to import from material3 alone; alias.
private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Spacer() {
    Box(modifier = Modifier.size(0.dp, 16.dp))
}
