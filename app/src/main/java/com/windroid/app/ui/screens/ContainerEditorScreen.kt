package com.windroid.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.windroid.app.core.model.AudioDriver
import com.windroid.app.core.model.Box64Preset
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.DisplaySpec
import com.windroid.app.core.model.DriveMapping
import com.windroid.app.core.model.DxWrapper
import com.windroid.app.core.model.GraphicsDriver
import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.MouseMode
import com.windroid.app.core.model.RightClickBehavior
import com.windroid.app.data.ContainerRepository
import com.windroid.app.ui.theme.DropdownField
import com.windroid.app.ui.theme.LabeledTextField
import com.windroid.app.ui.theme.SectionCard
import com.windroid.app.ui.theme.SliderSetting
import com.windroid.app.ui.theme.SwitchSettingRow

/**
 * Full-screen container editor.
 *
 * Edits a local draft and only writes back to [containerRepository] on Save, so
 * backing out never mutates a stored container. A brand-new route (created by the
 * list screen FAB, or a stale deep link) is seeded with a valid default config,
 * which makes this screen the single place a container comes into existence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerEditorScreen(
    containerId: String,
    containerRepository: ContainerRepository,
    defaultInputConfig: InputConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val existing = remember(containerId) { containerRepository.getById(containerId) }

    var draft by remember(containerId) {
        mutableStateOf(existing ?: ContainerConfig(id = containerId, name = NEW_CONTAINER_NAME))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (existing == null) R.string.editor_title_new else R.string.editor_title,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            containerRepository.save(draft)
                            onBack()
                        },
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
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
            // --- General ---------------------------------------------------
            item(key = "general") {
                SectionCard(title = stringResource(R.string.editor_section_general)) {
                    LabeledTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = stringResource(R.string.editor_field_name),
                    )
                }
            }

            // --- Display ---------------------------------------------------
            item(key = "display") {
                SectionCard(title = stringResource(R.string.editor_section_display)) {
                    Text(
                        text = stringResource(R.string.editor_field_resolution),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items = DisplaySpec.ALL_PRESETS, key = { it.resolutionLabel }) { preset ->
                            FilterChip(
                                selected = preset == draft.display,
                                onClick = { draft = draft.copy(display = preset) },
                                label = { Text(preset.resolutionLabel) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.editor_display_dpi,
                            draft.display.dpi,
                            draft.display.refreshRate,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Graphics --------------------------------------------------
            item(key = "graphics") {
                SectionCard(title = stringResource(R.string.editor_section_graphics)) {
                    DropdownField(
                        label = stringResource(R.string.editor_field_graphics_driver),
                        options = GraphicsDriver.entries.toList(),
                        selected = draft.graphicsDriver,
                        optionLabel = { it.displayName },
                        onSelected = { draft = draft.copy(graphicsDriver = it) },
                    )
                    DropdownField(
                        label = stringResource(R.string.editor_field_dx_wrapper),
                        options = DxWrapper.entries.toList(),
                        selected = draft.dxWrapper,
                        optionLabel = { it.displayName },
                        onSelected = { draft = draft.copy(dxWrapper = it) },
                    )
                    DropdownField(
                        label = stringResource(R.string.editor_field_box64),
                        options = Box64Preset.entries.toList(),
                        selected = draft.box64Preset,
                        optionLabel = { it.displayName },
                        onSelected = { draft = draft.copy(box64Preset = it) },
                    )
                }
            }

            // --- Wine & audio ---------------------------------------------
            item(key = "wine") {
                SectionCard(title = stringResource(R.string.editor_section_wine)) {
                    LabeledTextField(
                        value = draft.wineVersion,
                        onValueChange = { draft = draft.copy(wineVersion = it) },
                        label = stringResource(R.string.editor_field_wine_version),
                    )
                    LabeledTextField(
                        value = draft.startupCommand,
                        onValueChange = { draft = draft.copy(startupCommand = it) },
                        label = stringResource(R.string.editor_field_startup_command),
                    )
                    DropdownField(
                        label = stringResource(R.string.editor_field_audio),
                        options = AudioDriver.entries.toList(),
                        selected = draft.audioDriver,
                        optionLabel = { it.displayName },
                        onSelected = { draft = draft.copy(audioDriver = it) },
                    )
                }
            }

            // --- Drive mappings -------------------------------------------
            item(key = "drives") {
                DriveMappingsCard(
                    drives = draft.drives,
                    onDrivesChange = { drives -> draft = draft.copy(drives = drives) },
                )
            }

            // --- Input -----------------------------------------------------
            item(key = "input") {
                ContainerInputCard(
                    config = draft.inputConfig,
                    defaultConfig = defaultInputConfig,
                    onConfigChange = { updated -> draft = draft.copy(inputConfig = updated) },
                )
            }
        }
    }
}

/**
 * Drive-letter to host-path mappings.
 *
 * The next free letter is always offered first so the letters stay unique and
 * readable inside the guest.
 */
@Composable
private fun DriveMappingsCard(
    drives: List<DriveMapping>,
    onDrivesChange: (List<DriveMapping>) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.editor_section_drives),
        trailing = {
            TextButton(
                onClick = {
                    onDrivesChange(drives + DriveMapping(nextFreeLetter(drives), "/sdcard/Download"))
                },
            ) {
                Text(stringResource(R.string.editor_drives_add))
            }
        },
    ) {
        if (drives.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_drives_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            drives.forEachIndexed { index, drive ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.editor_drive_letter, drive.letter.toString()),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedTextField(
                        value = drive.hostPath,
                        onValueChange = { path ->
                            val updated = drives.toMutableList()
                            updated[index] = drive.copy(hostPath = path)
                            onDrivesChange(updated)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        placeholder = { Text(stringResource(R.string.editor_drive_host_path)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(
                        onClick = {
                            val updated = drives.toMutableList()
                            updated.removeAt(index)
                            onDrivesChange(updated)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(
                                R.string.editor_drive_remove,
                                drive.letter.toString(),
                            ),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** Per-container input overrides, seeded from the global defaults. */
@Composable
private fun ContainerInputCard(
    config: InputConfig,
    defaultConfig: InputConfig,
    onConfigChange: (InputConfig) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.editor_section_input),
        trailing = {
            TextButton(onClick = { onConfigChange(defaultConfig) }) {
                Text(stringResource(R.string.editor_input_reset))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.editor_field_mouse_mode),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MouseMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.mouseMode == mode,
                    onClick = { onConfigChange(config.copy(mouseMode = mode)) },
                    label = {
                        Text(
                            text = if (mode == MouseMode.ABSOLUTE) {
                                stringResource(R.string.input_mouse_absolute)
                            } else {
                                stringResource(R.string.input_mouse_captured)
                            },
                        )
                    },
                )
            }
        }

        SliderSetting(
            title = stringResource(R.string.editor_field_sensitivity),
            valueLabel = stringResource(R.string.input_sensitivity_value, config.mouseSensitivity),
            value = config.mouseSensitivity,
            onValueChange = { onConfigChange(config.copy(mouseSensitivity = it)) },
        )

        DropdownField(
            label = stringResource(R.string.editor_field_right_click),
            options = RightClickBehavior.entries.toList(),
            selected = config.rightClickBehavior,
            optionLabel = { behaviour ->
                if (behaviour == RightClickBehavior.X11_RIGHT_BUTTON) {
                    stringResource(R.string.input_right_click_x11)
                } else {
                    stringResource(R.string.input_right_click_back)
                }
            },
            onSelected = { behaviour -> onConfigChange(config.copy(rightClickBehavior = behaviour)) },
        )

        SwitchSettingRow(
            title = stringResource(R.string.editor_input_touchpad),
            checked = config.enableTouchpadFallback,
            onCheckedChange = { onConfigChange(config.copy(enableTouchpadFallback = it)) },
        )
        SwitchSettingRow(
            title = stringResource(R.string.editor_input_hide_virtual),
            checked = config.hideVirtualControlsWhenHardwareConnected,
            onCheckedChange = {
                onConfigChange(config.copy(hideVirtualControlsWhenHardwareConnected = it))
            },
        )
    }
}

/** Next unused drive letter in `D..Z`, falling back to `D` when the range is full. */
private fun nextFreeLetter(drives: List<DriveMapping>): Char {
    val used = drives.map { it.letter }.toSet()
    return ('D'..'Z').firstOrNull { it !in used } ?: 'D'
}

private const val NEW_CONTAINER_NAME = "New Windows Container"
