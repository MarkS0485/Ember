package uk.co.twinscrollgridbalancer.tsgbheater.ui.remote

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.twinscrollgridbalancer.tsgbheater.remote.QrScannerActivity
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar

// `onBack` is nullable so this screen can be a top-level destination in
// Remote-mode (no back arrow) or a sub-page from the Local-mode Me tab
// (back arrow returns to Me).
// `onSwitchMode` shows a swap-horizontal action button in the top-right
// when we're a top-level destination, letting the user flip back to
// Local mode without digging through settings.
@Composable
fun RemotePairScreen(
    onBack: (() -> Unit)?,
    onOpenControl: (String) -> Unit,
    onSwitchMode: (() -> Unit)? = null,
) {
    val vm: RemotePairViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current

    val scanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val uri = res.data?.getStringExtra(QrScannerActivity.RESULT_URI_EXTRA)
            if (!uri.isNullOrBlank()) vm.acceptScannedUri(uri)
        }
    }

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Remote",
            subtitle = "Talk to a laptop running the Windows app",
            onBack   = onBack,
            actions  = if (onSwitchMode != null) {
                {
                    IconButton(onClick = onSwitchMode) {
                        Icon(
                            imageVector        = Icons.Filled.SwapHoriz,
                            contentDescription = "Switch mode",
                            tint               = Color.White,
                        )
                    }
                }
            } else null,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    Text("Pair a laptop", style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Open the Windows app, go to API server, start it, then tap " +
                        "Generate. Scan the QR with the button below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { scanLauncher.launch(Intent(ctx, QrScannerActivity::class.java)) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Scan pairing QR") }
                    ui.error?.let {
                        Text(it,
                             color = MaterialTheme.colorScheme.error,
                             style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                             modifier = Modifier.padding(top = 8.dp))
                    }
                    ui.info?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary,
                             style = MaterialTheme.typography.bodyMedium,
                             modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            item {
                // Manual paste fallback. The Windows app shows the URI text
                // under the QR — copy it and paste here if scanning gives
                // you grief. Also useful for diagnosing exactly what's in
                // the URI: errors above echo the parsed text verbatim.
                var pasted by remember { mutableStateOf("") }
                Card {
                    Text("Or paste the URI",
                         style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Paste the tsgb:// URL shown below the QR on the Windows app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("tsgb://pair?…") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Button(
                        onClick = { if (pasted.isNotBlank()) vm.acceptScannedUri(pasted) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Pair from URL") }
                }
            }

            item {
                Text("PAIRED",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }

            if (ui.paired.isEmpty()) {
                item {
                    Text("No paired laptops yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                items(ui.paired, key = { it.id }) { s ->
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (s.id == ui.currentId) "${s.label} · current" else s.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text  = s.baseUrl,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text  = "keyId ${s.keyId}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = {
                                vm.makeCurrent(s.id)
                                onOpenControl(s.id)
                            }) { Text("Open") }
                            OutlinedButton(onClick = { vm.unpair(s.id) }) { Text("Unpair") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
