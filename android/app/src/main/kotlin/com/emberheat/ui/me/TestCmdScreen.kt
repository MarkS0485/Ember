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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.emberheat.ble.FrameCodec
import com.emberheat.di.ServiceLocator
import com.emberheat.ui.components.BrandTopBar

@Composable
fun TestCmdScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hex by remember { mutableStateOf("AA 55 02 01 01 04") }
    var lastResult by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Test commands",
            subtitle = "Raw BLE sender — frames also appear in Debug box",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { hex = it },
                        label = { Text("Hex bytes") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val bytes = runCatching { FrameCodec.fromHex(hex) }.getOrNull()
                                lastResult = if (bytes == null) "Bad hex"
                                else if (ServiceLocator.ble.sendRaw(bytes)) "Sent ${bytes.size} B"
                                else "Send failed (not connected?)"
                            }
                        }) { Text("Send") }
                    }
                    lastResult?.let {
                        Text(
                            text  = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    "Whitespace, colons, and commas are stripped before parsing. Two hex " +
                    "chars per byte. The frame is sent verbatim — no checksum is appended.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
