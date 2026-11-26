package com.windroid.app.xserver

import com.windroid.app.core.model.InputEventSink
import com.windroid.app.core.model.PointerButton
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Normalised host input, surfaced next to the X11 packets.
 *
 * The HUD and diagnostics subscribe to this instead of decoding the wire format;
 * the socket host keeps using [XServerState.onX11Event].
 */
sealed class InputActivity {

    data class PointerMove(val x: Int, val y: Int) : InputActivity()

    data class PointerButton(
        val button: com.windroid.app.core.model.PointerButton,
        val pressed: Boolean,
        val x: Int,
        val y: Int,
    ) : InputActivity()

    data class Key(
        val keycode: Int,
        val keysym: Int,
        val pressed: Boolean,
        val modifiers: Int,
    ) : InputActivity()

    data object ReleaseAll : InputActivity()
}

/**
 * Authoritative X11 display state for one WinDroid session: a thread-safe ARGB
 * framebuffer plus the pointer/keyboard state the protocol needs.
 *
 * Deliberately Android-free so it can run under plain JVM unit tests. The only
 * threading rule is that the framebuffer writers ([putImage], [fillRect],
 * [drawRectOutline], [copyFramebufferTo]) serialise on one monitor: the socket
 * host writes pixels from IO threads while the renderer copies the frame on the
 * UI thread.
 *
 * The [InputEventSink] callbacks are driven from the UI thread by the input
 * processors.
 */
class XServerState(
    val width: Int,
    val height: Int,
    val dpi: Int = 96,
) : InputEventSink {

    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(dpi > 0) { "dpi must be positive, was $dpi" }
    }

    /** Root window id advertised in the connection setup reply. */
    val rootWindowId: Int = X11Protocol.ROOT_WINDOW_ID

    /** TrueColor visual id advertised in the connection setup reply. */
    val visualId: Int = X11Protocol.DEFAULT_VISUAL_ID

    /**
     * Incremented on every framebuffer mutation. The renderer compares it with
     * the version it last uploaded and skips untouched frames.
     */
    val frameVersion = AtomicLong(0)

    private val pixels = IntArray(width * height)
    private val lock = Any()

    /** Pointer position in X11 screen coordinates, always inside the framebuffer. */
    @Volatile
    var pointerX: Int = width / 2
        private set

    @Volatile
    var pointerY: Int = height / 2
        private set

    /**
     * X11 `state` button bits (`Button1Mask` = `0x0100` … `Button5Mask` = `0x1000`).
     *
     * Wheel buttons 6..9 have no `state` bit in the core protocol, so they emit
     * press/release events without ever setting a bit here.
     */
    @Volatile
    var buttonMask: Int = 0
        private set

    /** X11 modifier bits (`ShiftMask` = 1 … `Mod5Mask` = 0x80). */
    @Volatile
    var modifiersMask: Int = 0
        private set

    @Volatile
    private var pressedKeymap: Map<Int, Int> = emptyMap()

    /** Keycodes currently held down; the values keep their keysyms for releases. */
    val pressedKeycodes: Set<Int>
        get() = pressedKeymap.keys

    /** Sink for ready-to-send 32-byte X11 event packets. */
    @Volatile
    var onX11Event: ((ByteArray) -> Unit)? = null

    /** Sink for decoded input activity (HUD, analytics, tests). */
    @Volatile
    var onInputActivity: ((InputActivity) -> Unit)? = null

    private val sequence = AtomicInteger(0)

    // ---------------------------------------------------------------------
    // Framebuffer
    // ---------------------------------------------------------------------

    /**
     * Blits a caller-owned ARGB pixel rectangle into the framebuffer.
     *
     * The rectangle is clipped against the screen on all four sides;
     * [argbPixels] is treated as `w * h` row-major pixels starting at its first
     * element. Requests that land entirely outside the screen are no-ops and do
     * not bump [frameVersion].
     */
    fun putImage(x: Int, y: Int, w: Int, h: Int, argbPixels: IntArray) {
        if (w <= 0 || h <= 0 || argbPixels.isEmpty()) return

        synchronized(lock) {
            val left = maxOf(0, x)
            val top = maxOf(0, y)
            val right = minOf(width, x + w)
            val bottom = minOf(height, y + h)
            if (left >= right || top >= bottom) return

            var copied = false
            for (row in top until bottom) {
                val sourceRow = row - y
                val sourceStart = sourceRow * w + (left - x)
                val available = argbPixels.size - sourceStart
                if (available <= 0) break
                val count = minOf(right - left, available)
                if (count <= 0) continue
                System.arraycopy(argbPixels, sourceStart, pixels, row * width + left, count)
                copied = true
            }
            if (copied) frameVersion.incrementAndGet()
        }
    }

    /** Fills a clipped rectangle with a solid ARGB colour. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(lock) {
            if (fillRectLocked(x, y, w, h, color)) {
                frameVersion.incrementAndGet()
            }
        }
    }

    /** Fills the whole framebuffer; used for the first paint and session resets. */
    fun clear(color: Int) = fillRect(0, 0, width, height, color)

    /**
     * Draws a rectangular outline of [thickness] pixels.
     *
     * When the rectangle is too small for four distinct edges the whole area is
     * filled instead, so a `2x2` window still shows something.
     */
    fun drawRectOutline(x: Int, y: Int, w: Int, h: Int, color: Int, thickness: Int = 1) {
        if (w <= 0 || h <= 0) return
        val t = thickness.coerceAtLeast(1)
        synchronized(lock) {
            if (w <= 2 * t || h <= 2 * t) {
                if (fillRectLocked(x, y, w, h, color)) {
                    frameVersion.incrementAndGet()
                }
                return
            }
            var changed = fillRectLocked(x, y, w, t, color)
            changed = fillRectLocked(x, y + h - t, w, t, color) or changed
            changed = fillRectLocked(x, y + t, t, h - 2 * t, color) or changed
            changed = fillRectLocked(x + w - t, y + t, t, h - 2 * t, color) or changed
            if (changed) frameVersion.incrementAndGet()
        }
    }

    /**
     * Copies a consistent snapshot of the framebuffer into [dest].
     *
     * [dest] must hold at least `width * height` pixels; larger arrays only get
     * the leading `width * height` entries written.
     */
    fun copyFramebufferTo(dest: IntArray) {
        synchronized(lock) {
            System.arraycopy(pixels, 0, dest, 0, minOf(dest.size, pixels.size))
        }
    }

    /** Pixel read-back for tests and diagnostics. Out-of-bounds reads return 0. */
    internal fun pixelAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        synchronized(lock) {
            return pixels[y * width + x]
        }
    }

    /** Caller holds [lock]; returns whether anything was written. */
    private fun fillRectLocked(x: Int, y: Int, w: Int, h: Int, color: Int): Boolean {
        val left = maxOf(0, x)
        val top = maxOf(0, y)
        val right = minOf(width, x + w)
        val bottom = minOf(height, y + h)
        if (left >= right || top >= bottom) return false
        for (row in top until bottom) {
            val start = row * width
            java.util.Arrays.fill(pixels, start + left, start + right, color)
        }
        return true
    }

    // ---------------------------------------------------------------------
    // InputEventSink
    // ---------------------------------------------------------------------

    override fun onPointerMove(x: Int, y: Int) {
        val clampedX = x.coerceIn(0, width - 1)
        val clampedY = y.coerceIn(0, height - 1)
        pointerX = clampedX
        pointerY = clampedY

        emit(
            X11Protocol.buildMotionEvent(
                sequence = nextSequence(),
                time = now(),
                rootWindowId = rootWindowId,
                eventWindowId = rootWindowId,
                rootX = clampedX,
                rootY = clampedY,
                eventX = clampedX,
                eventY = clampedY,
                state = currentStateMask(),
            ),
            InputActivity.PointerMove(clampedX, clampedY),
        )
    }

    override fun onPointerButton(button: PointerButton, pressed: Boolean, x: Int, y: Int) {
        val clampedX = x.coerceIn(0, width - 1)
        val clampedY = y.coerceIn(0, height - 1)
        pointerX = clampedX
        pointerY = clampedY

        val bit = X11Protocol.buttonStateMask(button.x11Button)
        if (bit != 0) {
            buttonMask = if (pressed) buttonMask or bit else buttonMask and bit.inv()
        }

        emit(
            X11Protocol.buildButtonEvent(
                eventCode = if (pressed) X11Protocol.BUTTON_PRESS else X11Protocol.BUTTON_RELEASE,
                button = button.x11Button,
                sequence = nextSequence(),
                time = now(),
                rootWindowId = rootWindowId,
                eventWindowId = rootWindowId,
                rootX = clampedX,
                rootY = clampedY,
                eventX = clampedX,
                eventY = clampedY,
                state = currentStateMask(),
            ),
            InputActivity.PointerButton(button, pressed, clampedX, clampedY),
        )
    }

    override fun onKeyEvent(
        x11Keycode: Int,
        x11Keysym: Int,
        pressed: Boolean,
        modifiersMask: Int,
    ) {
        val keycode = x11Keycode.coerceIn(X11Protocol.MIN_KEYCODE, X11Protocol.MAX_KEYCODE)
        this.modifiersMask = modifiersMask and X11Protocol.ALL_MODIFIER_MASK
        pressedKeymap = if (pressed) {
            pressedKeymap + (keycode to x11Keysym)
        } else {
            pressedKeymap - keycode
        }

        emit(
            X11Protocol.buildKeyEvent(
                eventCode = if (pressed) X11Protocol.KEY_PRESS else X11Protocol.KEY_RELEASE,
                keycode = keycode,
                sequence = nextSequence(),
                time = now(),
                rootWindowId = rootWindowId,
                eventWindowId = rootWindowId,
                rootX = pointerX,
                rootY = pointerY,
                eventX = pointerX,
                eventY = pointerY,
                state = currentStateMask(),
            ),
            InputActivity.Key(keycode, x11Keysym, pressed, this.modifiersMask),
        )
    }

    override fun onReleaseAllInputs() {
        val buttons = buttonMask
        val heldKeys = pressedKeymap
        val modifiers = modifiersMask
        val x = pointerX
        val y = pointerY
        val time = now()

        for (button in 1..9) {
            val bit = X11Protocol.buttonStateMask(button)
            if (bit == 0 || buttons and bit == 0) continue
            emit(
                X11Protocol.buildButtonEvent(
                    eventCode = X11Protocol.BUTTON_RELEASE,
                    button = button,
                    sequence = nextSequence(),
                    time = time,
                    rootWindowId = rootWindowId,
                    eventWindowId = rootWindowId,
                    rootX = x,
                    rootY = y,
                    eventX = x,
                    eventY = y,
                    state = modifiers or (buttons and bit.inv()),
                ),
                null,
            )
        }

        for ((keycode, _) in heldKeys.toSortedMap()) {
            emit(
                X11Protocol.buildKeyEvent(
                    eventCode = X11Protocol.KEY_RELEASE,
                    keycode = keycode,
                    sequence = nextSequence(),
                    time = time,
                    rootWindowId = rootWindowId,
                    eventWindowId = rootWindowId,
                    rootX = x,
                    rootY = y,
                    eventX = x,
                    eventY = y,
                    state = modifiers,
                ),
                null,
            )
        }

        buttonMask = 0
        modifiersMask = 0
        pressedKeymap = emptyMap()

        onInputActivity?.invoke(InputActivity.ReleaseAll)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Combined modifier + button bits as carried in the `state` field. */
    fun currentStateMask(): Int = modifiersMask or buttonMask

    private fun emit(packet: ByteArray, activity: InputActivity?) {
        onX11Event?.invoke(packet)
        if (activity != null) onInputActivity?.invoke(activity)
    }

    private fun nextSequence(): Int = sequence.incrementAndGet() and 0xFFFF

    private fun now(): Int = ((System.nanoTime() / 1_000_000L) and 0xFFFF_FFFFL).toInt()
}
