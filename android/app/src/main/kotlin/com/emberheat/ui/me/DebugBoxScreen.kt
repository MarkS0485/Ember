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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emberheat.ble.FrameCodec
import com.emberheat.ble.RawFrame
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.CoolBlue
import com.emberheat.ui.theme.FlameOrange

@Composable
fun DebugBoxScreen(onBack: () -> Unit) {
    val vm: DebugBoxViewModel = viewModel()
    val frames by vm.frames.collectAsState()
    val paused by vm.paused.collectAsState()

    val listState = rememberLazyListState()
    LaunchedEffect(frames.size) {
        if (frames.isNotEmpty() && !paused) listState.animateScrollToItem(frames.lastIndex)
    }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Debug box",
            subtitle = "${frames.size} frames" + if (paused) " · paused" else "",
            onBack   = onBack,
            actions = {
                IconButton(onClick = { vm.togglePause() }) {
                    Icon(
                        imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { vm.clear() }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = Color.White)
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (frames.isEmpty()) {
                item {
                    Text(
                        "No frames yet — connect a heater and live BLE traffic will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(frames, key = { "${it.timestampMs}-${it.tx}-${it.bytes.contentHashCode()}" }) { f ->
                FrameRow(f)
            }
        }
    }
}

@Composable
private fun FrameRow(f: RawFrame) {
    val tag = if (f.tx) "TX" else "RX"
    val accent = if (f.tx) FlameOrange else CoolBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text  = tag,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = accent,
        )
        Text(
            text  = FrameCodec.hex(f.bytes),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
