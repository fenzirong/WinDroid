package com.windroid.app.core.model

/**
 * X11 pointer buttons with their protocol numbers.
 *
 * Lives in `core.model` on purpose: the `input` module (event producer) and the
 * `xserver` module (event consumer) both depend on it, so neither has to depend
 * on the other and no import cycle can appear.
 */
enum class PointerButton(val x11Button: Int) {
    LEFT(1),
    MIDDLE(2),
    RIGHT(3),
    WHEEL_UP(4),
    WHEEL_DOWN(5),
    WHEEL_LEFT(6),
    WHEEL_RIGHT(7),
    BACK(8),
    FORWARD(9),
}

/**
 * Sink that accepts normalised host input and forwards it to the X11 server.
 *
 * Implementations must be safe to call from the UI thread; the X11 transport is
 * responsible for its own queuing.
 */
interface InputEventSink {
    /** Absolute pointer position in X11 screen coordinates. */
    fun onPointerMove(x: Int, y: Int)

    /** Button press/release at the supplied X11 screen coordinates. */
    fun onPointerButton(button: PointerButton, pressed: Boolean, x: Int, y: Int)

    /**
     * Key press/release. [x11Keycode] is the raw X11 keycode, [x11Keysym] the
     * resolved keysym (needed for keys the layout remaps), and [modifiersMask]
     * the X11 modifier state (ShiftMask = 1, LockMask = 2, ControlMask = 4,
     * Mod1Mask = 8, Mod4Mask = 64).
     */
    fun onKeyEvent(x11Keycode: Int, x11Keysym: Int, pressed: Boolean, modifiersMask: Int)

    /** Release every button and key currently held (focus loss, session stop). */
    fun onReleaseAllInputs()
}
