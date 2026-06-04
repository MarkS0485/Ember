package uk.co.twinscrollgridbalancer.tsgbheater.ui.diag

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.twinscrollgridbalancer.tsgbheater.diag.BleEventLog

// On-device view of the BLE diagnostic log (BleEventLog). Lets the user
// read the TX/RX/STATE/BTN stream and Share it as text — the bug-report
// mechanism that works without a PC, in shipped builds too.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleLogScreen(onBack: () -> Unit) {
    val entries by BleEventLog.entries.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to the newest line as it streams in (only when already at
    // or near the bottom would be nicer, but always-scroll is fine for a log).
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Log (${entries.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "TSGB Heater — BLE diagnostic log")
                            putExtra(Intent.EXTRA_TEXT, BleEventLog.exportText())
                        }
                        context.startActivity(Intent.createChooser(send, "Share BLE log"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { BleEventLog.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                Text(
                    "No events yet. Connect to the heater and use the controls; " +
                        "TX / RX / STATE / button lines will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(entries) { e ->
                    Text(
                        text = BleEventLog.format(e),
                        color = colorFor(e.level, e.category),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun colorFor(level: BleEventLog.Level, category: String): Color = when (level) {
    BleEventLog.Level.ERROR -> MaterialTheme.colorScheme.error
    BleEventLog.Level.WARN  -> Color(0xFFB26A00) // amber
    BleEventLog.Level.INFO  -> when (category) {
        "BTN"   -> MaterialTheme.colorScheme.primary
        "STATE" -> Color(0xFF1B7F3B) // green — decoded device state
        "AUTH"  -> Color(0xFF6A1B9A) // purple
        else    -> MaterialTheme.colorScheme.onSurface
    }
}
