package uk.co.twinscrollgridbalancer.tsgbheater.ui.me

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar

@Composable
fun SwitchScreen(onBack: () -> Unit) {
    val vm: SwitchViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Switches",
            subtitle = "Behaviour toggles",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("HEATER") }

            item { RunModeCard(currentMode = ui.telemetry?.runningMode, onPick = vm::setRunMode) }

            item {
                TempUnitCard(
                    inFahrenheit = ui.telemetry?.tempUnitFahrenheit == true,
                    onPick       = { useF -> vm.setTempUnit(useFahrenheit = useF) },
                )
            }

            item { SectionTitle("APP") }

            item {
                ToggleCard(
                    title    = "Keep BLE link alive",
                    subtitle = "Hold the foreground service connection while the app is " +
                               "in the background. Required for Auto Start/Stop.",
                    checked  = ui.keepAlive,
                    onToggle = vm::setKeepAlive,
                )
            }

            item {
                ToggleCard(
                    title    = "Auto Start / Stop master",
                    subtitle = "Lets phone-side rules drive the heater. Per-device " +
                               "toggles live on the Auto Start/Stop screen.",
                    checked  = ui.autoStartStop,
                    onToggle = vm::setAutoStartStop,
                )
            }
        }
    }
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

// Two-state segmented control for the heater's display unit. The active
// cell reflects telemetry; tapping the other cell sends the corresponding
// CMD_SWITCH_TEMP_FC / CMD_SWITCH_TEMP_CF button-event. Replaces a Material
// Switch because the Switch is a controlled component and the visual
// state would lag the user's tap until telemetry caught up.
@Composable
private fun TempUnitCard(
    inFahrenheit: Boolean,
    onPick: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                text  = "Temperature unit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "Changes how the heater's own panel reads. The app always shows " +
                        "°C; the active cell below mirrors what the heater is currently " +
                        "reporting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            UnitCell(
                label    = "°C",
                selected = !inFahrenheit,
                onClick  = { if (inFahrenheit) onPick(false) },
                modifier = Modifier.weight(1f),
            )
            UnitCell(
                label    = "°F",
                selected = inFahrenheit,
                onClick  = { if (!inFahrenheit) onPick(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UnitCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            color = fg,
        )
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    labelOn: String? = null,
    labelOff: String? = null,
) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (labelOn != null && labelOff != null) {
            Text(
                text  = if (checked) labelOn else labelOff,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun RunModeCard(
    currentMode: RunningMode?,
    onPick: (FrameCodec.RunMode) -> Unit,
) {
    val options = listOf(
        Triple(FrameCodec.RunMode.Auto,      "Auto",      "Heater chooses output to hit target temperature."),
        Triple(FrameCodec.RunMode.Manual,    "Manual",    "Fixed gear; ignores target temperature."),
        Triple(FrameCodec.RunMode.StartStop, "Start-Stop", "Heater cycles around the target with hysteresis."),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(vertical = 8.dp),
    ) {
        Text(
            text  = "Run mode",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        options.forEach { (mode, label, sub) ->
            // We can only highlight Auto/Manual based on runningMode; Start-Stop
            // is reported as states 9/10 (Active/Configured) in machineStatus.
            val isCurrent = when (mode) {
                FrameCodec.RunMode.Auto      -> currentMode == RunningMode.AutoRun
                FrameCodec.RunMode.Manual    -> currentMode == RunningMode.ManualRun
                FrameCodec.RunMode.StartStop -> currentMode == RunningMode.StartStopActive ||
                                                currentMode == RunningMode.StartStopConfig
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(mode) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioDot(selected = isCurrent)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text  = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.secondary
                else          MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = if (selected) 1f else 0.25f))
            .padding(6.dp),
    ) {
        // Empty padded dot acts as a 12dp radio indicator.
    }
}
