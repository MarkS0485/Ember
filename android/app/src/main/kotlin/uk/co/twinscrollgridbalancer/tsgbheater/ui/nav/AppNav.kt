package uk.co.twinscrollgridbalancer.tsgbheater.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppMode
import uk.co.twinscrollgridbalancer.tsgbheater.data.store.AppModeStore
import uk.co.twinscrollgridbalancer.tsgbheater.ui.advance.AdvanceScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.autostartstop.AutoStartStopScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.ble.BleListScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.device.DeviceScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.fuel.FuelScreen
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
import uk.co.twinscrollgridbalancer.tsgbheater.ui.diag.BleLogScreen
import uk.co.twinscrollgridbalancer.tsgbheater.di.ServiceLocator
import uk.co.twinscrollgridbalancer.tsgbheater.ui.onboarding.ModePickerScreen
import uk.co.twinscrollgridbalancer.tsgbheater.ui.pro.ProScreen
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
    const val ME_BLELOG     = "me/blelog"
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
    const val ME_FUEL       = "me/fuel"
    const val ME_PRO        = "me/pro"
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

// Top-level shell. Three states:
//   - mode not yet loaded from DataStore → blank scrim (one frame)
//   - mode == UNSET (first launch, or user picked "Switch Mode")
//     → ModePickerScreen
//   - mode == LOCAL  → 3-tab nav (Scan / Device / Me) over BLE
//   - mode == REMOTE → standalone Remote pair → control flow
//
// We do a synchronous first-emission read in LaunchedEffect to avoid
// flashing the picker for users who already chose a mode on a previous
// launch. Once `loaded` flips true, we follow the live Flow.
@Composable
fun AppNav() {
    val ctx   = LocalContext.current.applicationContext
    val store = remember { AppModeStore(ctx) }

    var loaded by remember { mutableStateOf(false) }
    val mode by store.mode.collectAsState(initial = AppMode.UNSET)

    LaunchedEffect(Unit) {
        // Drain at least one emission before showing the picker. If the
        // user has already picked, that emission is LOCAL/REMOTE and the
        // picker never flashes. If they haven't, it's UNSET and the
        // picker shows immediately.
        store.mode.first()
        loaded = true
    }

    if (!loaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    when (mode) {
        AppMode.UNSET ->
            ModePickerScreen(
                current  = AppMode.UNSET,
                onPicked = { /* persistence happens inside the picker */ },
            )
        AppMode.LOCAL  -> LocalNav(store)
        AppMode.REMOTE -> RemoteNav(store)
    }
}

@Composable
private fun LocalNav(store: AppModeStore) {
    val nav   = rememberNavController()
    val scope = rememberCoroutineScope()
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
            addTabRoutes(
                nav          = nav,
                onSwitchMode = { scope.launch { store.setMode(AppMode.UNSET) } },
            )
            addSubRoutes(nav)
        }
    }
}

@Composable
private fun RemoteNav(store: AppModeStore) {
    val nav   = rememberNavController()
    val scope = rememberCoroutineScope()

    // Remote control is a Pro feature. A non-Pro user who picked Remote mode
    // is funnelled to the Pro screen; "back" returns them to the mode picker
    // so they can choose Local instead.
    val proActive by ServiceLocator.entitlements.isProActive.collectAsState()
    if (!proActive) {
        ProScreen(onBack = { scope.launch { store.setMode(AppMode.UNSET) } })
        return
    }

    Scaffold(
        containerColor      = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        NavHost(
            navController    = nav,
            // Remote mode skips Scan/Device/Me — the only flow that
            // matters here is "pick a paired server, drive it remotely".
            startDestination = Routes.ME_REMOTE,
            modifier         = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.ME_REMOTE) {
                RemotePairScreen(
                    onBack         = null,    // top-level in remote mode
                    onOpenControl  = { id -> nav.navigate(Routes.remoteControl(id)) },
                    onSwitchMode   = { scope.launch { store.setMode(AppMode.UNSET) } },
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

private fun NavGraphBuilder.addTabRoutes(
    nav: NavHostController,
    onSwitchMode: () -> Unit,
) {
    composable(Routes.SCAN) {
        ScanScreen(
            onOpenBleList = { nav.navigate(Routes.BLE_LIST) },
        )
    }
    composable(Routes.DEVICE) {
        val devMode by ServiceLocator.settings.developerMode.collectAsState(initial = false)
        DeviceScreen(
            onOpenAdvance = { nav.navigate(Routes.ADVANCE) },
            onOpenTimer   = { nav.navigate(Routes.ME_TIMER) },
            onOpenTest    = { nav.navigate(Routes.TEST) },
            showTest      = devMode,
        )
    }
    composable(Routes.ME) {
        val proActive by ServiceLocator.entitlements.isProActive.collectAsState()
        val devMode by ServiceLocator.settings.developerMode.collectAsState(initial = false)
        MeScreen(
            proActive           = proActive,
            onOpenPro           = { nav.navigate(Routes.ME_PRO) },
            onOpenAbout         = { nav.navigate(Routes.ME_ABOUT) },
            onOpenBind          = { nav.navigate(Routes.ME_BIND) },
            onOpenGroups        = { nav.navigate(Routes.ME_GROUPS) },
            onOpenDebug         = { nav.navigate(Routes.ME_DEBUG_BOX) },
            onOpenBleLog        = { nav.navigate(Routes.ME_BLELOG) },
            onOpenFlag          = { nav.navigate(Routes.ME_FLAG) },
            onOpenMenu          = { nav.navigate(Routes.ME_MENU) },
            onOpenSwitch        = { nav.navigate(Routes.ME_SWITCH) },
            onOpenTestCmd       = { nav.navigate(Routes.ME_TEST_CMD) },
            onOpenTimer         = { nav.navigate(Routes.ME_TIMER) },
            onOpenAutoStartStop = { nav.navigate(Routes.ME_AUTOSTART) },
            onOpenAltProbe      = { nav.navigate(Routes.ME_ALT_PROBE) },
            onOpenSchedule      = { nav.navigate(Routes.ME_SCHEDULE) },
            onOpenRemote        = { nav.navigate(Routes.ME_REMOTE) },
            onOpenFuel          = { nav.navigate(Routes.ME_FUEL) },
            onSwitchMode        = onSwitchMode,
            developerMode       = devMode,
        )
    }
}

private fun NavGraphBuilder.addSubRoutes(nav: NavHostController) {
    composable(Routes.BLE_LIST)     { BleListScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ADVANCE)      { AdvanceScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_ABOUT)     { AboutScreen(onBack   = { nav.popBackStack() }) }
    composable(Routes.ME_BIND)      { BindScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_DEBUG_BOX) { DebugBoxScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_BLELOG)    { BleLogScreen(onBack  = { nav.popBackStack() }) }
    composable(Routes.ME_FLAG)      { FlagScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_MENU)      { MenuScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_SWITCH)    { SwitchScreen(onBack  = { nav.popBackStack() }) }
    composable(Routes.ME_TEST_CMD)  { TestCmdScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_TIMER)     { TimerScreen(onBack   = { nav.popBackStack() }) }
    composable(Routes.ME_AUTOSTART) { AutoStartStopScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_ALT_PROBE) { AltitudeProbeScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ME_SCHEDULE)  { ScheduleScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_FUEL)      { FuelScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_PRO)       { ProScreen(onBack    = { nav.popBackStack() }) }
    composable(Routes.ME_REMOTE) {
        // Local-mode entry to the pair screen: provide a Back button so
        // the user can return to the Me tab. Switch-mode is hidden here
        // (the Me tab already has its own "Switch mode" affordance).
        RemotePairScreen(
            onBack         = { nav.popBackStack() },
            onOpenControl  = { id -> nav.navigate(Routes.remoteControl(id)) },
            onSwitchMode   = null,
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
