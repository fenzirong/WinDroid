package com.windroid.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.windroid.app.R
import com.windroid.app.core.model.InstallProgress
import com.windroid.app.core.model.RuntimeComponentStatus
import com.windroid.app.core.model.RuntimeStatus
import com.windroid.app.runtime.ImageFsManager
import com.windroid.app.ui.theme.InfoPill
import com.windroid.app.ui.theme.SectionCard
import com.windroid.app.ui.theme.StatusDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Runtime installer / inspector.
 *
 * Reads the on-disk state through [ImageFsManager.inspectRuntime], imports a
 * `.zip` bundle through the Storage Access Framework and streams
 * [InstallProgress] into a live progress bar. The content URI stream is handed
 * straight to the manager — the UI never copies the archive first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSetupScreen(
    imageFsManager: ImageFsManager,
    status: RuntimeStatus?,
    onRefreshStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    var installing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    val bundlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        val fileName = context.displayNameOf(uri) ?: DEFAULT_BUNDLE_NAME
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (stream == null) {
            errorMessage = context.getString(R.string.runtime_file_unreadable)
            return@rememberLauncherForActivityResult
        }

        installing = true
        errorMessage = null
        progress = InstallProgress(stage = "open", fraction = 0f, message = fileName)

        scope.launch {
            try {
                imageFsManager.importArchive(stream, fileName)
                    .flowOn(Dispatchers.IO)
                    .catch { throwable ->
                        errorMessage = throwable.message ?: throwable::class.java.simpleName
                        installing = false
                    }
                    .collect { update ->
                        progress = update
                        if (update.error != null) {
                            errorMessage = update.error
                            installing = false
                        } else if (update.isComplete) {
                            installing = false
                            onRefreshStatus()
                        }
                    }
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.runtime_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                RuntimeStatusCard(status = status)
            }

            item(key = "components") {
                ComponentsCard(status)
            }

            item(key = "install") {
                InstallCard(
                    installing = installing,
                    progress = progress,
                    errorMessage = errorMessage,
                    onPickBundle = { bundlePicker.launch(RUNTIME_BUNDLE_MIME_TYPES) },
                )
            }

            if (status?.isFullRuntimeReady == true) {
                item(key = "remove") {
                    OutlinedButton(
                        onClick = { confirmRemove = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = null,
                        )
                        Text(
                            text = stringResource(R.string.runtime_remove),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            item(key = "references") {
                ReferencesCard()
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.runtime_remove_title)) },
            text = { Text(stringResource(R.string.runtime_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        progress = null
                        imageFsManager.clearRuntime()
                        onRefreshStatus()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Aggregate readiness of the Wine + Box64 stack. */
@Composable
private fun RuntimeStatusCard(status: RuntimeStatus?) {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(active = ready)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when {
                            status == null -> R.string.runtime_banner_checking
                            ready -> R.string.runtime_ready
                            else -> R.string.runtime_missing
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        if (ready) R.string.runtime_banner_full_body else R.string.runtime_install_body,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** Per-component installation table (imagefs, proot, box64, wine). */
@Composable
private fun ComponentsCard(status: RuntimeStatus?) {
    // Prefer the manager's own component list; synthesise it from the aggregate
    // booleans when the backend only filled those.
    val components: List<RuntimeComponentStatus> = when (val current = status) {
        null -> emptyList()
        else -> if (current.components.isNotEmpty()) {
            current.components
        } else {
            listOf(
                RuntimeComponentStatus(
                    name = stringResource(R.string.runtime_component_imagefs),
                    installed = current.imageFsInstalled,
                    path = "",
                ),
                RuntimeComponentStatus(
                    name = stringResource(R.string.runtime_component_proot),
                    installed = current.prootInstalled,
                    path = "",
                ),
                RuntimeComponentStatus(
                    name = stringResource(R.string.runtime_component_box64),
                    installed = current.box64Installed,
                    path = "",
                ),
                RuntimeComponentStatus(
                    name = stringResource(R.string.runtime_component_wine),
                    installed = current.wineInstalled,
                    path = "",
                ),
            )
        }
    }

    SectionCard(
        title = stringResource(R.string.runtime_components),
        subtitle = stringResource(R.string.runtime_components_body),
    ) {
        if (components.isEmpty()) {
            Text(
                text = stringResource(R.string.runtime_banner_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            components.forEach { component ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(active = component.installed)
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InfoPill(
                        text = stringResource(
                            if (component.installed) {
                                R.string.runtime_component_installed
                            } else {
                                R.string.runtime_component_missing
                            },
                        ),
                        containerColor = if (component.installed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (component.installed) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (component.detail.isNotBlank()) {
                    Text(
                        text = component.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Bundle picker plus the live install progress. */
@Composable
private fun InstallCard(
    installing: Boolean,
    progress: InstallProgress?,
    errorMessage: String?,
    onPickBundle: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.runtime_install_title),
        subtitle = stringResource(R.string.runtime_install_body),
    ) {
        Button(
            onClick = onPickBundle,
            enabled = !installing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.UploadFile, contentDescription = null)
            Text(
                text = stringResource(
                    if (installing) R.string.runtime_installing else R.string.runtime_pick_bundle,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        val current = progress
        if (current != null) {
            LinearProgressIndicator(
                progress = { current.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.runtime_stage_label,
                    current.stage,
                    current.message,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (current.isComplete) {
                InfoPill(
                    text = stringResource(R.string.runtime_installed),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        val failure = errorMessage
        if (failure != null) {
            Text(
                text = stringResource(R.string.runtime_import_failed, failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Upstream projects WinDroid is built on. */
@Composable
private fun ReferencesCard() {
    val context = LocalContext.current

    SectionCard(
        title = stringResource(R.string.runtime_references),
        subtitle = stringResource(R.string.runtime_references_body),
    ) {
        OSS_REFERENCES.forEach { reference ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { context.openUrl(reference.url) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reference.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = reference.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Reads the human-readable name behind a SAF content URI. */
private fun Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}.getOrNull()

/** Opens an upstream repository / docs page in the browser. */
private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** One upstream project row. */
private data class OssReference(
    val name: String,
    val url: String,
    val description: String,
)

private val OSS_REFERENCES: List<OssReference> = listOf(
    OssReference(
        name = "Winlator",
        url = "https://github.com/brunodev85/winlator",
        description = "Wine + Box86/Box64 container app for Android",
    ),
    OssReference(
        name = "Wine",
        url = "https://gitlab.winehq.org/wine/wine",
        description = "Windows API translation layer",
    ),
    OssReference(
        name = "Box64",
        url = "https://github.com/ptitSeb/box64",
        description = "x86_64 user-space emulator for ARM64 Linux",
    ),
    OssReference(
        name = "PRoot",
        url = "https://proot-me.github.io/",
        description = "User-space chroot and bind mounts without root",
    ),
    OssReference(
        name = "Termux-X11",
        url = "https://github.com/termux/termux-x11",
        description = "X11 server for Android",
    ),
)

/** Zips are usually reported as one of these; some providers use octet-stream. */
private val RUNTIME_BUNDLE_MIME_TYPES: Array<String> = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

private const val DEFAULT_BUNDLE_NAME = "runtime.zip"
