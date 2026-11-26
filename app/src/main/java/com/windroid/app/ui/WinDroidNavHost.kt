package com.windroid.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.windroid.app.R
import com.windroid.app.core.model.ConnectedPeripherals
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.RuntimeStatus
import com.windroid.app.data.ContainerRepository
import com.windroid.app.runtime.ImageFsManager
import com.windroid.app.ui.screens.ContainerEditorScreen
import com.windroid.app.ui.screens.ContainerListScreen
import com.windroid.app.ui.screens.InputSettingsScreen
import com.windroid.app.ui.screens.RuntimeSetupScreen

/**
 * Route table for the whole app.
 *
 * The editor is the only parameterised destination and it deliberately hides the
 * top-level chrome, so the form owns the full screen while it is open.
 */
object WinDroidDestinations {

    const val CONTAINERS: String = "containers"
    const val INPUT: String = "input"
    const val RUNTIME: String = "runtime"

    /** Navigation argument carrying the container being edited. */
    const val CONTAINER_ID_ARG: String = "containerId"

    /** Route pattern, e.g. `editor/{containerId}`. */
    const val EDITOR: String = "editor/{$CONTAINER_ID_ARG}"

    /** Builds the concrete editor route for [containerId]. */
    fun editor(containerId: String): String = "editor/$containerId"
}

/** One entry of the bottom bar / navigation rail. */
private data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val TOP_LEVEL_DESTINATIONS: List<TopLevelDestination> = listOf(
    TopLevelDestination(WinDroidDestinations.CONTAINERS, R.string.nav_containers, Icons.Filled.Computer),
    TopLevelDestination(WinDroidDestinations.INPUT, R.string.nav_input, Icons.Filled.Mouse),
    TopLevelDestination(WinDroidDestinations.RUNTIME, R.string.nav_runtime, Icons.Filled.Memory),
)

/**
 * Immutable bundle of everything the screens need.
 *
 * Rebuilt on every recomposition on purpose: [peripherals], [runtimeStatus] and
 * [defaultInputConfig] are live values, so caching the bundle would serve stale
 * data to the screens.
 */
private class WinDroidNavDependencies(
    val containers: List<ContainerConfig>,
    val containerRepository: ContainerRepository,
    val imageFsManager: ImageFsManager,
    val peripherals: ConnectedPeripherals,
    val onRefreshPeripherals: () -> Unit,
    val runtimeStatus: RuntimeStatus?,
    val onRefreshRuntimeStatus: () -> Unit,
    val defaultInputConfig: InputConfig,
    val onUpdateDefaultInputConfig: (InputConfig) -> Unit,
    val onLaunchSession: (String) -> Unit,
)

/**
 * Root composable: navigation chrome (bottom bar on phones, navigation rail from
 * 720dp up) plus the [NavHost] that owns every screen.
 */
@Composable
fun WinDroidNavHost(
    containerRepository: ContainerRepository,
    imageFsManager: ImageFsManager,
    peripherals: ConnectedPeripherals,
    onRefreshPeripherals: () -> Unit,
    runtimeStatus: RuntimeStatus?,
    onRefreshRuntimeStatus: () -> Unit,
    defaultInputConfig: InputConfig,
    onUpdateDefaultInputConfig: (InputConfig) -> Unit,
    onLaunchSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val containers by containerRepository.containers.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The editor is a child of the containers tab, so keep that tab selected.
    val selectedRoute = when {
        currentRoute == null -> WinDroidDestinations.CONTAINERS
        currentRoute.startsWith("editor") -> WinDroidDestinations.CONTAINERS
        else -> currentRoute
    }
    // Full-screen editor: hide the top-level chrome while it is open.
    val showChrome = currentRoute == null || !currentRoute.startsWith("editor")

    val deps = WinDroidNavDependencies(
        containers = containers,
        containerRepository = containerRepository,
        imageFsManager = imageFsManager,
        peripherals = peripherals,
        onRefreshPeripherals = onRefreshPeripherals,
        runtimeStatus = runtimeStatus,
        onRefreshRuntimeStatus = onRefreshRuntimeStatus,
        defaultInputConfig = defaultInputConfig,
        onUpdateDefaultInputConfig = onUpdateDefaultInputConfig,
        onLaunchSession = onLaunchSession,
    )

    val navigateToTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            // Keep a single copy of each tab and remember its scroll position.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val useNavigationRail = maxWidth >= 720.dp

        if (useNavigationRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    TOP_LEVEL_DESTINATIONS.forEach { destination ->
                        NavigationRailItem(
                            selected = selectedRoute == destination.route,
                            onClick = { navigateToTopLevel(destination.route) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
                WinDroidNavGraph(
                    navController = navController,
                    deps = deps,
                    onNavigateTopLevel = navigateToTopLevel,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                )
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (showChrome) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            TOP_LEVEL_DESTINATIONS.forEach { destination ->
                                NavigationBarItem(
                                    selected = selectedRoute == destination.route,
                                    onClick = { navigateToTopLevel(destination.route) },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(stringResource(destination.labelRes)) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                WinDroidNavGraph(
                    navController = navController,
                    deps = deps,
                    onNavigateTopLevel = navigateToTopLevel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

/** The actual navigation graph; shared by both chrome layouts. */
@Composable
private fun WinDroidNavGraph(
    navController: NavHostController,
    deps: WinDroidNavDependencies,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = WinDroidDestinations.CONTAINERS,
        modifier = modifier,
    ) {
        composable(WinDroidDestinations.CONTAINERS) {
            ContainerListScreen(
                containers = deps.containers,
                runtimeStatus = deps.runtimeStatus,
                peripherals = deps.peripherals,
                onRefreshPeripherals = deps.onRefreshPeripherals,
                onCreateContainer = {
                    // createDefaultContainer() already persists the new config, so
                    // the editor can resolve it by id straight away.
                    val created = deps.containerRepository.createDefaultContainer()
                    navController.navigate(WinDroidDestinations.editor(created.id))
                },
                onEditContainer = { containerId ->
                    navController.navigate(WinDroidDestinations.editor(containerId))
                },
                onDeleteContainer = { containerId ->
                    deps.containerRepository.delete(containerId)
                },
                onRunContainer = { containerId -> deps.onLaunchSession(containerId) },
                onOpenRuntimeSetup = { onNavigateTopLevel(WinDroidDestinations.RUNTIME) },
            )
        }

        composable(
            route = WinDroidDestinations.EDITOR,
            arguments = listOf(
                navArgument(WinDroidDestinations.CONTAINER_ID_ARG) { type = NavType.StringType },
            ),
        ) { entry ->
            val containerId = entry.arguments
                ?.getString(WinDroidDestinations.CONTAINER_ID_ARG)
                .orEmpty()

            ContainerEditorScreen(
                containerId = containerId,
                containerRepository = deps.containerRepository,
                defaultInputConfig = deps.defaultInputConfig,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WinDroidDestinations.INPUT) {
            InputSettingsScreen(
                config = deps.defaultInputConfig,
                onConfigChange = deps.onUpdateDefaultInputConfig,
                peripherals = deps.peripherals,
                onRefreshPeripherals = deps.onRefreshPeripherals,
            )
        }

        composable(WinDroidDestinations.RUNTIME) {
            RuntimeSetupScreen(
                imageFsManager = deps.imageFsManager,
                status = deps.runtimeStatus,
                onRefreshStatus = deps.onRefreshRuntimeStatus,
            )
        }
    }
}
