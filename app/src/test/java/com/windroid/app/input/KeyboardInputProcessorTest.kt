package com.windroid.app.input

import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.InputEventSink
import com.windroid.app.core.model.PointerButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [KeyboardInputProcessor].
 *
 * Android key codes are numeric literals so the tests never touch a stubbed
 * Android method; every assertion is on the recorded [InputEventSink] traffic.
 */
class KeyboardInputProcessorTest {

    private object Keys {
        const val A = 29
        const val C = 31
        const val G = 35
        const val M = 41
        const val Q = 45
        const val V = 50
        const val ALT_LEFT = 57
        const val SHIFT_LEFT = 59
        const val MENU = 82
        const val CTRL_LEFT = 113
        const val CAPS_LOCK = 115
        const val NUM_LOCK = 143
    }

    private data class KeyRecord(
        val keycode: Int,
        val keysym: Int,
        val pressed: Boolean,
        val modifiers: Int,
    )

    private class RecordingSink : InputEventSink {
        val keys = mutableListOf<KeyRecord>()
        val moves = mutableListOf<Pair<Int, Int>>()
        val buttons = mutableListOf<PointerButton>()
        var releaseAllCount = 0

        override fun onPointerMove(x: Int, y: Int) {
            moves += x to y
        }

        override fun onPointerButton(button: PointerButton, pressed: Boolean, x: Int, y: Int) {
            buttons += button
        }

        override fun onKeyEvent(
            x11Keycode: Int,
            x11Keysym: Int,
            pressed: Boolean,
            modifiersMask: Int,
        ) {
            keys += KeyRecord(x11Keycode, x11Keysym, pressed, modifiersMask)
        }

        override fun onReleaseAllInputs() {
            releaseAllCount++
        }
    }

    private val sink = RecordingSink()
    private val hotkeys = mutableListOf<KeyboardInputProcessor.SessionHotkey>()

    private fun processor(config: InputConfig = InputConfig()): KeyboardInputProcessor =
        KeyboardInputProcessor(sink, config) { hotkeys += it }

    // ---------------------------------------------------------------------
    // Basic translation
    // ---------------------------------------------------------------------

    @Test
    fun `letter press and release are mapped to x11 keycode and keysym`() {
        val keyboard = processor()

        assertTrue(keyboard.handleKeyDown(Keys.A, repeatCount = 0))
        assertEquals(listOf(KeyRecord(38, 0x61, true, 0)), sink.keys)

        assertTrue(keyboard.handleKeyUp(Keys.A))
        assertEquals(KeyRecord(38, 0x61, false, 0), sink.keys.last())
        assertTrue(keyboard.pressedKeyCodes.isEmpty())
    }

    @Test
    fun `unmapped keys are not consumed`() {
        val keyboard = processor()

        assertFalse(keyboard.handleKeyDown(Keys.MENU, repeatCount = 0))
        assertFalse(keyboard.handleKeyUp(Keys.MENU))
        assertTrue(sink.keys.isEmpty())
    }

    @Test
    fun `shift produces the uppercase keysym and sets the mask`() {
        val keyboard = processor()

        assertTrue(keyboard.handleKeyDown(Keys.SHIFT_LEFT, repeatCount = 0))
        assertEquals(X11KeyMap.SHIFT_MASK, keyboard.modifierMask)

        sink.keys.clear()
        keyboard.handleKeyDown(Keys.A, repeatCount = 0)
        assertEquals(KeyRecord(38, 0x41, true, X11KeyMap.SHIFT_MASK), sink.keys.single())
    }

    @Test
    fun `releasing a modifier clears its bit`() {
        val keyboard = processor()

        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        assertEquals(X11KeyMap.CONTROL_MASK, keyboard.modifierMask)

        keyboard.handleKeyUp(Keys.CTRL_LEFT)
        assertEquals(0, keyboard.modifierMask)
    }

    @Test
    fun `both ctrl and alt set their x11 masks`() {
        val keyboard = processor()

        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.ALT_LEFT, repeatCount = 0)

        assertEquals(
            X11KeyMap.CONTROL_MASK or X11KeyMap.MOD1_MASK,
            keyboard.modifierMask,
        )
    }

    @Test
    fun `caps lock toggles and flips letter case`() {
        val keyboard = processor()

        assertTrue(keyboard.handleKeyDown(Keys.CAPS_LOCK, repeatCount = 0))
        assertTrue(keyboard.isCapsLockOn)
        assertEquals(X11KeyMap.LOCK_MASK, keyboard.modifierMask)

        // The default lock hint must not clear the tracked lock.
        sink.keys.clear()
        keyboard.handleKeyDown(Keys.A, repeatCount = 0)
        assertEquals(0x41, sink.keys.single().keysym)

        // Shift + Caps Lock cancels out for letters.
        keyboard.handleKeyDown(Keys.SHIFT_LEFT, repeatCount = 0)
        sink.keys.clear()
        keyboard.handleKeyDown(Keys.C, repeatCount = 0)
        assertEquals(0x63, sink.keys.single().keysym)
    }

    @Test
    fun `syncLockState seeds the lock mirror`() {
        val keyboard = processor()

        keyboard.syncLockState(isCapsLockOn = true, isNumLockOn = true)

        assertTrue(keyboard.isCapsLockOn)
        assertTrue(keyboard.isNumLockOn)

        keyboard.handleKeyDown(Keys.A, repeatCount = 0)
        assertEquals(0x41, sink.keys.single().keysym)
    }

    @Test
    fun `num lock toggles and sets mod2`() {
        val keyboard = processor()

        assertTrue(keyboard.handleKeyDown(Keys.NUM_LOCK, repeatCount = 0))
        assertTrue(keyboard.isNumLockOn)
        assertEquals(X11KeyMap.MOD2_MASK, keyboard.modifierMask)
    }

    // ---------------------------------------------------------------------
    // Hotkeys
    // ---------------------------------------------------------------------

    @Test
    fun `ctrl alt g toggles pointer capture and is not forwarded`() {
        val keyboard = processor()
        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.ALT_LEFT, repeatCount = 0)
        sink.keys.clear()

        assertTrue(keyboard.handleKeyDown(Keys.G, repeatCount = 0))

        assertEquals(
            listOf(KeyboardInputProcessor.SessionHotkey.TOGGLE_POINTER_CAPTURE),
            hotkeys,
        )
        assertTrue("the trigger key must not reach Windows", sink.keys.isEmpty())

        // The matching UP is swallowed as well.
        assertTrue(keyboard.handleKeyUp(Keys.G))
        assertTrue(sink.keys.isEmpty())
        assertFalse(keyboard.pressedKeyCodes.contains(Keys.G))
    }

    @Test
    fun `ctrl alt m toggles the hud`() {
        val keyboard = processor()
        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.ALT_LEFT, repeatCount = 0)

        assertTrue(keyboard.handleKeyDown(Keys.M, repeatCount = 0))

        assertEquals(listOf(KeyboardInputProcessor.SessionHotkey.TOGGLE_HUD), hotkeys)
    }

    @Test
    fun `ctrl alt shift q exits the session`() {
        val keyboard = processor()
        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.ALT_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.SHIFT_LEFT, repeatCount = 0)
        sink.keys.clear()

        assertTrue(keyboard.handleKeyDown(Keys.Q, repeatCount = 0))

        assertEquals(listOf(KeyboardInputProcessor.SessionHotkey.EXIT_SESSION), hotkeys)
        assertTrue(sink.keys.isEmpty())
    }

    @Test
    fun `ctrl alt q without shift is forwarded to windows`() {
        val keyboard = processor()
        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.ALT_LEFT, repeatCount = 0)
        sink.keys.clear()

        assertTrue(keyboard.handleKeyDown(Keys.Q, repeatCount = 0))

        assertTrue(hotkeys.isEmpty())
        assertEquals(
            KeyRecord(24, 0x71, true, X11KeyMap.CONTROL_MASK or X11KeyMap.MOD1_MASK),
            sink.keys.single(),
        )
    }

    @Test
    fun `chords without ctrl and alt are not hotkeys`() {
        val keyboard = processor()
        keyboard.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)

        assertTrue(keyboard.handleKeyDown(Keys.G, repeatCount = 0))

        assertTrue(hotkeys.isEmpty())
        assertEquals(42, sink.keys.last().keycode)
    }

    @Test
    fun `auto repeat forwards the press but does not fire the hotkey twice`() {
        val keyboard = processor()

        keyboard.handleKeyDown(Keys.A, repeatCount = 0)
        keyboard.handleKeyDown(Keys.A, repeatCount = 1)

        assertEquals(2, sink.keys.count { it.keycode == 38 && it.pressed })
        assertEquals(1, keyboard.pressedKeyCodes.size)

        val chorded = processor()
        chorded.handleKeyDown(Keys.CTRL_LEFT, repeatCount = 0)
        chorded.handleKeyDown(Keys.ALT_LEFT, repeatCount = 0)
        sink.keys.clear()

        assertTrue(chorded.handleKeyDown(Keys.G, repeatCount = 0))
        assertTrue(chorded.handleKeyDown(Keys.G, repeatCount = 1))

        assertEquals(1, hotkeys.size)
        assertTrue(sink.keys.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Synthetic combos
    // ---------------------------------------------------------------------

    @Test
    fun `synthetic combo presses forward and releases in reverse`() {
        val keyboard = processor()

        keyboard.sendSyntheticCombo(Keys.CTRL_LEFT, Keys.C, Keys.V)

        assertEquals(listOf(37, 54, 55, 55, 54, 37), sink.keys.map { it.keycode })
        assertEquals(
            listOf(true, true, true, false, false, false),
            sink.keys.map { it.pressed },
        )
        assertTrue(keyboard.pressedKeyCodes.isEmpty())
        assertEquals(0, keyboard.modifierMask)
    }

    @Test
    fun `synthetic combo is not intercepted by the hotkey layer`() {
        val keyboard = processor()

        keyboard.sendSyntheticCombo(Keys.CTRL_LEFT, Keys.ALT_LEFT, Keys.Q)

        assertTrue(hotkeys.isEmpty())
        assertTrue(sink.keys.any { it.keycode == 24 && it.pressed })
    }

    @Test
    fun `synthetic combo ignores unmapped keys`() {
        val keyboard = processor()

        keyboard.sendSyntheticCombo(Keys.MENU)

        assertTrue(sink.keys.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Release / config
    // ---------------------------------------------------------------------

    @Test
    fun `releaseAllKeys releases held keys in reverse order`() {
        val keyboard = processor()
        keyboard.handleKeyDown(Keys.SHIFT_LEFT, repeatCount = 0)
        keyboard.handleKeyDown(Keys.A, repeatCount = 0)
        sink.keys.clear()

        keyboard.releaseAllKeys()

        assertEquals(listOf(38, 50), sink.keys.map { it.keycode })
        assertTrue(sink.keys.none { it.pressed })
        assertEquals(1, sink.releaseAllCount)
        assertTrue(keyboard.pressedKeyCodes.isEmpty())
        assertEquals(0, keyboard.modifierMask)
    }

    @Test
    fun `updateConfig replaces the tuning`() {
        val keyboard = processor()

        keyboard.updateConfig(InputConfig(mouseSensitivity = 3f, scrollSpeed = 2f))

        assertEquals(3f, keyboard.config.mouseSensitivity, 0.001f)
        assertEquals(2f, keyboard.config.scrollSpeed, 0.001f)
    }
}
