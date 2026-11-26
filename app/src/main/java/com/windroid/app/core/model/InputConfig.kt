package com.windroid.app.core.model

import kotlinx.serialization.Serializable

/** How physical-mouse movement maps onto the X11 pointer. */
@Serializable
enum class MouseMode {
    /** Direct absolute positioning: the Android touch/mouse point becomes the X11 point. */
    ABSOLUTE,

    /** Captured relative mode: deltas drive the pointer, the cursor is hidden and re-centred. */
    CAPTURED_RELATIVE,
}

/** What a secondary (right) click should do inside a session. */
@Serializable
enum class RightClickBehavior {
    /** Forward the click to X11 as button 3. */
    X11_RIGHT_BUTTON,

    /** Treat the secondary click as the Android back gesture instead. */
    ANDROID_BACK,
}

/**
 * Input tuning for a container. Persisted with [ContainerConfig], so it is
 * serializable and fully defaulted for forward compatibility.
 */
@Serializable
data class InputConfig(
    val mouseMode: MouseMode = MouseMode.ABSOLUTE,
    val mouseSensitivity: Float = 1.0f,
    val scrollSpeed: Float = 1.0f,
    val enableMouseAcceleration: Boolean = false,
    val rightClickBehavior: RightClickBehavior = RightClickBehavior.X11_RIGHT_BUTTON,
    val enableTouchpadFallback: Boolean = true,
    val capturePointerOnClick: Boolean = false,
    val hideVirtualControlsWhenHardwareConnected: Boolean = true,
    val interceptSystemShortcuts: Boolean = true,
)

/**
 * Snapshot of the physically attached input devices, produced by the input
 * module and consumed by the UI to decide whether virtual controls stay on
 * screen.
 */
data class ConnectedPeripherals(
    val miceCount: Int = 0,
    val keyboardsCount: Int = 0,
    val mouseNames: List<String> = emptyList(),
    val keyboardNames: List<String> = emptyList(),
) {
    val hasExternalMouse: Boolean get() = miceCount > 0
    val hasExternalKeyboard: Boolean get() = keyboardsCount > 0
}
