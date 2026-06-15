package com.emberheat.ui.advance

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import com.emberheat.ui.components.BrandTopBar

@OptIn(FlowPreview::class)
@Composable
fun AdvanceScreen(onBack: () -> Unit) {
    val vm: AdvanceViewModel = viewModel()
    val t by vm.telemetry.collectAsState()

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Advanced controls",
            subtitle = "Target · hysteresis (°C)",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SliderCard(
                    title    = "Target temperature",
                    subtitle = "Heater aims for this when in Auto or Start-Stop mode.",
                    value    = (t?.targetTempC?.toInt() ?: 22).coerceIn(10, 40),
                    unit     = "°C",
                    range    = 10..40,
                    onCommit = vm::setTargetTempC,
                )
            }
            item {
                SliderCard(
                    title    = "Start-Stop hysteresis",
                    subtitle = "How far above the target the heater can drift before it " +
                               "cuts out in Start-Stop mode.",
                    value    = 5,
                    unit     = "°C",
                    range    = 3..15,
                    onCommit = vm::setHysteresisC,
                )
            }
            item {
                Text(
                    text  = "Sliders commit ~400 ms after you stop moving so the BLE " +
                            "link isn't flooded with intermediate values.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun SliderCard(
    title: String,
    subtitle: String,
    value: Int,
    unit: String,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    var localValue by remember { mutableStateOf(value.toFloat()) }

    // Debounce commits so a drag across the range doesn't ship 30 frames.
    LaunchedEffect(Unit) {
        snapshotFlow { localValue }
            .drop(1)
            .debounce(400)
            .collect { onCommit(it.toInt()) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text  = "${localValue.toInt()} $unit",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Slider(
            value         = localValue,
            onValueChange = { localValue = it },
            valueRange    = range.first.toFloat()..range.last.toFloat(),
            steps         = (range.last - range.first - 1).coerceAtLeast(0),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onCommit(localValue.toInt()) }) { Text("Send now") }
        }
    }
}
