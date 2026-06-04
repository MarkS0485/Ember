package uk.co.twinscrollgridbalancer.tsgbheater.ui.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.twinscrollgridbalancer.tsgbheater.ble.ConnectionState
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FaultCodes
import uk.co.twinscrollgridbalancer.tsgbheater.ble.FrameCodec
import uk.co.twinscrollgridbalancer.tsgbheater.ble.HeaterTelemetry
import uk.co.twinscrollgridbalancer.tsgbheater.ble.RunningMode
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.FuelCard
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.StatusKind
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.StatusPill
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.CoolBlue
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ErrRed
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FlameOrange
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.OkGreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbNavy

// Primary screen — thermostat-style control panel. The Run-Mode selector
// at the top decides whether the control beneath it is a target-temp
// stepper (Auto / Start-Stop) or a power-level segmented (Manual). This
// mirrors the vendor app, where temp and gear are EITHER/OR, not both.
@Composable
fun DeviceScreen(
    onOpenAdvance: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenTest: () -> Unit,
    // Heater test mode is diagnostic tooling — its entry icon only appears
    // when developer mode is unlocked (About → tap version 7×).
    showTest: Boolean = false,
) {
    val vm: DeviceViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val t  = ui.telemetry
    val ready = ui.connectionState == ConnectionState.Ready

    Column(modifier = Modifier) {
        BrandTopBar(
            title    = ui.current?.name ?: "Heater",
            subtitle = ui.current?.mac ?: "No heater bound — pair on the Scan tab",
            actions = {
                IconButton(onClick = onOpenAdvance) {
                    Icon(Icons.Filled.Tune, contentDescription = "Advanced", tint = Color.White)
                }
                IconButton(onClick = onOpenTimer) {
                    Icon(Icons.Filled.Schedule, contentDescription = "Schedule", tint = Color.White)
                }
                if (showTest) {
                    IconButton(onClick = onOpenTest) {
                        Icon(Icons.Filled.Science, contentDescription = "Test", tint = Color.White)
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StatusRow(state = ui.connectionState, hasDevice = ui.current != null,
                onReconnect = vm::reconnect, onDisconnect = vm::disconnect) }

            item { HeroCard(telemetry = t) }

            item {
                RunModeSelector(
                    selected = ui.selectedMode,
                    enabled  = ready,
                    onPick   = vm::selectRunMode,
                )
            }

            // Either-or: target stepper (Auto / Start-Stop) OR gear segmented (Manual).
            item {
                when (ui.selectedMode) {
                    FrameCodec.RunMode.Manual ->
                        GearSegmented(
                            currentGear = t?.aimGear,
                            enabled     = ready,
                            onPick      = vm::setGear,
                        )
                    FrameCodec.RunMode.Auto,
                    FrameCodec.RunMode.StartStop ->
                        TargetStepperCard(
                            targetC = t?.targetTempC?.toInt(),
                            enabled = ready,
                            onMinus = { vm.nudgeTargetTemp(-1) },
                            onPlus  = { vm.nudgeTargetTemp(+1) },
                        )
                }
            }

            item {
                ActionRow(
                    runningMode = t?.runningMode,
                    enabled     = ready,
                    onHeat      = vm::start,
                    onVent      = vm::ventilate,
                    onStop      = vm::stop,
                )
            }

            val activeFaults = FaultCodes.active(t?.faultBits ?: 0)
            if (activeFaults.isNotEmpty()) {
                item { FaultBanner(active = activeFaults.size, firstCode = activeFaults.first().code) }
            }

            // Fuel card. Shows the tank level estimate, hours remaining
            // at the live gear, alert banner if level is low/critical,
            // and lets the user refill or tweak per-heater config.
            ui.fuel?.let { fuel ->
                item {
                    FuelCard(
                        snapshot = fuel,
                        currentGear = t?.aimGear ?: 5,
                        onRefill = vm::refillFuel,
                        onSaveConfig = vm::updateFuelConfig,
                    )
                }
            }

            item { CompactStats(t) }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// --- Connection chip + reconnect button --------------------------------

@Composable
private fun StatusRow(
    state: ConnectionState,
    hasDevice: Boolean,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (label, kind) = when (state) {
        ConnectionState.Ready                -> "Connected"           to StatusKind.Online
        ConnectionState.Connecting,
        ConnectionState.DiscoveringServices,
        ConnectionState.Reconnecting,
        ConnectionState.Scanning             -> state.name            to StatusKind.Stale
        ConnectionState.Failed               -> "Connection failed"   to StatusKind.Offline
        ConnectionState.Idle                 -> "Disconnected"        to StatusKind.Offline
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(label = label, kind = kind)
        if (hasDevice) {
            when (state) {
                ConnectionState.Ready -> OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                ConnectionState.Idle,
                ConnectionState.Failed -> OutlinedButton(onClick = onReconnect) {
                    Icon(Icons.Filled.Refresh, contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp))
                    Text("Connect")
                }
                else -> { /* transitional */ }
            }
        }
    }
}

// --- Big hero readout --------------------------------------------------

@Composable
private fun HeroCard(telemetry: HeaterTelemetry?) {
    val ambient   = telemetry?.ambientTempC
    val running   = telemetry?.runningMode ?: RunningMode.Unknown
    val accent    = stateAccent(running)
    val showFlame = running == RunningMode.AutoRun || running == RunningMode.ManualRun ||
                    running == RunningMode.Ignition || running == RunningMode.StartStopActive

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface,
                    1f to MaterialTheme.colorScheme.surfaceVariant,
                )
            )
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (showFlame) Icons.Filled.Whatshot else Icons.Filled.Air,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text  = running.label,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text  = ambient?.let { "%.1f".format(it) } ?: "—",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 92.sp,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = "°C",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 18.dp, start = 4.dp),
            )
        }
        Text(
            text  = "Ambient · cabin air",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Run-mode segmented selector ---------------------------------------

@Composable
private fun RunModeSelector(
    selected: FrameCodec.RunMode,
    enabled: Boolean,
    onPick: (FrameCodec.RunMode) -> Unit,
) {
    val items = listOf(
        Triple(FrameCodec.RunMode.Auto,      "Auto",       "Target temp"),
        Triple(FrameCodec.RunMode.Manual,    "Manual",     "Power level"),
        Triple(FrameCodec.RunMode.StartStop, "Start-Stop", "Target + hysteresis"),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()) {
            items.forEach { (mode, label, _) ->
                val isSel = mode == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSel) MaterialTheme.colorScheme.secondary
                                    else        Color.Transparent)
                        .clickable(enabled = enabled) { onPick(mode) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface)
                    Text(items.first { it.first == mode }.third,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) Color.White.copy(alpha = 0.8f)
                                else        MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- Target stepper ----------------------------------------------------

@Composable
private fun TargetStepperCard(
    targetC: Int?,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus:  () -> Unit,
) {
    val displayedTarget = targetC?.coerceIn(DeviceViewModel.TARGET_MIN_C, DeviceViewModel.TARGET_MAX_C)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "TARGET",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = "${DeviceViewModel.TARGET_MIN_C} – ${DeviceViewModel.TARGET_MAX_C} °C",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(
                icon    = Icons.Filled.Remove,
                enabled = enabled && (displayedTarget == null || displayedTarget > DeviceViewModel.TARGET_MIN_C),
                onClick = onMinus,
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text  = displayedTarget?.toString() ?: "—",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text  = "°C",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            StepperButton(
                icon    = Icons.Filled.Add,
                enabled = enabled && (displayedTarget == null || displayedTarget < DeviceViewModel.TARGET_MAX_C),
                onClick = onPlus,
            )
        }
    }
}

@Composable
private fun StepperButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else         MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                   else         MaterialTheme.colorScheme.outline,
        )
    }
}

// --- 1-10 segmented gear -----------------------------------------------

@Composable
private fun GearSegmented(
    currentGear: Int?,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "POWER LEVEL",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = currentGear?.let { "gear $it" } ?: "—",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            (DeviceViewModel.GEAR_MIN..DeviceViewModel.GEAR_MAX).forEach { g ->
                GearCell(
                    label    = g.toString(),
                    selected = currentGear == g,
                    enabled  = enabled,
                    onClick  = { onPick(g) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GearCell(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.secondary
        else     -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        selected -> Color.White
        enabled  -> MaterialTheme.colorScheme.onSurface
        else     -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            color = fg,
        )
    }
}

// --- Action row: Heat / Vent / Stop ------------------------------------

@Composable
private fun ActionRow(
    runningMode: RunningMode?,
    enabled: Boolean,
    onHeat: () -> Unit,
    onVent: () -> Unit,
    onStop: () -> Unit,
) {
    val activeAction: Action = when (runningMode) {
        RunningMode.Ignition, RunningMode.AutoRun, RunningMode.ManualRun,
        RunningMode.StartStopActive                                       -> Action.Heat
        RunningMode.Ventilation                                           -> Action.Vent
        else                                                              -> Action.Stop
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()) {
        ActionButton("Heat",       Icons.Filled.Whatshot,        ErrRed,       activeAction == Action.Heat, enabled, Modifier.weight(1f), onHeat)
        ActionButton("Ventilate",  Icons.Filled.Air,             CoolBlue,     activeAction == Action.Vent, enabled, Modifier.weight(1f), onVent)
        ActionButton("Stop",       Icons.Filled.PowerSettingsNew, MaterialTheme.colorScheme.outline,
                                                                              activeAction == Action.Stop, enabled, Modifier.weight(1f), onStop)
    }
}

private enum class Action { Heat, Vent, Stop }

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) accent else MaterialTheme.colorScheme.surface
    val fg = if (selected) Color.White
             else if (enabled) MaterialTheme.colorScheme.onSurface
             else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(BorderStroke(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

// --- Fault banner ------------------------------------------------------

@Composable
private fun FaultBanner(active: Int, firstCode: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text  = if (active == 1) "FAULT $firstCode" else "$active FAULTS ACTIVE",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text  = "See Flags screen for detail",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f),
        )
    }
}

// --- Compact secondary stats strip -------------------------------------

@Composable
private fun CompactStats(t: HeaterTelemetry?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = "LIVE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatLine("Outlet",    fmt(t?.outletTempC),    "°C")
        StatLine("Housing",   fmt(t?.housingTempC),   "°C")
        StatLine("Intake",    fmt(t?.intakeTempC),    "°C")
        StatLine("Fuel pump", fmt(t?.fuelPumpHz),     "Hz")
        StatLine("Fan",       t?.fanRpm?.toString()  ?: "—", "rpm")
        StatLine("Ignition",  fmt(t?.ignitionWatts), "W")
        StatLine("Battery",   fmt(t?.batteryV),      "V")
        StatLine("Altitude",  t?.altitudeM?.toString() ?: "—", "m")
    }
}

@Composable
private fun StatLine(label: String, value: String, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text  = unit,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
    }
}

// --- Helpers -----------------------------------------------------------

@Composable
private fun stateAccent(mode: RunningMode): Color = when (mode) {
    RunningMode.Ignition,
    RunningMode.AutoRun,
    RunningMode.ManualRun,
    RunningMode.StartStopActive          -> FlameOrange
    RunningMode.Cooldown,
    RunningMode.Ventilation              -> CoolBlue
    RunningMode.Standby,
    RunningMode.StartStopConfig          -> OkGreen
    RunningMode.Fault                    -> ErrRed
    RunningMode.Boot,
    RunningMode.ManualPump,
    RunningMode.Unknown                  -> TsgbNavy
}

private fun fmt(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"
