package com.windroid.app.xserver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.PointerButton
import com.windroid.app.core.model.RightClickBehavior
import com.windroid.app.input.KeyboardInputProcessor
import com.windroid.app.input.MouseInputProcessor
import kotlin.math.abs

/**
 * Displays an [XServerState] framebuffer and feeds physical input into it.
 *
 * Rendering runs on a [Choreographer] callback (one frame per vsync) and only
 * re-uploads pixels when [XServerState.frameVersion] changed; the destination
 * rectangle keeps the X11 aspect ratio and is re-derived on every surface size
 * change. A software cursor is painted on top of the bitmap so the pointer is
 * visible even when the guest never draws its own.
 *
 * Input is delegated to the `input` module processors
 * ([MouseInputProcessor] / [KeyboardInputProcessor]); this view only translates
 * Android [MotionEvent]/[KeyEvent] shapes into their normalised calls and owns
 * the pointer-capture lifecycle.
 */
class XServerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var x11State: XServerState? = null
    private var mouseProcessor: MouseInputProcessor? = null
    private var keyboardProcessor: KeyboardInputProcessor? = null

    private var inputConfig: InputConfig = InputConfig()

    private var onHotkeyListener: ((KeyboardInputProcessor.SessionHotkey) -> Unit)? = null
    private var onPointerCaptureListener: ((Boolean) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var frameBuffer: IntArray = IntArray(0)

    private val srcRect = Rect()
    private val destRect = RectF()
    private val lastDrawnDest = RectF(-1f, -1f, -1f, -1f)

    private var lastFrameVersion = -1L
    private var lastPointerX = Int.MIN_VALUE
    private var lastPointerY = Int.MIN_VALUE

    private var rendering = false
    private var pointerCaptureDesired = false

    // Touchpad-fallback gesture state.
    private var touchActive = false
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchMoved = false
    private var twoFingerTap = false

    private val touchSlop: Int by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isAntiAlias = false
        isDither = true
    }

    /** Windows-style arrow, defined once in a 12x19 unit box. */
    private val cursorPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 16.5f)
        lineTo(4.4f, 12.3f)
        lineTo(7.1f, 18.6f)
        lineTo(9.5f, 17.4f)
        lineTo(6.7f, 11.3f)
        lineTo(11.3f, 11.3f)
        close()
    }

    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val cursorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val frameTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!rendering) return
            drawFrame()
            if (rendering) scheduleFrame()
        }
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---------------------------------------------------------------------
    // Session lifecycle
    // ---------------------------------------------------------------------

    /**
     * Binds this view to one session.
     *
     * [onHotkey] receives the keyboard processor's session shortcuts; the
     * `onPointerCaptureChanged` listener mirrors [setPointerCaptured] requests so
     * the activity can hide its toolbar while the pointer is grabbed.
     */
    fun attachSession(
        state: XServerState,
        inputConfig: InputConfig,
        onHotkey: (KeyboardInputProcessor.SessionHotkey) -> Unit,
        onPointerCaptureChanged: (Boolean) -> Unit,
    ) {
        detachSession()

        x11State = state
        this.inputConfig = inputConfig
        onHotkeyListener = onHotkey
        onPointerCaptureListener = onPointerCaptureChanged

        frameBuffer = IntArray(state.width * state.height)
        bitmap = Bitmap.createBitmap(state.width, state.height, Bitmap.Config.ARGB_8888)
        srcRect.set(0, 0, state.width, state.height)
        lastFrameVersion = -1L

        mouseProcessor = MouseInputProcessor(state, inputConfig, state.width, state.height)
        keyboardProcessor = KeyboardInputProcessor(state, inputConfig) { hotkey ->
            onHotkeyListener?.invoke(hotkey)
        }

        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        updateViewportSize(width, height)
        lastFrameVersion = -1L
        startRendering()
    }

    /** Applies a new container input configuration to both processors. */
    fun updateInputConfig(config: InputConfig) {
        inputConfig = config
        mouseProcessor?.updateConfig(config)
        keyboardProcessor?.updateConfig(config)
    }

    /**
     * Enters or leaves relative pointer capture.
     *
     * Android's capture API (O) hides the system pointer and delivers relative
     * deltas through [onCapturedPointerEvent]; the request is remembered so a
     * focus regain can re-arm it.
     */
    fun setPointerCaptured(capture: Boolean) {
        pointerCaptureDesired = capture
        if (capture) {
            if (isAttachedToWindow && !hasPointerCapture()) requestPointerCapture()
        } else if (hasPointerCapture()) {
            releasePointerCapture()
        }
    }

    /** Types a host-side shortcut (e.g. `Ctrl+Alt+Del`) through the keyboard processor. */
    fun sendSyntheticCombo(vararg androidKeyCodes: Int) {
        keyboardProcessor?.sendSyntheticCombo(*androidKeyCodes)
    }

    /** Releases input state and drops every reference to the session. */
    fun detachSession() {
        stopRendering()
        keyboardProcessor?.releaseAllKeys()
        mouseProcessor?.releaseAllButtons()

        keyboardProcessor = null
        mouseProcessor = null
        x11State = null
        onHotkeyListener = null
        onPointerCaptureListener = null
        pointerCaptureDesired = false
        touchActive = false

        bitmap?.recycle()
        bitmap = null
        frameBuffer = IntArray(0)

        lastFrameVersion = -1L
        lastPointerX = Int.MIN_VALUE
        lastPointerY = Int.MIN_VALUE
        lastDrawnDest.set(-1f, -1f, -1f, -1f)
        destRect.setEmpty()
    }

    // ---------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ---------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (x11State != null) {
            lastFrameVersion = -1L
            startRendering()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateViewportSize(width, height)
        lastFrameVersion = -1L
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }

    override fun onDetachedFromWindow() {
        stopRendering()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun startRendering() {
        if (rendering) return
        rendering = true
        postToUi {
            if (rendering) scheduleFrame()
        }
    }

    private fun stopRendering() {
        if (!rendering) return
        rendering = false
        postToUi {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameTick) }
        }
    }

    private fun scheduleFrame() {
        Choreographer.getInstance().postFrameCallback(frameTick)
    }

    private fun postToUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else post { block() }
    }

    private fun updateViewportSize(viewWidth: Int, viewHeight: Int) {
        val state = x11State
        if (state == null || viewWidth <= 0 || viewHeight <= 0) {
            destRect.setEmpty()
            return
        }

        val scale = minOf(viewWidth.toFloat() / state.width, viewHeight.toFloat() / state.height)
        val destWidth = state.width * scale
        val destHeight = state.height * scale
        val left = (viewWidth - destWidth) / 2f
        val top = (viewHeight - destHeight) / 2f
        destRect.set(left, top, left + destWidth, top + destHeight)

        mouseProcessor?.updateViewport(viewWidth, viewHeight)
        lastFrameVersion = -1L
    }

    private fun drawFrame() {
        val state = x11State ?: return
        val frameBitmap = bitmap ?: return
        if (!holder.surface.isValid) return

        val version = state.frameVersion.get()
        val pointerX = state.pointerX
        val pointerY = state.pointerY
        if (version == lastFrameVersion &&
            pointerX == lastPointerX &&
            pointerY == lastPointerY &&
            destRect == lastDrawnDest
        ) {
            return
        }

        val canvas = try {
            holder.lockCanvas()
        } catch (e: RuntimeException) {
            null
        } ?: return

        try {
            canvas.drawColor(Color.BLACK)
            if (!destRect.isEmpty) {
                if (version != lastFrameVersion) {
                    state.copyFramebufferTo(frameBuffer)
                    frameBitmap.setPixels(frameBuffer, 0, state.width, 0, 0, state.width, state.height)
                }
                canvas.drawBitmap(frameBitmap, srcRect, destRect, bitmapPaint)
                drawCursor(canvas, state)
            }
            lastFrameVersion = version
            lastPointerX = pointerX
            lastPointerY = pointerY
            lastDrawnDest.set(destRect)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawCursor(canvas: Canvas, state: XServerState) {
        val scaleX = destRect.width() / state.width
        val scaleY = destRect.height() / state.height
        val x = destRect.left + state.pointerX * scaleX
        val y = destRect.top + state.pointerY * scaleY
        val cursorScale = resources.displayMetrics.density * CURSOR_DP_SCALE

        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(cursorScale, cursorScale)
        canvas.drawPath(cursorPath, cursorFillPaint)
        canvas.drawPath(cursorPath, cursorOutlinePaint)
        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------------
    // Input — captured pointer (relative)
    // ---------------------------------------------------------------------

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        val mouse = mouseProcessor ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                mouse.handleCapturedMotion(event.x, event.y)
                mouse.handleButtonState(event.buttonState)
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vertical != 0f || horizontal != 0f) {
                    mouse.handleScroll(vertical, horizontal)
                }
            }
            MotionEvent.ACTION_SCROLL -> mouse.handleScroll(
                event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            )
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> mouse.handleButtonState(event.buttonState)
            else -> Unit
        }
        return true
    }

    // ---------------------------------------------------------------------
    // Input — generic pointer devices (hover, buttons, wheel)
    // ---------------------------------------------------------------------

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val mouse = mouseProcessor ?: return super.onGenericMotionEvent(event)
        if (!event.isFromPointerDevice()) return super.onGenericMotionEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                mouse.handleAbsoluteMotion(event.x, event.y)
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vertical != 0f || horizontal != 0f) {
                    mouse.handleScroll(vertical, horizontal)
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                mouse.handleScroll(
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                )
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = event.actionButton.toPointerButton()
                if (button != null) {
                    mouse.handleButtonEvent(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------------------------------------------------------------
    // Input — keyboard
    // ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Android delivers a mouse's secondary click as KEYCODE_BACK from SOURCE_MOUSE.
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isFromMouseDevice()) {
            if (inputConfig.rightClickBehavior == RightClickBehavior.X11_RIGHT_BUTTON) {
                mouseProcessor?.handleButtonEvent(PointerButton.RIGHT, true)
                return true
            }
            return false
        }

        val keyboard = keyboardProcessor ?: return super.onKeyDown(keyCode, event)
        val handled = keyboard.handleKeyDown(
            keyCode,
            event.repeatCount,
            event.isCapsLockOn,
            event.isNumLockOn,
        )
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isFromMouseDevice()) {
            if (inputConfig.rightClickBehavior == RightClickBehavior.X11_RIGHT_BUTTON) {
                mouseProcessor?.handleButtonEvent(PointerButton.RIGHT, false)
                return true
            }
            return false
        }

        val keyboard = keyboardProcessor ?: return super.onKeyUp(keyCode, event)
        return keyboard.handleKeyUp(keyCode) || super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------------
    // Input — touchpad fallback
    // ---------------------------------------------------------------------

    /**
     * Virtual touchpad: one finger drags the pointer, a tap clicks the primary
     * button, a two-finger tap clicks the secondary button and a two-finger drag
     * scrolls. Only active while [InputConfig.enableTouchpadFallback] is set.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mouse = mouseProcessor ?: return super.onTouchEvent(event)
        if (!inputConfig.enableTouchpadFallback) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                touchDownTime = event.eventTime
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                touchMoved = false
                twoFingerTap = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingerTap = true
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return true
                if (event.pointerCount >= 2) {
                    val deltaY = event.y - lastTouchY
                    if (abs(deltaY) >= 1f) {
                        mouse.handleScroll(0f, -deltaY / TOUCHPAD_SCROLL_PIXELS_PER_NOTCH)
                        touchMoved = true
                    }
                } else {
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    if (deltaX != 0f || deltaY != 0f) {
                        mouse.handleTouchpadDelta(deltaX, deltaY)
                    }
                    if (abs(event.x - touchDownX) > touchSlop || abs(event.y - touchDownY) > touchSlop) {
                        touchMoved = true
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val isTap = touchActive &&
                    !touchMoved &&
                    (event.eventTime - touchDownTime) <= TAP_TIMEOUT_MS
                if (isTap) {
                    tap(if (twoFingerTap) PointerButton.RIGHT else PointerButton.LEFT)
                }
                touchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun tap(button: PointerButton) {
        val mouse = mouseProcessor ?: return
        mouse.handleButtonEvent(button, true)
        mouse.handleButtonEvent(button, false)
    }

    // ---------------------------------------------------------------------
    // Focus / capture
    // ---------------------------------------------------------------------

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            // Never leave a key or button latched while the session is backgrounded.
            keyboardProcessor?.releaseAllKeys()
            mouseProcessor?.releaseAllButtons()
            x11State?.onReleaseAllInputs()
            touchActive = false
        } else if (pointerCaptureDesired && isAttachedToWindow && !hasPointerCapture()) {
            requestPointerCapture()
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (!hasCapture) {
            mouseProcessor?.releaseAllButtons()
        }
        onPointerCaptureListener?.invoke(hasCapture)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun MotionEvent.isFromPointerDevice(): Boolean {
        val source = source
        return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD ||
            (source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
    }

    private fun KeyEvent.isFromMouseDevice(): Boolean {
        val source = source
        if ((source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true
        val deviceSources = device?.sources ?: return false
        return (deviceSources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
    }

    private fun Int.toPointerButton(): PointerButton? = when (this) {
        MotionEvent.BUTTON_PRIMARY -> PointerButton.LEFT
        MotionEvent.BUTTON_SECONDARY -> PointerButton.RIGHT
        MotionEvent.BUTTON_TERTIARY -> PointerButton.MIDDLE
        MotionEvent.BUTTON_BACK -> PointerButton.BACK
        MotionEvent.BUTTON_FORWARD -> PointerButton.FORWARD
        else -> null
    }

    private companion object {
        /** Tap must land inside this window to count as a click. */
        const val TAP_TIMEOUT_MS = 250L

        /** Pixels of two-finger drag that translate into one wheel notch. */
        const val TOUCHPAD_SCROLL_PIXELS_PER_NOTCH = 64f

        /** Cursor box scale, relative to one dp. */
        const val CURSOR_DP_SCALE = 1.0f
    }
}
