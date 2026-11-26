package com.windroid.app.xserver

import com.windroid.app.core.model.PointerButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for [XServerState]: framebuffer clipping, pixel integrity
 * and the `InputEventSink` → 32-byte X11 packet bridge.
 */
class XServerStateTest {

    private lateinit var state: XServerState
    private lateinit var events: MutableList<ByteArray>
    private lateinit var activities: MutableList<InputActivity>

    @Before
    fun setUp() {
        state = XServerState(width = 8, height = 8)
        events = mutableListOf()
        activities = mutableListOf()
        state.onX11Event = { packet -> events.add(packet) }
        state.onInputActivity = { activity -> activities.add(activity) }
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        (packet[offset].toInt() and 0xFF) or ((packet[offset + 1].toInt() and 0xFF) shl 8)

    // ---------------------------------------------------------------------
    // Framebuffer
    // ---------------------------------------------------------------------

    @Test
    fun `state starts empty with a centred pointer`() {
        assertEquals(8, state.width)
        assertEquals(8, state.height)
        assertEquals(96, state.dpi)
        assertEquals(1, state.rootWindowId)
        assertEquals(4, state.pointerX)
        assertEquals(4, state.pointerY)
        assertEquals(0L, state.frameVersion.get())
        assertEquals(0, state.buttonMask)
        assertEquals(0, state.modifiersMask)
        assertTrue(state.pressedKeycodes.isEmpty())
    }

    @Test
    fun `putImage clips at the left and top edges`() {
        val target = XServerState(4, 4)
        val colour = 0xFF336699.toInt()

        target.putImage(-1, -1, 3, 3, IntArray(9) { colour })

        assertEquals(colour, target.pixelAt(0, 0))
        assertEquals(colour, target.pixelAt(1, 1))
        assertEquals(0, target.pixelAt(2, 2))
        assertEquals(1L, target.frameVersion.get())
    }

    @Test
    fun `putImage keeps row order when clipped on the right and bottom`() {
        val target = XServerState(4, 4)
        val source = IntArray(9) { it + 1 } // row-major 3x3: 1 2 3 / 4 5 6 / 7 8 9

        target.putImage(2, 2, 3, 3, source)

        assertEquals(1, target.pixelAt(2, 2))
        assertEquals(2, target.pixelAt(3, 2))
        assertEquals(4, target.pixelAt(2, 3))
        assertEquals(5, target.pixelAt(3, 3))
        assertEquals(0, target.pixelAt(1, 2))
        assertEquals(1L, target.frameVersion.get())
    }

    @Test
    fun `putImage outside the screen is a no-op`() {
        val target = XServerState(4, 4)

        target.putImage(10, 10, 2, 2, IntArray(4) { 0xFFFFFFFF.toInt() })

        assertEquals(0L, target.frameVersion.get())
        assertEquals(0, target.pixelAt(3, 3))
    }

    @Test
    fun `putImage tolerates a truncated source array`() {
        val target = XServerState(4, 4)

        target.putImage(0, 0, 4, 4, intArrayOf(7, 8))

        assertEquals(7, target.pixelAt(0, 0))
        assertEquals(8, target.pixelAt(1, 0))
        assertEquals(0, target.pixelAt(2, 0))
        assertEquals(0, target.pixelAt(0, 1))
    }

    @Test
    fun `fillRect clips to the framebuffer`() {
        val target = XServerState(4, 4)
        val colour = 0xFF00FF00.toInt()

        target.fillRect(-2, -2, 4, 4, colour)

        assertEquals(colour, target.pixelAt(0, 0))
        assertEquals(colour, target.pixelAt(1, 1))
        assertEquals(0, target.pixelAt(2, 2))
        assertEquals(1L, target.frameVersion.get())
    }

    @Test
    fun `drawRectOutline leaves the interior untouched`() {
        val target = XServerState(6, 6)
        val colour = 0xFF0000FF.toInt()

        target.drawRectOutline(0, 0, 4, 4, colour, thickness = 1)

        assertEquals(colour, target.pixelAt(0, 0))
        assertEquals(colour, target.pixelAt(3, 0))
        assertEquals(colour, target.pixelAt(0, 3))
        assertEquals(colour, target.pixelAt(3, 3))
        assertEquals(0, target.pixelAt(1, 1))
        assertEquals(0, target.pixelAt(2, 2))
        assertEquals(1L, target.frameVersion.get())
    }

    @Test
    fun `copyFramebufferTo returns a snapshot`() {
        val target = XServerState(4, 4)
        target.fillRect(0, 0, 2, 2, 0xFFAA0000.toInt())

        val snapshot = IntArray(16)
        target.copyFramebufferTo(snapshot)

        assertEquals(0xFFAA0000.toInt(), snapshot[0])
        assertEquals(0, snapshot[5])

        target.fillRect(0, 0, 2, 2, 0xFF00AA00.toInt())

        assertEquals(0xFFAA0000.toInt(), snapshot[0])
        assertNotEquals(snapshot[0], target.pixelAt(0, 0))
    }

    // ---------------------------------------------------------------------
    // InputEventSink → X11 packets
    // ---------------------------------------------------------------------

    @Test
    fun `pointer move clamps and emits a motion packet`() {
        state.onPointerMove(100, -5)

        assertEquals(7, state.pointerX)
        assertEquals(0, state.pointerY)

        val packet = events.single()
        assertEquals(32, packet.size)
        assertEquals(X11Protocol.MOTION_NOTIFY, packet[0].toInt())
        assertEquals(0, packet[1].toInt())
        assertEquals(7, u16(packet, 20))
        assertEquals(0, u16(packet, 22))
        assertEquals(7, u16(packet, 24))
        assertEquals(0, u16(packet, 26))
        assertEquals(1, packet[30].toInt())
        assertEquals(InputActivity.PointerMove(7, 0), activities.single())
    }

    @Test
    fun `button press sets the state bit and emits ButtonPress`() {
        state.onPointerButton(PointerButton.LEFT, pressed = true, x = 2, y = 3)

        assertEquals(X11Protocol.BUTTON1_MASK, state.buttonMask)
        assertEquals(2, state.pointerX)
        assertEquals(3, state.pointerY)

        val packet = events.single()
        assertEquals(X11Protocol.BUTTON_PRESS, packet[0].toInt())
        assertEquals(1, packet[1].toInt())
        assertEquals(X11Protocol.BUTTON1_MASK, u16(packet, 28))
        assertEquals(
            InputActivity.PointerButton(PointerButton.LEFT, pressed = true, x = 2, y = 3),
            activities.single(),
        )

        state.onPointerButton(PointerButton.LEFT, pressed = false, x = 2, y = 3)

        assertEquals(0, state.buttonMask)
        assertEquals(X11Protocol.BUTTON_RELEASE, events.last()[0].toInt())
        assertEquals(0, u16(events.last(), 28))
    }

    @Test
    fun `wheel buttons emit events without touching the state mask`() {
        state.onPointerButton(PointerButton.WHEEL_UP, pressed = true, x = 1, y = 1)
        state.onPointerButton(PointerButton.WHEEL_UP, pressed = false, x = 1, y = 1)

        assertEquals(0, state.buttonMask)
        assertEquals(2, events.size)
        assertEquals(X11Protocol.BUTTON_PRESS, events[0][0].toInt())
        assertEquals(4, events[0][1].toInt())
        assertEquals(X11Protocol.BUTTON_RELEASE, events[1][0].toInt())
        assertEquals(4, events[1][1].toInt())
    }

    @Test
    fun `key press tracks the keycode and propagates modifiers`() {
        state.onKeyEvent(
            x11Keycode = 38,
            x11Keysym = 0x61,
            pressed = true,
            modifiersMask = X11Protocol.CONTROL_MASK,
        )

        assertTrue(38 in state.pressedKeycodes)
        assertEquals(X11Protocol.CONTROL_MASK, state.modifiersMask)

        val packet = events.single()
        assertEquals(X11Protocol.KEY_PRESS, packet[0].toInt())
        assertEquals(38, packet[1].toInt())
        assertEquals(X11Protocol.CONTROL_MASK, u16(packet, 28))
        assertEquals(
            InputActivity.Key(38, 0x61, pressed = true, modifiers = X11Protocol.CONTROL_MASK),
            activities.single(),
        )

        state.onKeyEvent(
            x11Keycode = 38,
            x11Keysym = 0x61,
            pressed = false,
            modifiersMask = 0,
        )

        assertTrue(state.pressedKeycodes.isEmpty())
        assertEquals(X11Protocol.KEY_RELEASE, events.last()[0].toInt())
        assertEquals(0, state.modifiersMask)
    }

    @Test
    fun `keycodes are clamped into the X11 range`() {
        state.onKeyEvent(x11Keycode = 2, x11Keysym = 0x1000, pressed = true, modifiersMask = 0)

        assertTrue(X11Protocol.MIN_KEYCODE in state.pressedKeycodes)
        assertEquals(X11Protocol.MIN_KEYCODE, events.single()[1].toInt())

        state.onKeyEvent(x11Keycode = 400, x11Keysym = 0x2000, pressed = true, modifiersMask = 0)

        assertTrue(X11Protocol.MAX_KEYCODE in state.pressedKeycodes)
        assertEquals(X11Protocol.MAX_KEYCODE, events.last()[1].toInt() and 0xFF)
    }

    @Test
    fun `state mask combines buttons and modifiers`() {
        state.onKeyEvent(65, 0x20, pressed = true, modifiersMask = X11Protocol.SHIFT_MASK)
        state.onPointerButton(PointerButton.RIGHT, pressed = true, x = 4, y = 4)

        assertEquals(
            X11Protocol.SHIFT_MASK or X11Protocol.BUTTON3_MASK,
            state.currentStateMask(),
        )
        assertEquals(
            X11Protocol.SHIFT_MASK or X11Protocol.BUTTON3_MASK,
            u16(events.last(), 28),
        )
    }

    @Test
    fun `releaseAllInputs flushes held buttons and keys`() {
        state.onPointerButton(PointerButton.MIDDLE, pressed = true, x = 1, y = 1)
        state.onKeyEvent(x11Keycode = 65, x11Keysym = 0x20, pressed = true, modifiersMask = 0)
        events.clear()
        activities.clear()

        state.onReleaseAllInputs()

        assertEquals(2, events.size)
        assertEquals(X11Protocol.BUTTON_RELEASE, events[0][0].toInt())
        assertEquals(2, events[0][1].toInt())
        assertEquals(X11Protocol.KEY_RELEASE, events[1][0].toInt())
        assertEquals(65, events[1][1].toInt())

        assertEquals(listOf<InputActivity>(InputActivity.ReleaseAll), activities)
        assertEquals(0, state.buttonMask)
        assertEquals(0, state.modifiersMask)
        assertTrue(state.pressedKeycodes.isEmpty())
    }

    @Test
    fun `releaseAllInputs on an idle state only reports the activity`() {
        state.onReleaseAllInputs()

        assertTrue(events.isEmpty())
        assertEquals(listOf<InputActivity>(InputActivity.ReleaseAll), activities)
    }

    @Test
    fun `sequence numbers increase per emitted packet`() {
        state.onPointerMove(1, 1)
        state.onPointerMove(2, 2)

        val first = u16(events[0], 2)
        val second = u16(events[1], 2)

        assertEquals((first + 1) and 0xFFFF, second)
    }
}
