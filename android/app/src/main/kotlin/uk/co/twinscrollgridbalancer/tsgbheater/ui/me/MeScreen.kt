package uk.co.twinscrollgridbalancer.tsgbheater.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.BrandTopBar
import uk.co.twinscrollgridbalancer.tsgbheater.ui.components.NavListItem
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.CoolBlue
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FlameOrange
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.FuelAmber
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.ProbeSlate
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbNavy
import uk.co.twinscrollgridbalancer.tsgbheater.ui.theme.TsgbRed

// Third tab — the catch-all for everything that isn't live operation.
// HeatGenie surfaces this as the user/account page; we treat it as the
// "settings + utilities" hub so a fresh install can explore every screen
// without binding a heater first.
@Composable
fun MeScreen(
    proActive: Boolean,
    onOpenPro: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBind: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenBleLog: () -> Unit,
    onOpenFlag: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSwitch: () -> Unit,
    onOpenTestCmd: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenAutoStartStop: () -> Unit,
    onOpenAltProbe: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenFuel: () -> Unit,
    onSwitchMode: () -> Unit,
    // Hidden until the user unlocks developer mode (About → tap version 7×).
    // Gates the diagnostic tooling so a normal install never sees it.
    developerMode: Boolean = false,
) {
    Column(modifier = Modifier) {
        BrandTopBar(
            title    = "Account",
            subtitle = "Settings · diagnostics · about",
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // First entry: the Pro hub. No popups anywhere else in the app —
            // everything monetisation-related lives behind this one tap. Shows
            // the active state once unlocked, otherwise an "Unlock" prompt.
            item {
                NavListItem(
                    icon     = Icons.Filled.WorkspacePremium,
                    title    = "TSGB Heater Pro",
                    subtitle = if (proActive) "Pro features unlocked — thank you"
                               else "Unlock remote, schedules, groups & more",
                    accent   = FuelAmber,
                    onClick  = onOpenPro,
                    badge    = if (proActive) null else "PRO",
                )
            }
            // Switch operating mode. Surfaces this near the top because users
            // in Local mode often want to flip to Remote (e.g. they've left
            // the van) and burying it deeper makes that flow feel harder.
            item {
                NavListItem(
                    icon     = Icons.Filled.SwapHoriz,
                    title    = "Switch mode",
                    subtitle = "Currently Local (Bluetooth). Tap to switch to Remote.",
                    accent   = CoolBlue,
                    onClick  = onSwitchMode,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Link,
                    title    = "Bound devices",
                    subtitle = "Manage the heaters paired to this phone",
                    accent   = TsgbNavy,
                    onClick  = onOpenBind,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Laptop,
                    title    = "Remote (laptop API)",
                    subtitle = "Talk to a Windows laptop running the API server",
                    accent   = CoolBlue,
                    onClick  = { if (proActive) onOpenRemote() else onOpenPro() },
                    badge    = if (proActive) null else "PRO",
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Groups,
                    title    = "Groups",
                    subtitle = "Control multiple heaters as one",
                    accent   = FuelAmber,
                    onClick  = { if (proActive) onOpenGroups() else onOpenPro() },
                    badge    = if (proActive) null else "PRO",
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.AutoMode,
                    title    = "Auto Start / Stop",
                    subtitle = "Phone decides when the heater runs",
                    accent   = TsgbRed,
                    onClick  = { if (proActive) onOpenAutoStartStop() else onOpenPro() },
                    badge    = if (proActive) null else "PRO",
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.CalendarMonth,
                    title    = "Schedule",
                    subtitle = "Multiple on/off times per day — app driven",
                    accent   = FlameOrange,
                    onClick  = { if (proActive) onOpenSchedule() else onOpenPro() },
                    badge    = if (proActive) null else "PRO",
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.LocalGasStation,
                    title    = "Fuel tracking",
                    subtitle = "Estimate tank level, hours left & low-fuel auto-stop",
                    accent   = FuelAmber,
                    onClick  = { if (proActive) onOpenFuel() else onOpenPro() },
                    badge    = if (proActive) null else "PRO",
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Schedule,
                    title    = "Heater timer (read-only)",
                    subtitle = "What's currently programmed on the controller",
                    accent   = FlameOrange,
                    onClick  = onOpenTimer,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.ToggleOn,
                    title    = "Switches",
                    subtitle = "Auto-restart, fault lockout, child-lock",
                    accent   = CoolBlue,
                    onClick  = onOpenSwitch,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Flag,
                    title    = "Active flags",
                    subtitle = "Live warning + fault codes from the controller",
                    accent   = TsgbRed,
                    onClick  = onOpenFlag,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.AutoMirrored.Filled.MenuBook,
                    title    = "Menu reference",
                    subtitle = "Every controller menu item explained",
                    accent   = FuelAmber,
                    onClick  = onOpenMenu,
                )
            }
            // Diagnostic tooling — hidden unless developer mode is unlocked
            // (About → tap the version 7×). Not part of the normal app.
            if (developerMode) {
                item {
                    NavListItem(
                        icon     = Icons.Filled.Terminal,
                        title    = "Test commands",
                        subtitle = "Raw BLE command sender for debugging",
                        accent   = ProbeSlate,
                        onClick  = onOpenTestCmd,
                    )
                }
                item {
                    NavListItem(
                        icon     = Icons.Filled.Landscape,
                        title    = "Altitude probe",
                        subtitle = "Sweep SHORT_PARA for a hidden altitude setter",
                        accent   = ProbeSlate,
                        onClick  = onOpenAltProbe,
                    )
                }
                item {
                    NavListItem(
                        icon     = Icons.Filled.BugReport,
                        title    = "Debug box",
                        subtitle = "Live raw frame log and parser output",
                        accent   = ProbeSlate,
                        onClick  = onOpenDebug,
                    )
                }
            }
            // Visible to everyone (not dev-gated): this is the bug-report
            // mechanism for published builds — users can view and Share the
            // BLE diagnostic log without a PC.
            item {
                NavListItem(
                    icon     = Icons.Filled.BugReport,
                    title    = "BLE diagnostic log",
                    subtitle = "Connection & command trace — view and share for support",
                    accent   = ProbeSlate,
                    onClick  = onOpenBleLog,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Info,
                    title    = "About",
                    subtitle = "App version · licences · TSGB site",
                    accent   = TsgbNavy,
                    onClick  = onOpenAbout,
                )
            }
        }
    }
}
