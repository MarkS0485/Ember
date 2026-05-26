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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar

// Probe screen for finding a hidden altitude setter. The vendor protocol
// only documents SHORT_PARA indices 0..4; this screen lets the user fire
// frames at indices 5..15 and watch the heater's reported altitudeM for
// changes. Three payload encodings are offered because the parameter
// layout for unknown indices is unspecified.
@Composable
fun AltitudeProbeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val telemetry by ServiceLocator.ble.telemetry.collectAsState()

    var indexIdx by remember { mutableStateOf(0) }     // pointer into INDEX_OPTIONS
    var encodingIdx by remember { mutableStateOf(1) }  // pointer into ENCODING_OPTIONS
    var altitudeMText by remember { mutableStateOf("244") }   // ~800 ft default
    var lastFrameHex by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    val altitudeM = altitudeMText.toIntOrNull() ?: 0
    val index = INDEX_OPTIONS[indexIdx]
    val encoding = ENCODING_OPTIONS[encodingIdx]
    val (d1, d2) = encoding.encode(altitudeM)
    val previewBytes = FrameCodec.buildShortParaProbe(index, d1, d2)

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Altitude probe",
            subtitle = "Experimental SHORT_PARA sweep",
            onBack   = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BannerCard(
                    "Vendor app uses SHORT_PARA indices 0–4 only. This screen sends frames at " +
                    "higher indices and waits to see if regInfo.altitudeM changes. Most likely " +
                    "the controller ignores them. Watch the read-back below after each Send."
                )
            }

            item {
                CardWrap("Read-back from heater") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text  = "altitudeM",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text  = telemetry?.altitudeM?.let { "$it m" } ?: "—",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        text  = "Sensor reading. Values ≥ 8000 mean sensor fault (docs).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                CardWrap("Altitude value") {
                    OutlinedTextField(
                        value = altitudeMText,
                        onValueChange = { altitudeMText = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Altitude (m)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Slider(
                        value = altitudeM.coerceIn(SLIDER_MIN_M, SLIDER_MAX_M).toFloat(),
                        onValueChange = { altitudeMText = it.toInt().toString() },
                        valueRange = SLIDER_MIN_M.toFloat()..SLIDER_MAX_M.toFloat(),
                    )
                    Text(
                        text  = "Slider range ${SLIDER_MIN_M} m … ${SLIDER_MAX_M} m " +
                                "(≈ ${(SLIDER_MIN_M * 3.281).toInt()} ft … " +
                                "${(SLIDER_MAX_M * 3.281).toInt()} ft). " +
                                "Type the box for any value.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                CardWrap("SHORT_PARA index") {
                    SegmentedChips(
                        options  = INDEX_OPTIONS.map { it.toString() },
                        selected = indexIdx,
                        onPick   = { indexIdx = it },
                    )
                    Text(
                        text  = "0–4 are vendor-defined (RunMode / Target / Gear / Timer / Diff). " +
                                "Probe higher values for a hidden altitude setter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                CardWrap("Encoding") {
                    ENCODING_OPTIONS.forEachIndexed { i, opt ->
                        EncodingRow(
                            label    = opt.label,
                            detail   = opt.detail,
                            selected = i == encodingIdx,
                            onClick  = { encodingIdx = i },
                        )
                    }
                }
            }

            item {
                CardWrap("Frame preview") {
                    Text(
                        text  = FrameCodec.hex(previewBytes),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text  = "AA len op d0 d1 d2 crcH crcL — d0=$index (index), " +
                                "d1=0x%02X d2=0x%02X".format(d1 and 0xFF, d2 and 0xFF),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = ServiceLocator.ble.sendRaw(previewBytes)
                                lastFrameHex = FrameCodec.hex(previewBytes)
                                lastResult = if (ok) "Sent. Watch altitudeM."
                                             else   "Send failed — not connected?"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Send probe frame") }
                    lastResult?.let {
                        Text(
                            text  = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    lastFrameHex?.let {
                        Text(
                            text  = "Last sent: $it",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --- Helpers --------------------------------------------------------

private val INDEX_OPTIONS = listOf(5, 6, 7, 8, 9, 10, 11, 12)

private data class EncodingOption(
    val label: String,
    val detail: String,
    val encode: (Int) -> Pair<Int, Int>,   // (d1, d2)
)

private val ENCODING_OPTIONS = listOf(
    EncodingOption(
        label  = "Single byte (d2)",
        detail = "d1 = 0, d2 = altitude (0..255). Mirrors how target-temp is packed.",
        encode = { alt -> 0 to (alt and 0xFF) },
    ),
    EncodingOption(
        label  = "uleShort (d1=lo, d2=hi)",
        detail = "d1 = altitude & 0xFF, d2 = (altitude >> 8) & 0xFF. Matches regInfo's little-endian altitude.",
        encode = { alt -> (alt and 0xFF) to ((alt shr 8) and 0xFF) },
    ),
    EncodingOption(
        label  = "ubeShort (d1=hi, d2=lo)",
        detail = "Reverse byte order — try if little-endian doesn't take.",
        encode = { alt -> ((alt shr 8) and 0xFF) to (alt and 0xFF) },
    ),
)

private const val SLIDER_MIN_M = -30      // ~-100 ft
private const val SLIDER_MAX_M = 760      // ~+2500 ft

@Composable
private fun CardWrap(title: String, content: @Composable () -> Unit) {
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
            text  = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
private fun BannerCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun SegmentedChips(
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            val bg = if (isSel) MaterialTheme.colorScheme.secondary else Color.Transparent
            val fg = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onPick(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun EncodingRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
             else          MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text  = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
