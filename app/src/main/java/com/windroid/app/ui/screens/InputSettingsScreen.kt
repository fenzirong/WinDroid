package com.windroid.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.windroid.app.R
import com.windroid.app.core.model.ConnectedPeripherals
import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.MouseMode
import com.windroid.app.core.model.RightClickBehavior
import com.windroid.app.ui.theme.DropdownField
import com.windroid.app.ui.theme.SectionCard
import com.windroid.app.ui.theme.SliderSetting
import com.windroid.app.ui.theme.SwitchSettingRow
import kotlinx.serialization.json.Json

/**
 * Global (default) input tuning.
 *
 * Everything here is the fallback a container uses when it does not override the
 * value in its own `InputConfig`. Values are written through to
 * [DefaultInputConfigStore] on every change, so they survive process death.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputSettingsScreen(
    config: InputConfig,
    onConfigChange: (InputConfig) -> Unit,
    peripherals: ConnectedPeripherals,
    onRefreshPeripherals: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.input_title)) },
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
            item(key = "hint") {
                Text(
                    text = stringResource(R.string.input_global_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "pointer-mode") {
                PointerModeCard(config = config, onConfigChange = onConfigChange)
            }

            item(key = "mouse-tuning") {
                MouseTuningCard(config = config, onConfigChange = onConfigChange)
            }

            item(key = "buttons") {
                ButtonsCard(config = config, onConfigChange = onConfigChange)
            }

            item(key = "devices") {
                ConnectedDevicesCard(
                    peripherals = peripherals,
                    onRefresh = onRefreshPeripherals,
                )
            }

            item(key = "shortcuts") {
                ShortcutsCard()
            }
        }
    }
}

/** Absolute versus captured-relative pointer behaviour. */
@Composable
private fun PointerModeCard(
    config: InputConfig,
    onConfigChange: (InputConfig) -> Unit,
) {
    SectionCard(title = stringResource(R.string.input_section_pointer)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
        Text(
            text = stringResource(
                if (config.mouseMode == MouseMode.ABSOLUTE) {
                    R.string.input_mouse_absolute_hint
                } else {
                    R.string.input_mouse_captured_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Sensitivity, scroll speed and acceleration. */
@Composable
private fun MouseTuningCard(
    config: InputConfig,
    onConfigChange: (InputConfig) -> Unit,
) {
    SectionCard(title = stringResource(R.string.input_section_mouse)) {
        SliderSetting(
            title = stringResource(R.string.input_sensitivity),
            valueLabel = stringResource(R.string.input_sensitivity_value, config.mouseSensitivity),
            value = config.mouseSensitivity,
            onValueChange = { onConfigChange(config.copy(mouseSensitivity = it)) },
        )
        SliderSetting(
            title = stringResource(R.string.input_scroll),
            valueLabel = stringResource(R.string.input_scroll_value, config.scrollSpeed),
            value = config.scrollSpeed,
            onValueChange = { onConfigChange(config.copy(scrollSpeed = it)) },
        )
        SwitchSettingRow(
            title = stringResource(R.string.input_acceleration),
            subtitle = stringResource(R.string.input_acceleration_hint),
            checked = config.enableMouseAcceleration,
            onCheckedChange = { onConfigChange(config.copy(enableMouseAcceleration = it)) },
        )
    }
}

/** Right-click behaviour, touchpad fallback and virtual-control visibility. */
@Composable
private fun ButtonsCard(
    config: InputConfig,
    onConfigChange: (InputConfig) -> Unit,
) {
    SectionCard(title = stringResource(R.string.input_section_buttons)) {
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
            onSelected = { behaviour ->
                onConfigChange(config.copy(rightClickBehavior = behaviour))
            },
        )
        Text(
            text = stringResource(R.string.input_right_click_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchSettingRow(
            title = stringResource(R.string.editor_input_touchpad),
            subtitle = stringResource(R.string.input_touchpad_hint),
            checked = config.enableTouchpadFallback,
            onCheckedChange = { onConfigChange(config.copy(enableTouchpadFallback = it)) },
        )
        SwitchSettingRow(
            title = stringResource(R.string.editor_input_hide_virtual),
            subtitle = stringResource(R.string.input_hide_virtual_hint),
            checked = config.hideVirtualControlsWhenHardwareConnected,
            onCheckedChange = {
                onConfigChange(config.copy(hideVirtualControlsWhenHardwareConnected = it))
            },
        )
        SwitchSettingRow(
            title = stringResource(R.string.input_capture_on_click),
            subtitle = stringResource(R.string.input_capture_on_click_hint),
            checked = config.capturePointerOnClick,
            onCheckedChange = { onConfigChange(config.copy(capturePointerOnClick = it)) },
        )
        SwitchSettingRow(
            title = stringResource(R.string.input_intercept_shortcuts),
            subtitle = stringResource(R.string.input_intercept_shortcuts_hint),
            checked = config.interceptSystemShortcuts,
            onCheckedChange = { onConfigChange(config.copy(interceptSystemShortcuts = it)) },
        )
    }
}

/** Shortcut reference card for the in-session hotkeys. */
@Composable
private fun ShortcutsCard() {
    SectionCard(
        title = stringResource(R.string.input_section_shortcuts),
        subtitle = stringResource(R.string.shortcut_hint),
    ) {
        ShortcutRow(
            title = stringResource(R.string.shortcut_toggle_capture),
            keys = listOf("Ctrl", "Alt", "G"),
        )
        ShortcutRow(
            title = stringResource(R.string.shortcut_toggle_hud),
            keys = listOf("Ctrl", "Alt", "M"),
        )
        ShortcutRow(
            title = stringResource(R.string.shortcut_exit_session),
            keys = listOf("Ctrl", "Alt", "Shift", "Q"),
        )
    }
}

/** One "action — key chord" line with monospace key caps. */
@Composable
private fun ShortcutRow(
    title: String,
    keys: List<String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        keys.forEach { key ->
            KeyCap(label = key)
        }
    }
}

@Composable
private fun KeyCap(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Persistence for the global default [InputConfig].
 *
 * A container's own `InputConfig` always wins; this store only seeds the editor
 * ("reset to global defaults") and the input screen. Uses SharedPreferences plus
 * kotlinx-serialization so the stored schema always matches [InputConfig].
 */
object DefaultInputConfigStore {

    private const val PREFS_NAME = "windroid_input_defaults"
    private const val KEY_CONFIG = "input_config"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Reads the stored defaults, falling back to `InputConfig()` defaults. */
    fun load(context: Context): InputConfig {
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null)
            ?: return InputConfig()

        return runCatching {
            json.decodeFromString(InputConfig.serializer(), stored)
        }.getOrElse { InputConfig() }
    }

    /** Persists [config] as the new global default. */
    fun save(context: Context, config: InputConfig) {
        val encoded = runCatching {
            json.encodeToString(InputConfig.serializer(), config)
        }.getOrNull() ?: return

        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, encoded)
            .apply()
    }
}
