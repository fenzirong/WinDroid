package com.windroid.app.ui.session

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windroid.app.R
import com.windroid.app.core.model.ConnectedPeripherals
import com.windroid.app.core.model.RuntimeMode
import com.windroid.app.core.model.SessionState
import kotlinx.coroutines.delay

/**
 * In-session floating HUD.
 *
 * Design constraints, in priority order:
 *
 *  1. It must never obstruct an external mouse or keyboard, so the default and
 *     dominant state is a small semi-transparent pill pinned to the top-right
 *     corner. It auto-minimises again a few seconds after being expanded.
 *  2. Everything needed while an X11 session owns the screen has to be reachable
 *     without a system gesture: pointer capture, the Windows combos (Win /
 *     Alt+Tab / Ctrl+Alt+Del / Esc), the log drawer and Exit.
 *  3. Colours are hard-coded light-on-dark rather than pulled from the theme,
 *     because the HUD always floats over the black X11 canvas — a light theme
 *     would render it unreadable.
 */
@Composable
fun SessionHudOverlay(
    sessionState: SessionState,
    runtimeMode: RuntimeMode,
    pointerCaptured: Boolean,
    peripherals: ConnectedPeripherals,
    logs: List<String>,
    onTogglePointerCapture: (Boolean) -> Unit,
    onSendCombo: (IntArray) -> Unit,
    onHideHud: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var logsVisible by remember { mutableStateOf(false) }

    // Auto-minimise: an idle toolbar would cover the top-right of the Windows
    // desktop, which is exactly where external-mouse users aim.
    LaunchedEffect(expanded, logsVisible) {
        if (expanded && !logsVisible) {
            delay(HUD_AUTO_COLLAPSE_MS)
            expanded = false
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!expanded) {
            CollapsedPill(
                sessionState = sessionState,
                runtimeMode = runtimeMode,
                pointerCaptured = pointerCaptured,
                onExpand = { expanded = true },
            )
        } else {
            ExpandedPanel(
                sessionState = sessionState,
                runtimeMode = runtimeMode,
                pointerCaptured = pointerCaptured,
                peripherals = peripherals,
                logs = logs,
                logsVisible = logsVisible,
                onTogglePointerCapture = onTogglePointerCapture,
                onSendCombo = onSendCombo,
                onToggleLogs = { logsVisible = !logsVisible },
                onCollapse = {
                    expanded = false
                    logsVisible = false
                },
                onHideHud = onHideHud,
                onExit = onExit,
            )
        }
    }
}

/** The default, unobtrusive state: status dot, mode, capture state, expand. */
@Composable
private fun CollapsedPill(
    sessionState: SessionState,
    runtimeMode: RuntimeMode,
    pointerCaptured: Boolean,
    onExpand: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onExpand)
            .alpha(HUD_COLLAPSED_ALPHA),
        shape = RoundedCornerShape(50),
        color = HudPanelColor,
        contentColor = HudContentColor,
        border = BorderStroke(1.dp, HudBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color = sessionStateColor(sessionState), shape = RoundedCornerShape(50)),
            )
            Text(
                text = stringResource(
                    if (runtimeMode == RuntimeMode.WINE_BOX64) {
                        R.string.hud_mode_wine
                    } else {
                        R.string.hud_mode_demo
                    },
                ),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = Icons.Filled.Mouse,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (pointerCaptured) HudAccentColor else HudContentColor.copy(alpha = 0.6f),
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.hud_expand),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The full quick toolbar. */
@Composable
private fun ExpandedPanel(
    sessionState: SessionState,
    runtimeMode: RuntimeMode,
    pointerCaptured: Boolean,
    peripherals: ConnectedPeripherals,
    logs: List<String>,
    logsVisible: Boolean,
    onTogglePointerCapture: (Boolean) -> Unit,
    onSendCombo: (IntArray) -> Unit,
    onToggleLogs: () -> Unit,
    onCollapse: () -> Unit,
    onHideHud: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(268.dp)
            .alpha(HUD_EXPANDED_ALPHA),
        shape = RoundedCornerShape(12.dp),
        color = HudPanelColor,
        contentColor = HudContentColor,
        border = BorderStroke(1.dp, HudBorderColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // --- Header: state + collapse ---------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = sessionStateColor(sessionState),
                            shape = RoundedCornerShape(50),
                        ),
                )
                Text(
                    text = sessionStateLabel(sessionState),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.hud_collapse),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onCollapse),
                )
            }

            // --- Runtime mode badge + device indicator --------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudChip(
                    text = stringResource(
                        if (runtimeMode == RuntimeMode.WINE_BOX64) {
                            R.string.hud_mode_wine
                        } else {
                            R.string.hud_mode_demo
                        },
                    ),
                    accent = runtimeMode == RuntimeMode.WINE_BOX64,
                )
                if (peripherals.hasExternalMouse) {
                    Icon(
                        imageVector = Icons.Filled.Mouse,
                        contentDescription = stringResource(R.string.hud_mouse_connected),
                        modifier = Modifier.size(14.dp),
                        tint = HudAccentColor,
                    )
                }
                if (peripherals.hasExternalKeyboard) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = stringResource(R.string.hud_keyboard_connected),
                        modifier = Modifier.size(14.dp),
                        tint = HudAccentColor,
                    )
                }
                if (!peripherals.hasExternalMouse && !peripherals.hasExternalKeyboard) {
                    Text(
                        text = stringResource(R.string.hud_devices_none),
                        fontSize = 10.sp,
                        color = HudContentColor.copy(alpha = 0.6f),
                    )
                }
            }

            // --- Pointer capture toggle (Ctrl+Alt+G) ----------------------
            HudActionRow(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Mouse,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (pointerCaptured) HudAccentColor else HudContentColor,
                    )
                },
                label = stringResource(R.string.hud_toggle_capture),
                value = stringResource(
                    if (pointerCaptured) R.string.hud_pointer_captured else R.string.hud_pointer_absolute,
                ),
                valueAccent = pointerCaptured,
                onClick = { onTogglePointerCapture(!pointerCaptured) },
            )

            // --- Windows combos -------------------------------------------
            Text(
                text = stringResource(R.string.hud_combos_title),
                fontSize = 10.sp,
                color = HudContentColor.copy(alpha = 0.6f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ComboChip(
                    label = stringResource(R.string.hud_combo_win),
                    onClick = { onSendCombo(COMBO_WIN) },
                )
                ComboChip(
                    label = stringResource(R.string.hud_combo_alt_tab),
                    onClick = { onSendCombo(COMBO_ALT_TAB) },
                )
                ComboChip(
                    label = stringResource(R.string.hud_combo_ctrl_alt_del),
                    onClick = { onSendCombo(COMBO_CTRL_ALT_DEL) },
                )
                ComboChip(
                    label = stringResource(R.string.hud_combo_esc),
                    onClick = { onSendCombo(COMBO_ESC) },
                )
            }

            // --- Log drawer toggle + exit ---------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudActionRow(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = HudContentColor,
                        )
                    },
                    label = stringResource(R.string.hud_logs),
                    value = if (logsVisible) "−" else "+",
                    valueAccent = logsVisible,
                    onClick = onToggleLogs,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HudExitColor.copy(alpha = 0.22f),
                    contentColor = HudExitColor,
                    border = BorderStroke(1.dp, HudExitColor.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onExit)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(text = stringResource(R.string.hud_exit), fontSize = 11.sp)
                    }
                }
            }

            if (logsVisible) {
                LogDrawer(logs = logs)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.hud_hide),
                        modifier = Modifier
                            .clickable(onClick = onHideHud)
                            .padding(vertical = 2.dp),
                        fontSize = 10.sp,
                        color = HudAccentColor,
                    )
                }
            }
        }
    }
}

/** Scrollable tail of the session log. */
@Composable
private fun LogDrawer(logs: List<String>) {
    val visibleLogs = remember(logs) { logs.takeLast(MAX_VISIBLE_LOGS) }
    val listState = rememberLazyListState()

    LaunchedEffect(visibleLogs.size) {
        if (visibleLogs.isNotEmpty()) {
            listState.scrollToItem(visibleLogs.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 148.dp)
            .background(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (visibleLogs.isEmpty()) {
            Text(
                text = stringResource(R.string.hud_logs_empty),
                fontSize = 10.sp,
                color = HudContentColor.copy(alpha = 0.6f),
            )
        } else {
            LazyColumn(state = listState) {
                items(items = visibleLogs) { line ->
                    Text(
                        text = line,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = HudContentColor.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

/** Icon + label + right-aligned value, tappable as one row. */
@Composable
private fun HudActionRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    valueAccent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(text = label, fontSize = 11.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (valueAccent) HudAccentColor else HudContentColor.copy(alpha = 0.7f),
        )
    }
}

/** Small label chip used for the runtime-mode badge. */
@Composable
private fun HudChip(
    text: String,
    accent: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (accent) HudAccentColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
        contentColor = if (accent) HudAccentColor else HudContentColor.copy(alpha = 0.75f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** One Windows key-combo button. */
@Composable
private fun ComboChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Color.White.copy(alpha = 0.1f),
        contentColor = HudContentColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Short status label for the session lifecycle. */
@Composable
private fun sessionStateLabel(state: SessionState): String = stringResource(
    when (state) {
        SessionState.IDLE -> R.string.session_state_idle
        SessionState.STARTING -> R.string.session_state_starting
        SessionState.RUNNING -> R.string.session_state_running
        SessionState.STOPPING -> R.string.session_state_stopping
        SessionState.ERROR -> R.string.session_state_error
    },
)

/** Traffic-light colour for the session state dot. */
private fun sessionStateColor(state: SessionState): Color = when (state) {
    SessionState.RUNNING -> Color(0xFF6CCB5F)
    SessionState.STARTING, SessionState.STOPPING -> Color(0xFFFFC64B)
    SessionState.ERROR -> Color(0xFFFF6B6B)
    SessionState.IDLE -> Color(0xFF9E9E9E)
}

// ---------------------------------------------------------------------------
// HUD palette: always light-on-dark, because the overlay floats over the black
// X11 canvas regardless of the app theme.
// ---------------------------------------------------------------------------
private val HudPanelColor = Color(0xFF121212).copy(alpha = 0.80f)
private val HudContentColor = Color(0xFFF3F3F3)
private val HudBorderColor = Color.White.copy(alpha = 0.16f)
private val HudAccentColor = Color(0xFF4CC2FF)
private val HudExitColor = Color(0xFFFF6B6B)

private const val HUD_COLLAPSED_ALPHA = 0.62f
private const val HUD_EXPANDED_ALPHA = 0.97f
private const val HUD_AUTO_COLLAPSE_MS = 7_000L
private const val MAX_VISIBLE_LOGS = 60

// Windows key chords as Android key codes, so XServerSurfaceView can translate
// them into X11 key events via sendSyntheticCombo().
private val COMBO_WIN = intArrayOf(KeyEvent.KEYCODE_META_LEFT)
private val COMBO_ALT_TAB = intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB)
private val COMBO_CTRL_ALT_DEL = intArrayOf(
    KeyEvent.KEYCODE_CTRL_LEFT,
    KeyEvent.KEYCODE_ALT_LEFT,
    KeyEvent.KEYCODE_DEL,
)
private val COMBO_ESC = intArrayOf(KeyEvent.KEYCODE_ESCAPE)
