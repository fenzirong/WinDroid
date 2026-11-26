package com.windroid.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.windroid.app.R
import com.windroid.app.core.model.ConnectedPeripherals
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.RuntimeStatus
import com.windroid.app.ui.theme.InfoPill
import com.windroid.app.ui.theme.SectionCard
import com.windroid.app.ui.theme.StatusDot

/**
 * Container list — the app's home screen.
 *
 * Top to bottom: the runtime readiness banner (full Wine + Box64 stack versus the
 * built-in demo desktop), the live external-device badge, then one card per
 * saved container with Run / Edit / Delete, and a FAB that creates a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerListScreen(
    containers: List<ContainerConfig>,
    runtimeStatus: RuntimeStatus?,
    peripherals: ConnectedPeripherals,
    onRefreshPeripherals: () -> Unit,
    onCreateContainer: () -> Unit,
    onEditContainer: (String) -> Unit,
    onDeleteContainer: (String) -> Unit,
    onRunContainer: (String) -> Unit,
    onOpenRuntimeSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ContainerConfig?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Insets are already consumed by the navigation shell.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.containers_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateContainer,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.containers_create)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "runtime-banner") {
                RuntimeReadinessBanner(
                    status = runtimeStatus,
                    onOpenRuntimeSetup = onOpenRuntimeSetup,
                )
            }

            item(key = "devices") {
                ConnectedDevicesCard(
                    peripherals = peripherals,
                    onRefresh = onRefreshPeripherals,
                )
            }

            if (containers.isEmpty()) {
                item(key = "empty") { EmptyContainersState() }
            }

            items(items = containers, key = { it.id }) { container ->
                ContainerCard(
                    container = container,
                    onRun = { onRunContainer(container.id) },
                    onEdit = { onEditContainer(container.id) },
                    onDelete = { pendingDelete = container },
                )
            }
        }
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.containers_delete_title)) },
            text = {
                Text(stringResource(R.string.containers_delete_body, deleteTarget.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteContainer(deleteTarget.id)
                        pendingDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Runtime readiness banner: accent container when the full Wine + Box64 stack is
 * installed, warning tone while sessions would fall back to the built-in demo
 * desktop.
 */
@Composable
private fun RuntimeReadinessBanner(
    status: RuntimeStatus?,
    onOpenRuntimeSetup: () -> Unit,
) {
    val ready = status?.isFullRuntimeReady == true

    val containerColor = when {
        status == null -> MaterialTheme.colorScheme.surfaceContainerHigh
        ready -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when {
        status == null -> MaterialTheme.colorScheme.onSurfaceVariant
        ready -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val title = when {
        status == null -> stringResource(R.string.runtime_banner_checking)
        ready -> stringResource(R.string.runtime_banner_full_title)
        else -> stringResource(R.string.runtime_banner_demo_title)
    }
    val body = when {
        status == null -> stringResource(R.string.runtime_banner_checking)
        ready -> stringResource(R.string.runtime_banner_full_body)
        else -> stringResource(R.string.runtime_banner_demo_body)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenRuntimeSetup),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(active = ready)
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f),
                )
            }
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/**
 * Live badge for physically attached input devices.
 *
 * Shared with the input settings screen so both places show exactly the same
 * snapshot from `ExternalDeviceMonitor`.
 */
@Composable
fun ConnectedDevicesCard(
    peripherals: ConnectedPeripherals,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.devices_title),
) {
    val mousePart = if (peripherals.miceCount > 0) mouseCountLabel(peripherals.miceCount) else null
    val keyboardPart = if (peripherals.keyboardsCount > 0) {
        keyboardCountLabel(peripherals.keyboardsCount)
    } else {
        null
    }
    val summary = listOfNotNull(mousePart, keyboardPart).joinToString(" · ")
    val hasHardware = peripherals.hasExternalMouse || peripherals.hasExternalKeyboard
    val deviceNames = peripherals.mouseNames + peripherals.keyboardNames

    SectionCard(
        title = title,
        modifier = modifier,
        trailing = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.devices_refresh),
                )
            }
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoPill(
                text = if (summary.isEmpty()) stringResource(R.string.devices_touch_only) else summary,
                icon = if (peripherals.hasExternalMouse) Icons.Filled.Mouse else Icons.Filled.Keyboard,
                containerColor = if (summary.isEmpty()) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (summary.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
            StatusDot(active = hasHardware)
            Text(
                text = stringResource(
                    if (hasHardware) R.string.devices_hardware_ready else R.string.devices_hardware_missing,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (deviceNames.isEmpty()) {
            Text(
                text = stringResource(R.string.devices_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                deviceNames.forEach { name ->
                    Text(
                        text = "· $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun mouseCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(R.string.devices_mouse_one)
    } else {
        stringResource(R.string.devices_mouse_many, count)
    }

@Composable
private fun keyboardCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(R.string.devices_keyboard_one)
    } else {
        stringResource(R.string.devices_keyboard_many, count)
    }

/** One saved container with its translation-layer badges and actions. */
@Composable
private fun ContainerCard(
    container: ContainerConfig,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Computer,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = container.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.containers_config_summary,
                            container.display.resolutionLabel,
                            container.display.dpi,
                            container.display.refreshRate,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Badge strip scrolls horizontally so long driver names never wrap
            // into an unreadable block on narrow screens.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoPill(text = container.graphicsDriver.displayName)
                InfoPill(text = container.dxWrapper.displayName)
                InfoPill(text = container.box64Preset.displayName)
                InfoPill(text = container.audioDriver.displayName)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onRun) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_run))
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Shown when no container has been created yet. */
@Composable
private fun EmptyContainersState() {
    SectionCard(title = stringResource(R.string.containers_empty_title)) {
        Text(
            text = stringResource(R.string.containers_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
