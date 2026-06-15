package com.emberheat.ui.me

import android.content.pm.PackageInfo
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.emberheat.di.ServiceLocator
import com.emberheat.ui.components.BrandTopBar
import com.emberheat.ui.theme.ProbeSlate
import com.emberheat.ui.theme.EmberNavy
import com.emberheat.ui.theme.EmberRed

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pkg: PackageInfo = remember(ctx) {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    }
    val scope    = rememberCoroutineScope()
    val settings = remember { ServiceLocator.settings }
    val devMode by settings.developerMode.collectAsState(initial = false)
    // Tap the version 7× to reveal developer mode — the standard Android
    // easter-egg gesture, so it's invisible to normal users.
    var taps by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier) {
        BrandTopBar(
            title  = "About",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Hero(pkg) {
                    if (devMode) return@Hero
                    taps++
                    when {
                        taps >= 7 -> {
                            taps = 0
                            scope.launch { settings.setDeveloperMode(true) }
                            Toast.makeText(ctx, "Developer mode unlocked", Toast.LENGTH_SHORT).show()
                        }
                        taps >= 4 -> {
                            val left = 7 - taps
                            Toast.makeText(
                                ctx,
                                "$left more tap${if (left == 1) "" else "s"} to unlock developer mode",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            if (devMode) {
                item { DeveloperCard(onTurnOff = { scope.launch { settings.setDeveloperMode(false) } }) }
            }
            item {
                InfoCard("Package", listOf(
                    "Name"    to ctx.packageName,
                    "Version" to "${pkg.versionName} (build ${pkgLongVersion(pkg)})",
                ))
            }
            item {
                InfoCard("Hardware target", listOf(
                    "Vendor app"  to "HeatGenie (uni.UNIC97AE27, v1.1.1)",
                    "Service UUID" to "0x181A · Environmental Sensing (repurposed)",
                    "Frame"        to "AA 00 op d0 d1 d2 crcH crcL · CRC-16/XMODEM",
                    "Scan filter"  to "name == \"boygu\" or C1:XX:XX:XX:FE",
                ))
            }
            item {
                InfoCard("Build philosophy", listOf(
                    "Connectivity" to "BLE only — no cloud, no account",
                    "Storage"      to "DataStore preferences, on-device only",
                    "Service"      to "Foreground service while connected (Auto Start/Stop)",
                ))
            }
            item {
                Text(
                    "A community-built controller for heater hardware whose original " +
                    "vendor app is no longer supported. No cloud, no account — " +
                    "everything runs on your phone over Bluetooth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

// Shown on the About screen once developer mode is unlocked, so there's an
// obvious, discoverable way back out (the unlock gesture is hidden).
@Composable
private fun DeveloperCard(onTurnOff: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.DeveloperMode,
            contentDescription = null,
            tint = ProbeSlate,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Developer mode on",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "Diagnostic tools are visible in the Me tab and on the Device screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onTurnOff) { Text("Turn off") }
    }
}

@Composable
private fun Hero(pkg: PackageInfo, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = EmberRed,
            modifier = Modifier.size(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Ember",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = EmberNavy,
            )
            Text(
                text  = "v${pkg.versionName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
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
            text  = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text  = k,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.4f),
                )
                Text(
                    text  = v,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun pkgLongVersion(pkg: PackageInfo): Long =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pkg.longVersionCode
    else pkg.versionCode.toLong()
