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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ToggleOn
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
    onOpenAbout: () -> Unit,
    onOpenBind: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenFlag: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSwitch: () -> Unit,
    onOpenTestCmd: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenAutoStartStop: () -> Unit,
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
                    icon     = Icons.Filled.Groups,
                    title    = "Groups",
                    subtitle = "Control multiple heaters as one",
                    accent   = FuelAmber,
                    onClick  = onOpenGroups,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.AutoMode,
                    title    = "Auto Start / Stop",
                    subtitle = "Phone decides when the heater runs",
                    accent   = TsgbRed,
                    onClick  = onOpenAutoStartStop,
                )
            }
            item {
                NavListItem(
                    icon     = Icons.Filled.Schedule,
                    title    = "Schedule",
                    subtitle = "Daily on/off and one-shot timers",
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
                    icon     = Icons.Filled.BugReport,
                    title    = "Debug box",
                    subtitle = "Live raw frame log and parser output",
                    accent   = ProbeSlate,
                    onClick  = onOpenDebug,
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
