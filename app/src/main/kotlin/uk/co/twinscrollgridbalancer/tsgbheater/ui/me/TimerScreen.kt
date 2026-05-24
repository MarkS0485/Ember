package uk.co.twinscrollgridbalancer.tsgbheater.ui.me

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.twinscrollgridbalancer.tsgbheater.ble.TimerMode
import uk.co.twinscrollgridbalancer.tsgbheater.ble.TimerSlot
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.OkGreen

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

@Composable
fun TimerScreen(onBack: () -> Unit) {
    val vm: TimerViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Schedule",
            subtitle = if (ui.slots.isEmpty()) "Tap refresh to read from heater"
                       else                    "Read at ${formatTimeOfDay(ui.lastUpdatedMs)}",
            onBack   = onBack,
            actions = {
                if (ui.refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 12.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.slots.isEmpty()) {
                item { EmptyHint() }
            } else {
                items(ui.slots, key = { it.dayIndex }) { SlotRow(it) }
                item { Footer() }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text  = "Read the schedule from the heater",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text  = "Tap the refresh icon top-right while connected. The controller " +
                    "stores seven on/off slots — one per weekday — and this screen will " +
                    "render them once the read completes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlotRow(s: TimerSlot) {
    val active = s.mode != TimerMode.Off
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
        Text(
            text  = WEEKDAYS[s.dayIndex],
            style = MaterialTheme.typography.titleMedium,
            color = if (active) OkGreen else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 4.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            if (active) {
                Text(
                    text  = "On  ${s.onLabel}    Off  ${s.offLabel}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text  = "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text  = "${s.mode.label} (raw mode ${s.modeRaw})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Footer() {
    Text(
        text  = "Editing the schedule from this app isn't supported yet — the vendor " +
                "app's write path for the timer area wasn't traced in the protocol pass " +
                "(see docs/BLE_PROTOCOL.md open questions). Reads work today.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(8.dp),
    )
}

private fun formatTimeOfDay(ms: Long?): String {
    if (ms == null) return "—"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        cal.get(java.util.Calendar.SECOND),
    )
}
