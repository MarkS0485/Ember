package uk.co.twinscrollgridbalancer.tsgbheater.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uk.co.twinscrollgridbalancer.tsgbheater.ui.advance.AdvanceScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.autostartstop.AutoStartStopScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.ble.BleListScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.device.DeviceScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.groups.GroupCreateScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.groups.GroupDetailScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.groups.GroupsScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.AboutScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.AltitudeProbeScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.BindScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.DebugBoxScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.FlagScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.MeScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.MenuScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.SwitchScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.TestCmdScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.me.TimerScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.remote.RemoteControlScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.remote.RemotePairScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.scan.ScanScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.schedule.ScheduleScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.test.TestScreen

// Route names mirror HeatGenie's vendor app so the page-by-page rewrite has
// an obvious source mapping when the BLE backend is wired in.
object Routes {
    // Bottom-tab destinations.
    const val SCAN          = "tabs/scan"
    const val DEVICE        = "tabs/device"
    const val ME            = "tabs/me"

    // Sub-pages reachable from a tab.
    const val BLE_LIST      = "ble"
    const val ADVANCE       = "advance"
    const val ME_ABOUT      = "me/about"
    const val ME_BIND       = "me/bind"
    const val ME_DEBUG_BOX  = "me/debug"
    const val ME_FLAG       = "me/flag"
    const val ME_MENU       = "me/menu"
    const val ME_SWITCH     = "me/switch"
    const val ME_TEST_CMD   = "me/testcmd"
    const val ME_TIMER      = "me/timer"
    const val ME_AUTOSTART  = "me/autostartstop"
    const val ME_GROUPS     = "me/groups"
    const val ME_ALT_PROBE  = "me/altprobe"
    const val ME_SCHEDULE   = "me/schedule"
    const val ME_REMOTE     = "me/remote"
    const val REMOTE_CTL    = "remote/{serverId}"
    const val GROUP_CREATE  = "groups/new"
    const val GROUP_DETAIL  = "groups/{id}"
    const val TEST          = "test"

    fun groupDetail(id: String): String = "groups/$id"
    fun remoteControl(serverId: String): String = "remote/$serverId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.SCAN,   "Scan",   Icons.Filled.QrCodeScanner),
    Tab(Routes.DEVICE, "Device", Icons.Filled.LocalFireDepartment),
    Tab(Routes.ME,     "Me",     Icons.Filled.Person),
)
private val TAB_ROUTES = TABS.map { it.route }.toSet()

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val showBar = entry?.destination?.route in TAB_ROUTES

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Zero top inset so BrandTopBar's gradient can paint through the
        // status-bar area; the bottom bar handles its own inset below.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                HeaterBottomBar(nav, entry?.destination?.route)
            }
        },
    ) { padding ->
        NavHost(
            navController    = nav,
            startDestination = Routes.SCAN,
            modifier         = Modifier.fillMaxSize().padding(padding),
        ) {
            addTabRoutes(nav)
            addSubRoutes(nav)
        }
    }
}

@Composable
private fun HeaterBottomBar(nav: NavHostController, currentRoute: String?) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor   = MaterialTheme.colorScheme.onSurface,
    ) {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick  = {
                    if (currentRoute == tab.route) return@NavigationBarItem
                    nav.navigate(tab.route) {
                        popUpTo(Routes.SCAN) { saveState = true }
                        launchSingleTop = true
                        restoreState    = true
                    }
                },
                icon  = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor   = MaterialTheme.colorScheme.secondary,
                    selectedTextColor   = MaterialTheme.colorScheme.secondary,
                    indicatorColor      = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun NavGraphBuilder.addTabRoutes(nav: NavHostController) {
    composable(Routes.SCAN) {
        ScanScreen(
            onOpenBleList = { nav.navigate(Routes.BLE_LIST) },
        )
    }
    composable(Routes.DEVICE) {
        DeviceScreen(
            onOpenAdvance = { nav.navigate(Routes.ADVANCE) },
            onOpenTimer   = { nav.navigate(Routes.ME_TIMER) },
            onOpenTest    = { nav.navigate(Routes.TEST) },
        )
    }
    composable(Routes.ME) {
        MeScreen(
            onOpenAbout         = { nav.navigate(Routes.ME_ABOUT) },
            onOpenBind          = { nav.navigate(Routes.ME_BIND) },
            onOpenGroups        = { nav.navigate(Routes.ME_GROUPS) },
            onOpenDebug         = { nav.navigate(Routes.ME_DEBUG_BOX) },
            onOpenFlag          = { nav.navigate(Routes.ME_FLAG) },
            onOpenMenu          = { nav.navigate(Routes.ME_MENU) },
            onOpenSwitch        = { nav.navigate(Routes.ME_SWITCH) },
            onOpenTestCmd       = { nav.navigate(Routes.ME_TEST_CMD) },
            onOpenTimer         = { nav.navigate(Routes.ME_TIMER) },
            onOpenAutoStartStop = { nav.navigate(Routes.ME_AUTOSTART) },
            onOpenAltProbe      = { nav.navigate(Routes.ME_ALT_PROBE) },
            onOpenSchedule      = { nav.navigate(Routes.ME_SCHEDULE) },
            onOpenRemote        = { nav.navigate(Routes.ME_REMOTE) },
        )
    }
}

private fun NavGraphBuilder.addSubRoutes(nav: NavHostController) {
    composable(Routes.BLE_LIST)     { BleListScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ADVANCE)      { AdvanceScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_ABOUT)     { AboutScreen(onBack   = { nav.popBackStack() }) }
    composable(Routes.ME_BIND)      { BindScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_DEBUG_BOX) { DebugBoxScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_FLAG)      { FlagScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_MENU)      { MenuScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_SWITCH)    { SwitchScreen(onBack  = { nav.popBackStack() }) }
    composable(Routes.ME_TEST_CMD)  { TestCmdScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_TIMER)     { TimerScreen(onBack   = { nav.popBackStack() }) }
    composable(Routes.ME_AUTOSTART) { AutoStartStopScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_ALT_PROBE) { AltitudeProbeScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_SCHEDULE)  { ScheduleScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_REMOTE) {
        RemotePairScreen(
            onBack         = { nav.popBackStack() },
            onOpenControl  = { id -> nav.navigate(Routes.remoteControl(id)) },
        )
    }
    composable(
        route     = Routes.REMOTE_CTL,
        arguments = listOf(androidx.navigation.navArgument("serverId") {
            type = androidx.navigation.NavType.StringType
        }),
    ) { entry ->
        val id = entry.arguments?.getString("serverId").orEmpty()
        RemoteControlScreen(serverId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.ME_GROUPS) {
        GroupsScreen(
            onBack        = { nav.popBackStack() },
            onCreateGroup = { nav.navigate(Routes.GROUP_CREATE) },
            onOpenGroup   = { id -> nav.navigate(Routes.groupDetail(id)) },
        )
    }
    composable(Routes.GROUP_CREATE) {
        GroupCreateScreen(
            onBack    = { nav.popBackStack() },
            onCreated = { nav.popBackStack() },
        )
    }
    composable(
        route     = Routes.GROUP_DETAIL,
        arguments = listOf(androidx.navigation.navArgument("id") {
            type = androidx.navigation.NavType.StringType
        }),
    ) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        GroupDetailScreen(
            groupId   = id,
            onBack    = { nav.popBackStack() },
            onDeleted = { nav.popBackStack() },
        )
    }
    composable(Routes.TEST)         { TestScreen(onBack    = { nav.popBackStack() }) }
}
