package com.windroid.app.input

import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.InputEventSink
import com.windroid.app.core.model.MouseMode
import com.windroid.app.core.model.PointerButton
import com.windroid.app.core.model.RightClickBehavior
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The rectangle inside the Android view that actually shows the X11 framebuffer.
 *
 * The X11 screen keeps its own aspect ratio, so the surface is letterboxed
 * (bars top/bottom) or pillarboxed (bars left/right). Absolute pointer input is
 * projected through this rectangle; input landing on the bars is clamped to the
 * nearest screen edge.
 *
 * All values are in Android view pixels.
 */
data class ViewportRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** `true` when the rectangle covers no pixels (surface not measured yet). */
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /** `true` when the point lies inside the rendered framebuffer. */
    fun contains(x: Float, y: Float): Boolean =
        !isEmpty && x >= left && x < right && y >= top && y < bottom

    companion object {
        /** Placeholder used before the surface reports its size. */
        val ZERO: ViewportRect = ViewportRect(0f, 0f, 0f, 0f)
    }
}

/**
 * Translates Android pointer input (absolute touch/mouse, captured relative
 * deltas or touchpad deltas) into X11 pointer events.
 *
 * The processor owns the cursor position, the sensitivity/acceleration curve,
 * the letterbox math and the button diffing, so the activity only has to forward
 * raw `MotionEvent` data. Every emitted event goes through [InputEventSink];
 * nothing here touches the X socket directly, which keeps the class unit
 * testable on a plain JVM.
 *
 * @param sink transport that forwards the events to the X server
 * @param initialConfig initial tuning (mouse mode, sensitivity, …)
 * @param initialScreenWidth X11 screen width in pixels
 * @param initialScreenHeight X11 screen height in pixels
 */
class MouseInputProcessor(
    private val sink: InputEventSink,
    initialConfig: InputConfig,
    initialScreenWidth: Int,
    initialScreenHeight: Int,
) {

    /** Active tuning; [updateConfig] is the sanitising entry point. */
    var config: InputConfig = initialConfig.normalised()

    /** Width of the X11 screen in pixels. */
    var screenWidth: Int = initialScreenWidth.coerceAtLeast(1)
        private set

    /** Height of the X11 screen in pixels. */
    var screenHeight: Int = initialScreenHeight.coerceAtLeast(1)
        private set

    /** Current cursor position on the X11 screen, in pixels. */
    var cursorX: Float = 0f

    /** Current cursor position on the X11 screen, in pixels. */
    var cursorY: Float = 0f

    /** Letterbox/pillarbox rectangle of the rendered framebuffer. */
    var viewport: ViewportRect = ViewportRect.ZERO

    /** Invoked when a secondary click is configured as Android back. */
    var onAndroidBackRequested: (() -> Unit)? = null

    private var viewWidth: Int = screenWidth
    private var viewHeight: Int = screenHeight

    private var verticalScrollAccumulator: Float = 0f
    private var horizontalScrollAccumulator: Float = 0f

    private val pressedButtons = LinkedHashSet<PointerButton>()
    private var lastAndroidButtonState: Int = 0

    /** `true` while the pointer is driven by captured relative deltas. */
    val isCapturedRelativeMode: Boolean
        get() = config.mouseMode == MouseMode.CAPTURED_RELATIVE

    private val maxCursorX: Float get() = (screenWidth - 1).toFloat()
    private val maxCursorY: Float get() = (screenHeight - 1).toFloat()

    init {
        centreCursor()
        updateViewport(screenWidth, screenHeight)
    }

    // ---------------------------------------------------------------------
    // Configuration / geometry
    // ---------------------------------------------------------------------

    /** Applies new tuning; values that would break the math are sanitised. */
    fun updateConfig(config: InputConfig) {
        this.config = config.normalised()
    }

    /**
     * Updates the X11 screen size (resolution change, container switch).
     *
     * The cursor is clamped into the new bounds and the viewport is recomputed
     * against the last known surface size.
     */
    fun updateScreenSize(width: Int, height: Int) {
        val newWidth = width.coerceAtLeast(1)
        val newHeight = height.coerceAtLeast(1)
        if (newWidth == screenWidth && newHeight == screenHeight) return
        screenWidth = newWidth
        screenHeight = newHeight
        cursorX = cursorX.coerceIn(0f, maxCursorX)
        cursorY = cursorY.coerceIn(0f, maxCursorY)
        updateViewport(viewWidth, viewHeight)
    }

    /**
     * Recomputes the aspect-ratio preserving [viewport] for a surface of
     * [viewWidth] × [viewHeight] pixels.
     *
     * Uses the X11 screen aspect as the source of truth: a taller-than-screen
     * surface gets top/bottom bars (letterbox), a wider one gets left/right bars
     * (pillarbox).
     */
    fun updateViewport(viewWidth: Int, viewHeight: Int) {
        this.viewWidth = viewWidth.coerceAtLeast(0)
        this.viewHeight = viewHeight.coerceAtLeast(0)
        viewport = computeViewport(this.viewWidth, this.viewHeight)
    }

    private fun computeViewport(width: Int, height: Int): ViewportRect {
        if (width <= 0 || height <= 0) return ViewportRect.ZERO
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
        val viewAspect = width.toFloat() / height.toFloat()
        return if (viewAspect > screenAspect) {
            // Pillarbox: full height, narrower content centred horizontally.
            val contentWidth = height * screenAspect
            ViewportRect(
                left = (width - contentWidth) / 2f,
                top = 0f,
                width = contentWidth,
                height = height.toFloat(),
            )
        } else {
            // Letterbox: full width, shorter content centred vertically.
            val contentHeight = width / screenAspect
            ViewportRect(
                left = 0f,
                top = (height - contentHeight) / 2f,
                width = width.toFloat(),
                height = contentHeight,
            )
        }
    }

    /** Moves the cursor to the centre of the X11 screen without emitting. */
    fun centreCursor() {
        cursorX = maxCursorX / 2f
        cursorY = maxCursorY / 2f
    }

    // ---------------------------------------------------------------------
    // Motion
    // ---------------------------------------------------------------------

    /**
     * Handles captured relative motion (mouse grabbed, cursor hidden).
     *
     * The delta is scaled by [InputConfig.mouseSensitivity] and, when
     * [InputConfig.enableMouseAcceleration] is on, by a speed dependent curve.
     */
    fun handleCapturedMotion(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        val gain = sensitivityGain() * accelerationGain(hypot(dx, dy))
        moveBy(dx * gain, dy * gain)
    }

    /**
     * Handles absolute motion in view coordinates (touch, hovering pen, mouse in
     * absolute mode) by projecting it through the [viewport].
     */
    fun handleAbsoluteMotion(viewX: Float, viewY: Float) {
        if (!viewX.isFinite() || !viewY.isFinite()) return
        val rect = viewport
        val nx = if (rect.isEmpty) 0f else ((viewX - rect.left) / rect.width).coerceIn(0f, 1f)
        val ny = if (rect.isEmpty) 0f else ((viewY - rect.top) / rect.height).coerceIn(0f, 1f)
        cursorX = (nx * maxCursorX).coerceIn(0f, maxCursorX)
        cursorY = (ny * maxCursorY).coerceIn(0f, maxCursorY)
        emitCursor()
    }

    /**
     * Handles relative motion coming from the on-screen touchpad fallback.
     *
     * Ignored entirely unless [InputConfig.enableTouchpadFallback] is enabled;
     * the touchpad gain is deliberately lower than the captured-mouse gain so a
     * finger swipe does not overshoot the screen.
     */
    fun handleTouchpadDelta(dx: Float, dy: Float) {
        if (!config.enableTouchpadFallback) return
        if (!dx.isFinite() || !dy.isFinite()) return
        moveBy(dx * (sensitivityGain() * TOUCHPAD_GAIN), dy * (sensitivityGain() * TOUCHPAD_GAIN))
    }

    private fun moveBy(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, maxCursorX)
        cursorY = (cursorY + dy).coerceIn(0f, maxCursorY)
        emitCursor()
    }

    private fun emitCursor() {
        sink.onPointerMove(cursorX.roundToInt(), cursorY.roundToInt())
    }

    // ---------------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------------

    /**
     * Diffs a `MotionEvent.getButtonState()` mask against the previous one and
     * emits a press/release for every button that changed.
     *
     * Handled flags: PRIMARY (1) → left, SECONDARY (2) → right or Android back,
     * TERTIARY (4) → middle, BACK (8), FORWARD (16). Stylus buttons are ignored.
     */
    fun handleButtonState(androidButtonState: Int) {
        val changed = lastAndroidButtonState xor androidButtonState
        lastAndroidButtonState = androidButtonState

        if (changed and ANDROID_BUTTON_PRIMARY != 0) {
            handleButtonEvent(PointerButton.LEFT, androidButtonState and ANDROID_BUTTON_PRIMARY != 0)
        }
        if (changed and ANDROID_BUTTON_TERTIARY != 0) {
            handleButtonEvent(PointerButton.MIDDLE, androidButtonState and ANDROID_BUTTON_TERTIARY != 0)
        }
        if (changed and ANDROID_BUTTON_BACK != 0) {
            handleButtonEvent(PointerButton.BACK, androidButtonState and ANDROID_BUTTON_BACK != 0)
        }
        if (changed and ANDROID_BUTTON_FORWARD != 0) {
            handleButtonEvent(PointerButton.FORWARD, androidButtonState and ANDROID_BUTTON_FORWARD != 0)
        }
        if (changed and ANDROID_BUTTON_SECONDARY != 0) {
            val pressed = androidButtonState and ANDROID_BUTTON_SECONDARY != 0
            if (config.rightClickBehavior == RightClickBehavior.X11_RIGHT_BUTTON) {
                handleButtonEvent(PointerButton.RIGHT, pressed)
            } else if (pressed) {
                onAndroidBackRequested?.invoke()
            }
        }
    }

    /**
     * Emits a single button press/release at the current cursor position.
     *
     * Duplicate presses (already held) and orphan releases are dropped so the X
     * server never sees an unbalanced button.
     */
    fun handleButtonEvent(button: PointerButton, pressed: Boolean) {
        if (pressed) {
            if (!pressedButtons.add(button)) return
        } else {
            if (!pressedButtons.remove(button)) return
        }
        sink.onPointerButton(button, pressed, cursorX.roundToInt(), cursorY.roundToInt())
    }

    // ---------------------------------------------------------------------
    // Scrolling
    // ---------------------------------------------------------------------

    /**
     * Accumulates fractional scroll deltas and emits discrete wheel pulses.
     *
     * X11 has no notion of fractional scrolling: a wheel "click" is a
     * press+release pair of `PointerButton.WHEEL_*`. Accumulating the remainder
     * keeps high-resolution trackpads from losing sub-click movement.
     *
     * Positive `vScroll` scrolls down (Android `AXIS_VSCROLL` convention) and
     * positive `hScroll` scrolls right.
     */
    fun handleScroll(vScroll: Float, hScroll: Float) {
        val speed = config.scrollSpeed.sanitisedSpeed()
        if (vScroll.isFinite() && vScroll != 0f) {
            verticalScrollAccumulator += vScroll * speed
            var pulses = 0
            while (verticalScrollAccumulator <= -1f && pulses < MAX_SCROLL_PULSES_PER_EVENT) {
                emitScrollPulse(PointerButton.WHEEL_UP)
                verticalScrollAccumulator += 1f
                pulses++
            }
            pulses = 0
            while (verticalScrollAccumulator >= 1f && pulses < MAX_SCROLL_PULSES_PER_EVENT) {
                emitScrollPulse(PointerButton.WHEEL_DOWN)
                verticalScrollAccumulator -= 1f
                pulses++
            }
        }
        if (hScroll.isFinite() && hScroll != 0f) {
            horizontalScrollAccumulator += hScroll * speed
            var pulses = 0
            while (horizontalScrollAccumulator >= 1f && pulses < MAX_SCROLL_PULSES_PER_EVENT) {
                emitScrollPulse(PointerButton.WHEEL_RIGHT)
                horizontalScrollAccumulator -= 1f
                pulses++
            }
            pulses = 0
            while (horizontalScrollAccumulator <= -1f && pulses < MAX_SCROLL_PULSES_PER_EVENT) {
                emitScrollPulse(PointerButton.WHEEL_LEFT)
                horizontalScrollAccumulator += 1f
                pulses++
            }
        }
    }

    private fun emitScrollPulse(button: PointerButton) {
        val x = cursorX.roundToInt()
        val y = cursorY.roundToInt()
        sink.onPointerButton(button, true, x, y)
        sink.onPointerButton(button, false, x, y)
    }

    // ---------------------------------------------------------------------
    // Release / reset
    // ---------------------------------------------------------------------

    /** Releases every button this processor believes is held. */
    fun releaseAllButtons() {
        lastAndroidButtonState = 0
        if (pressedButtons.isEmpty()) return
        val x = cursorX.roundToInt()
        val y = cursorY.roundToInt()
        for (button in pressedButtons.toList().asReversed()) {
            sink.onPointerButton(button, false, x, y)
        }
        pressedButtons.clear()
    }

    /** Drops pending fractional scroll and button state without emitting. */
    fun resetState() {
        verticalScrollAccumulator = 0f
        horizontalScrollAccumulator = 0f
        pressedButtons.clear()
        lastAndroidButtonState = 0
    }

    // ---------------------------------------------------------------------
    // Curves
    // ---------------------------------------------------------------------

    private fun sensitivityGain(): Float = config.mouseSensitivity.sanitisedSensitivity()

    /**
     * Speed dependent gain applied when [InputConfig.enableMouseAcceleration] is
     * on. Slow movements keep a 1:1 ratio, fast flicks are boosted up to
     * `1 + ACCEL_MAX_BOOST`.
     */
    private fun accelerationGain(distance: Float): Float {
        if (!config.enableMouseAcceleration) return 1f
        val normalised = (distance / ACCEL_REFERENCE_DISTANCE_PX).coerceIn(0f, 1f)
        return 1f + ACCEL_MAX_BOOST * normalised * normalised
    }

    private fun InputConfig.normalised(): InputConfig = copy(
        mouseSensitivity = mouseSensitivity.sanitisedSensitivity(),
        scrollSpeed = scrollSpeed.sanitisedSpeed(),
    )

    private fun Float.sanitisedSensitivity(): Float =
        if (isFinite() && this > 0f) coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) else 1f

    private fun Float.sanitisedSpeed(): Float =
        if (isFinite() && this > 0f) coerceIn(MIN_SCROLL_SPEED, MAX_SCROLL_SPEED) else 1f

    companion object {
        // `MotionEvent.BUTTON_*` values, mirrored so the class stays free of
        // Android imports and therefore testable on a plain JVM.
        const val ANDROID_BUTTON_PRIMARY = 1
        const val ANDROID_BUTTON_SECONDARY = 2
        const val ANDROID_BUTTON_TERTIARY = 4
        const val ANDROID_BUTTON_BACK = 8
        const val ANDROID_BUTTON_FORWARD = 16

        private const val TOUCHPAD_GAIN = 1.5f
        private const val ACCEL_REFERENCE_DISTANCE_PX = 24f
        private const val ACCEL_MAX_BOOST = 0.75f
        private const val MIN_SENSITIVITY = 0.1f
        private const val MAX_SENSITIVITY = 10f
        private const val MIN_SCROLL_SPEED = 0.1f
        private const val MAX_SCROLL_SPEED = 10f
        private const val MAX_SCROLL_PULSES_PER_EVENT = 10
    }
}
