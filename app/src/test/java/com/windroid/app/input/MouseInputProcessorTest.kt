package com.windroid.app.input

import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.InputEventSink
import com.windroid.app.core.model.MouseMode
import com.windroid.app.core.model.PointerButton
import com.windroid.app.core.model.RightClickBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [MouseInputProcessor].
 *
 * Everything asserted here is arithmetic on floats and integers, so the tests
 * run without Robolectric: the sink is a recording fake and the "Android"
 * button flags are the literal `MotionEvent.BUTTON_*` values.
 */
class MouseInputProcessorTest {

    private data class ButtonEvent(
        val button: PointerButton,
        val pressed: Boolean,
        val x: Int,
        val y: Int,
    )

    private class RecordingSink : InputEventSink {
        val moves = mutableListOf<Pair<Int, Int>>()
        val buttons = mutableListOf<ButtonEvent>()
        val keys = mutableListOf<Triple<Int, Boolean, Int>>()
        var releaseAllCount = 0

        override fun onPointerMove(x: Int, y: Int) {
            moves += x to y
        }

        override fun onPointerButton(button: PointerButton, pressed: Boolean, x: Int, y: Int) {
            buttons += ButtonEvent(button, pressed, x, y)
        }

        override fun onKeyEvent(
            x11Keycode: Int,
            x11Keysym: Int,
            pressed: Boolean,
            modifiersMask: Int,
        ) {
            keys += Triple(x11Keycode, pressed, modifiersMask)
        }

        override fun onReleaseAllInputs() {
            releaseAllCount++
        }
    }

    private val sink = RecordingSink()

    private fun processor(
        config: InputConfig = InputConfig(),
        sink: RecordingSink = this.sink,
    ): MouseInputProcessor = MouseInputProcessor(sink, config, SCREEN_WIDTH, SCREEN_HEIGHT)

    // ---------------------------------------------------------------------
    // Initial state & viewport
    // ---------------------------------------------------------------------

    @Test
    fun `starts centred with a full screen viewport`() {
        val mouse = processor()

        assertEquals(SCREEN_WIDTH, mouse.screenWidth)
        assertEquals(SCREEN_HEIGHT, mouse.screenHeight)
        assertEquals(639.5f, mouse.cursorX, 0.001f)
        assertEquals(359.5f, mouse.cursorY, 0.001f)

        val viewport = mouse.viewport
        assertEquals(0f, viewport.left, 0.001f)
        assertEquals(0f, viewport.top, 0.001f)
        assertEquals(1280f, viewport.width, 0.001f)
        assertEquals(720f, viewport.height, 0.001f)
        assertTrue(viewport.contains(10f, 10f))
        assertFalse(viewport.isEmpty)
    }

    @Test
    fun `square surface letterboxes a wide screen`() {
        val mouse = processor()
        mouse.updateViewport(1000, 1000)

        val viewport = mouse.viewport
        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(218.75f, viewport.top, 0.01f)
        assertEquals(1000f, viewport.width, 0.01f)
        assertEquals(562.5f, viewport.height, 0.01f)
        assertEquals(781.25f, viewport.bottom, 0.01f)
    }

    @Test
    fun `square surface pillarboxes a tall screen`() {
        val mouse = MouseInputProcessor(sink, InputConfig(), 720, 1280)
        mouse.updateViewport(1000, 1000)

        val viewport = mouse.viewport
        assertEquals(218.75f, viewport.left, 0.01f)
        assertEquals(0f, viewport.top, 0.01f)
        assertEquals(562.5f, viewport.width, 0.01f)
        assertEquals(1000f, viewport.height, 0.01f)
        assertEquals(781.25f, viewport.right, 0.01f)
    }

    @Test
    fun `unmeasured surface yields an empty viewport`() {
        val mouse = processor()
        mouse.updateViewport(0, 0)
        assertTrue(mouse.viewport.isEmpty)
    }

    // ---------------------------------------------------------------------
    // Captured relative motion
    // ---------------------------------------------------------------------

    @Test
    fun `captured motion applies sensitivity and emits`() {
        val mouse = processor(InputConfig(mouseSensitivity = 2f))

        mouse.handleCapturedMotion(10f, -5f)

        assertEquals(659.5f, mouse.cursorX, 0.001f)
        assertEquals(349.5f, mouse.cursorY, 0.001f)
        assertEquals(listOf(660 to 350), sink.moves)
    }

    @Test
    fun `captured motion clamps to the screen bounds`() {
        val mouse = processor()

        mouse.handleCapturedMotion(5000f, 5000f)
        assertEquals(1279f, mouse.cursorX, 0.001f)
        assertEquals(719f, mouse.cursorY, 0.001f)
        assertEquals(1279 to 719, sink.moves.last())

        mouse.handleCapturedMotion(-5000f, -5000f)
        assertEquals(0f, mouse.cursorX, 0.001f)
        assertEquals(0f, mouse.cursorY, 0.001f)
        assertEquals(0 to 0, sink.moves.last())
    }

    @Test
    fun `captured motion rounds the emitted position`() {
        val mouse = processor()
        mouse.handleCapturedMotion(0f, 0f)
        assertEquals(640 to 360, sink.moves.last())
    }

    @Test
    fun `acceleration boosts fast flicks only`() {
        val plain = processor(InputConfig())
        val accelerated = MouseInputProcessor(
            RecordingSink(),
            InputConfig(enableMouseAcceleration = true),
            SCREEN_WIDTH,
            SCREEN_HEIGHT,
        )

        plain.handleCapturedMotion(48f, 0f)
        accelerated.handleCapturedMotion(48f, 0f)

        assertTrue(
            "accelerated pointer should travel further",
            accelerated.cursorX > plain.cursorX,
        )
        // 1 + ACCEL_MAX_BOOST once the delta saturates the reference distance.
        assertEquals(639.5f + 84f, accelerated.cursorX, 0.01f)
    }

    @Test
    fun `nan deltas are ignored`() {
        val mouse = processor()
        mouse.handleCapturedMotion(Float.NaN, 1f)
        assertTrue(sink.moves.isEmpty())
    }

    @Test
    fun `sensitivity is sanitised when invalid`() {
        val mouse = processor(InputConfig(mouseSensitivity = Float.NaN))
        assertEquals(1f, mouse.config.mouseSensitivity, 0.001f)

        mouse.updateConfig(InputConfig(mouseSensitivity = -3f))
        assertEquals(1f, mouse.config.mouseSensitivity, 0.001f)

        mouse.updateConfig(InputConfig(mouseSensitivity = 99f))
        assertEquals(10f, mouse.config.mouseSensitivity, 0.001f)
    }

    // ---------------------------------------------------------------------
    // Absolute motion
    // ---------------------------------------------------------------------

    @Test
    fun `absolute motion maps the full viewport onto the screen`() {
        val mouse = processor()

        mouse.handleAbsoluteMotion(0f, 0f)
        assertEquals(0f, mouse.cursorX, 0.001f)
        assertEquals(0f, mouse.cursorY, 0.001f)

        mouse.handleAbsoluteMotion(SCREEN_WIDTH.toFloat(), SCREEN_HEIGHT.toFloat())
        assertEquals(1279f, mouse.cursorX, 0.001f)
        assertEquals(719f, mouse.cursorY, 0.001f)

        mouse.handleAbsoluteMotion(640f, 360f)
        assertEquals(640 to 360, sink.moves.last())
    }

    @Test
    fun `absolute motion is clamped inside the letterbox bars`() {
        val mouse = processor()
        mouse.updateViewport(1000, 1000)

        mouse.handleAbsoluteMotion(500f, 10f)
        assertEquals(0f, mouse.cursorY, 0.001f)

        mouse.handleAbsoluteMotion(500f, 500f)
        assertEquals(360, sink.moves.last().second)

        mouse.handleAbsoluteMotion(-50f, 500f)
        assertEquals(0f, mouse.cursorX, 0.001f)
    }

    // ---------------------------------------------------------------------
    // Touchpad fallback
    // ---------------------------------------------------------------------

    @Test
    fun `touchpad delta moves the cursor when the fallback is enabled`() {
        val mouse = processor()
        mouse.handleTouchpadDelta(10f, 0f)

        assertEquals(639.5f + 15f, mouse.cursorX, 0.001f)
        assertEquals(655, sink.moves.last().first)
    }

    @Test
    fun `touchpad delta is ignored when the fallback is disabled`() {
        val mouse = processor(InputConfig(enableTouchpadFallback = false))
        mouse.handleTouchpadDelta(10f, 10f)

        assertTrue(sink.moves.isEmpty())
        assertEquals(639.5f, mouse.cursorX, 0.001f)
    }

    // ---------------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------------

    @Test
    fun `button state diffing emits press and release`() {
        val mouse = processor()

        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_PRIMARY)
        val press = sink.buttons.single()
        assertTrue(press.pressed)
        assertEquals(PointerButton.LEFT, press.button)
        assertEquals(640, press.x)
        assertEquals(360, press.y)

        mouse.handleButtonState(0)
        assertFalse(sink.buttons.last().pressed)
        assertEquals(PointerButton.LEFT, sink.buttons.last().button)
    }

    @Test
    fun `unchanged button state emits nothing`() {
        val mouse = processor()
        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_PRIMARY)
        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_PRIMARY)

        assertEquals(1, sink.buttons.size)
    }

    @Test
    fun `all button flags are mapped`() {
        val mouse = processor()

        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_TERTIARY)
        assertEquals(PointerButton.MIDDLE, sink.buttons.last().button)

        mouse.handleButtonState(0)
        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_BACK)
        assertEquals(PointerButton.BACK, sink.buttons.last().button)

        mouse.handleButtonState(0)
        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_FORWARD)
        assertEquals(PointerButton.FORWARD, sink.buttons.last().button)
    }

    @Test
    fun `secondary click becomes the X11 right button by default`() {
        val mouse = processor()
        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_SECONDARY)

        assertEquals(PointerButton.RIGHT, sink.buttons.single().button)
        assertTrue(sink.buttons.single().pressed)
    }

    @Test
    fun `secondary click can be routed to android back`() {
        val mouse = processor(InputConfig(rightClickBehavior = RightClickBehavior.ANDROID_BACK))
        var backRequests = 0
        mouse.onAndroidBackRequested = { backRequests++ }

        mouse.handleButtonState(MouseInputProcessor.ANDROID_BUTTON_SECONDARY)
        mouse.handleButtonState(0)

        assertEquals(1, backRequests)
        assertTrue(sink.buttons.isEmpty())
    }

    @Test
    fun `button events are deduplicated`() {
        val mouse = processor()
        mouse.handleButtonEvent(PointerButton.LEFT, true)
        mouse.handleButtonEvent(PointerButton.LEFT, true)
        mouse.handleButtonEvent(PointerButton.LEFT, false)
        mouse.handleButtonEvent(PointerButton.LEFT, false)

        assertEquals(2, sink.buttons.size)
    }

    @Test
    fun `releaseAllButtons releases every held button in reverse order`() {
        val mouse = processor()
        mouse.handleButtonEvent(PointerButton.LEFT, true)
        mouse.handleButtonEvent(PointerButton.MIDDLE, true)
        sink.buttons.clear()

        mouse.releaseAllButtons()

        assertEquals(
            listOf(PointerButton.MIDDLE, PointerButton.LEFT),
            sink.buttons.map { it.button },
        )
        assertTrue(sink.buttons.none { it.pressed })

        mouse.releaseAllButtons()
        assertEquals(2, sink.buttons.size)
    }

    // ---------------------------------------------------------------------
    // Scrolling
    // ---------------------------------------------------------------------

    @Test
    fun `scroll accumulates fractional deltas`() {
        val mouse = processor()

        mouse.handleScroll(-0.4f, 0f)
        mouse.handleScroll(-0.4f, 0f)
        assertTrue("half a click must not fire yet", sink.buttons.isEmpty())

        mouse.handleScroll(-0.4f, 0f)
        assertEquals(2, sink.buttons.size)
        assertEquals(PointerButton.WHEEL_UP, sink.buttons.first().button)
        assertTrue(sink.buttons.first().pressed)
        assertFalse(sink.buttons.last().pressed)
    }

    @Test
    fun `scroll speed scales the accumulator`() {
        val mouse = processor(InputConfig(scrollSpeed = 2f))
        mouse.handleScroll(-0.5f, 0f)

        assertEquals(2, sink.buttons.size)
        assertEquals(PointerButton.WHEEL_UP, sink.buttons.first().button)
    }

    @Test
    fun `positive vertical scroll emits wheel down`() {
        val mouse = processor()
        mouse.handleScroll(1.5f, 0f)

        assertEquals(PointerButton.WHEEL_DOWN, sink.buttons.first().button)
        assertEquals(2, sink.buttons.size)
    }

    @Test
    fun `horizontal scroll emits the sideways wheels`() {
        val mouse = processor()

        mouse.handleScroll(0f, 1.1f)
        assertEquals(PointerButton.WHEEL_RIGHT, sink.buttons.first().button)

        sink.buttons.clear()
        mouse.handleScroll(0f, -1.1f)
        assertEquals(PointerButton.WHEEL_LEFT, sink.buttons.first().button)
    }

    // ---------------------------------------------------------------------
    // Screen changes
    // ---------------------------------------------------------------------

    @Test
    fun `changing the screen size clamps the cursor`() {
        val mouse = processor()
        mouse.handleCapturedMotion(5000f, 5000f)

        mouse.updateScreenSize(640, 480)

        assertEquals(640, mouse.screenWidth)
        assertEquals(480, mouse.screenHeight)
        assertEquals(639f, mouse.cursorX, 0.001f)
        assertEquals(479f, mouse.cursorY, 0.001f)
    }

    @Test
    fun `mode helper reports the configured mouse mode`() {
        val mouse = processor()
        assertFalse(mouse.isCapturedRelativeMode)

        mouse.updateConfig(InputConfig(mouseMode = MouseMode.CAPTURED_RELATIVE))
        assertTrue(mouse.isCapturedRelativeMode)
    }

    private companion object {
        const val SCREEN_WIDTH = 1280
        const val SCREEN_HEIGHT = 720
    }
}
