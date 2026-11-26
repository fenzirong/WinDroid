package com.windroid.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [X11KeyMap].
 *
 * Android key codes are spelled out numerically on purpose: under the standard
 * unit-test setup the `android.jar` stub returns `0` for anything it does not
 * mock, so the table is validated against literal values.
 */
class X11KeyMapTest {

    /** Android `KeyEvent.KEYCODE_*` values used below. */
    private object Keys {
        const val A = 29
        const val Z = 54
        const val MENU = 82
        const val BACK = 4

        const val DIGIT_0 = 7
        const val DIGIT_1 = 8
        const val DIGIT_9 = 16

        const val COMMA = 55
        const val PERIOD = 56
        const val ALT_LEFT = 57
        const val SHIFT_LEFT = 59
        const val SHIFT_RIGHT = 60
        const val TAB = 61
        const val SPACE = 62
        const val ENTER = 66
        const val DEL = 67
        const val GRAVE = 68
        const val MINUS = 69
        const val EQUALS = 70
        const val LEFT_BRACKET = 71
        const val RIGHT_BRACKET = 72
        const val BACKSLASH = 73
        const val SEMICOLON = 74
        const val APOSTROPHE = 75
        const val SLASH = 76

        const val PAGE_UP = 92
        const val PAGE_DOWN = 93
        const val ESCAPE = 111
        const val FORWARD_DEL = 112
        const val CTRL_LEFT = 113
        const val CTRL_RIGHT = 114
        const val CAPS_LOCK = 115
        const val SCROLL_LOCK = 116
        const val META_LEFT = 117
        const val META_RIGHT = 118
        const val SYSRQ = 120
        const val BREAK = 121
        const val MOVE_HOME = 122
        const val MOVE_END = 123
        const val INSERT = 124

        const val F1 = 131
        const val F12 = 142
        const val NUM_LOCK = 143
        const val NUMPAD_0 = 144
        const val NUMPAD_9 = 153
        const val NUMPAD_DIVIDE = 154
        const val NUMPAD_MULTIPLY = 155
        const val NUMPAD_SUBTRACT = 156
        const val NUMPAD_ADD = 157
        const val NUMPAD_DOT = 158
        const val NUMPAD_ENTER = 160
    }

    // ---------------------------------------------------------------------
    // Modifier masks
    // ---------------------------------------------------------------------

    @Test
    fun `modifier masks match the X11 core definitions`() {
        assertEquals(1, X11KeyMap.SHIFT_MASK)
        assertEquals(2, X11KeyMap.LOCK_MASK)
        assertEquals(4, X11KeyMap.CONTROL_MASK)
        assertEquals(8, X11KeyMap.MOD1_MASK)
        assertEquals(16, X11KeyMap.MOD2_MASK)
        assertEquals(64, X11KeyMap.MOD4_MASK)
        assertEquals(8, X11KeyMap.EVDEV_X11_OFFSET)
    }

    // ---------------------------------------------------------------------
    // Coverage
    // ---------------------------------------------------------------------

    @Test
    fun `table covers more than one hundred keys`() {
        assertTrue(
            "expected 100+ mappings, got ${X11KeyMap.mappings.size}",
            X11KeyMap.mappings.size >= 100,
        )
    }

    @Test
    fun `every entry carries a valid X11 keycode`() {
        X11KeyMap.mappings.forEach { (androidKeyCode, mapping) ->
            assertTrue(
                "keycode ${mapping.evdevKeycode} out of range for $androidKeyCode",
                mapping.evdevKeycode in 8..255,
            )
            assertTrue("keysym must be inside the keysym range", mapping.keysym in 0x20..0x10FFFF)
        }
    }

    @Test
    fun `x11 keycodes are unique per android key`() {
        val keycodes = X11KeyMap.mappings.values.map { it.evdevKeycode }
        assertEquals("duplicate X11 keycodes in the table", keycodes.size, keycodes.toSet().size)
    }

    // ---------------------------------------------------------------------
    // Letters / digits / punctuation
    // ---------------------------------------------------------------------

    @Test
    fun `letters map to evdev plus eight with lower and upper keysyms`() {
        val a = X11KeyMap.mapAndroidKeyCode(Keys.A)
        assertNotNull(a)
        assertEquals(38, a!!.evdevKeycode)
        assertEquals(0x61, a.keysym)
        assertEquals(0x41, a.shiftKeysym)

        val z = X11KeyMap.mapAndroidKeyCode(Keys.Z)
        assertEquals(52, z!!.evdevKeycode)
        assertEquals(0x7A, z.keysym)
        assertEquals(0x5A, z.shiftKeysym)
    }

    @Test
    fun `digits carry the US shifted symbols`() {
        val one = X11KeyMap.mapAndroidKeyCode(Keys.DIGIT_1)!!
        assertEquals(10, one.evdevKeycode)
        assertEquals('1'.code, one.keysym)
        assertEquals('!'.code, one.shiftKeysym)

        val zero = X11KeyMap.mapAndroidKeyCode(Keys.DIGIT_0)!!
        assertEquals(19, zero.evdevKeycode)
        assertEquals('0'.code, zero.keysym)
        assertEquals(')'.code, zero.shiftKeysym)

        val nine = X11KeyMap.mapAndroidKeyCode(Keys.DIGIT_9)!!
        assertEquals(18, nine.evdevKeycode)
        assertEquals('9'.code, nine.keysym)
        assertEquals('('.code, nine.shiftKeysym)
    }

    @Test
    fun `oem punctuation uses the US layout`() {
        assertEquals(20, X11KeyMap.mapAndroidKeyCode(Keys.MINUS)!!.evdevKeycode)
        assertEquals('_'.code, X11KeyMap.mapAndroidKeyCode(Keys.MINUS)!!.shiftKeysym)
        assertEquals(21, X11KeyMap.mapAndroidKeyCode(Keys.EQUALS)!!.evdevKeycode)
        assertEquals('+'.code, X11KeyMap.mapAndroidKeyCode(Keys.EQUALS)!!.shiftKeysym)
        assertEquals('{'.code, X11KeyMap.mapAndroidKeyCode(Keys.LEFT_BRACKET)!!.shiftKeysym)
        assertEquals('}'.code, X11KeyMap.mapAndroidKeyCode(Keys.RIGHT_BRACKET)!!.shiftKeysym)
        assertEquals('|'.code, X11KeyMap.mapAndroidKeyCode(Keys.BACKSLASH)!!.shiftKeysym)
        assertEquals(':'.code, X11KeyMap.mapAndroidKeyCode(Keys.SEMICOLON)!!.shiftKeysym)
        assertEquals('"'.code, X11KeyMap.mapAndroidKeyCode(Keys.APOSTROPHE)!!.shiftKeysym)
        assertEquals('<'.code, X11KeyMap.mapAndroidKeyCode(Keys.COMMA)!!.shiftKeysym)
        assertEquals('>'.code, X11KeyMap.mapAndroidKeyCode(Keys.PERIOD)!!.shiftKeysym)
        assertEquals('?'.code, X11KeyMap.mapAndroidKeyCode(Keys.SLASH)!!.shiftKeysym)
        assertEquals('~'.code, X11KeyMap.mapAndroidKeyCode(Keys.GRAVE)!!.shiftKeysym)

        assertEquals(49, X11KeyMap.mapAndroidKeyCode(Keys.GRAVE)!!.evdevKeycode)
        assertEquals(47, X11KeyMap.mapAndroidKeyCode(Keys.SEMICOLON)!!.evdevKeycode)
        assertEquals(61, X11KeyMap.mapAndroidKeyCode(Keys.SLASH)!!.evdevKeycode)
    }

    // ---------------------------------------------------------------------
    // Special keys
    // ---------------------------------------------------------------------

    @Test
    fun `editing and navigation keys use their keysyms`() {
        assertEquals(9, X11KeyMap.mapAndroidKeyCode(Keys.ESCAPE)!!.evdevKeycode)
        assertEquals(0xFF1B, X11KeyMap.mapAndroidKeyCode(Keys.ESCAPE)!!.keysym)
        assertEquals(23, X11KeyMap.mapAndroidKeyCode(Keys.TAB)!!.evdevKeycode)
        assertEquals(0xFF09, X11KeyMap.mapAndroidKeyCode(Keys.TAB)!!.keysym)
        assertEquals(36, X11KeyMap.mapAndroidKeyCode(Keys.ENTER)!!.evdevKeycode)
        assertEquals(0xFF0D, X11KeyMap.mapAndroidKeyCode(Keys.ENTER)!!.keysym)
        assertEquals(22, X11KeyMap.mapAndroidKeyCode(Keys.DEL)!!.evdevKeycode)
        assertEquals(0xFF08, X11KeyMap.mapAndroidKeyCode(Keys.DEL)!!.keysym)
        assertEquals(65, X11KeyMap.mapAndroidKeyCode(Keys.SPACE)!!.evdevKeycode)
        assertEquals(' '.code, X11KeyMap.mapAndroidKeyCode(Keys.SPACE)!!.keysym)

        assertEquals(118, X11KeyMap.mapAndroidKeyCode(Keys.INSERT)!!.evdevKeycode)
        assertEquals(0xFF63, X11KeyMap.mapAndroidKeyCode(Keys.INSERT)!!.keysym)
        assertEquals(119, X11KeyMap.mapAndroidKeyCode(Keys.FORWARD_DEL)!!.evdevKeycode)
        assertEquals(0xFFFF, X11KeyMap.mapAndroidKeyCode(Keys.FORWARD_DEL)!!.keysym)
        assertEquals(0xFF50, X11KeyMap.mapAndroidKeyCode(Keys.MOVE_HOME)!!.keysym)
        assertEquals(0xFF57, X11KeyMap.mapAndroidKeyCode(Keys.MOVE_END)!!.keysym)
        assertEquals(0xFF55, X11KeyMap.mapAndroidKeyCode(Keys.PAGE_UP)!!.keysym)
        assertEquals(0xFF56, X11KeyMap.mapAndroidKeyCode(Keys.PAGE_DOWN)!!.keysym)
    }

    @Test
    fun `arrow keys map to the X11 cursor keysyms`() {
        assertEquals(111, X11KeyMap.mapAndroidKeyCode(19)!!.evdevKeycode) // DPAD_UP
        assertEquals(0xFF52, X11KeyMap.mapAndroidKeyCode(19)!!.keysym)
        assertEquals(116, X11KeyMap.mapAndroidKeyCode(20)!!.evdevKeycode) // DPAD_DOWN
        assertEquals(0xFF54, X11KeyMap.mapAndroidKeyCode(20)!!.keysym)
        assertEquals(113, X11KeyMap.mapAndroidKeyCode(21)!!.evdevKeycode) // DPAD_LEFT
        assertEquals(0xFF51, X11KeyMap.mapAndroidKeyCode(21)!!.keysym)
        assertEquals(114, X11KeyMap.mapAndroidKeyCode(22)!!.evdevKeycode) // DPAD_RIGHT
        assertEquals(0xFF53, X11KeyMap.mapAndroidKeyCode(22)!!.keysym)
    }

    @Test
    fun `function keys are consecutive from f1`() {
        val f1 = X11KeyMap.mapAndroidKeyCode(Keys.F1)!!
        assertEquals(67, f1.evdevKeycode)
        assertEquals(0xFFBE, f1.keysym)

        val f11 = X11KeyMap.mapAndroidKeyCode(Keys.F1 + 10)!!
        assertEquals(95, f11.evdevKeycode)
        assertEquals(0xFFC8, f11.keysym)

        val f12 = X11KeyMap.mapAndroidKeyCode(Keys.F12)!!
        assertEquals(96, f12.evdevKeycode)
        assertEquals(0xFFC9, f12.keysym)
    }

    @Test
    fun `modifier and lock keys use the xkb keycodes`() {
        assertEquals(50, X11KeyMap.mapAndroidKeyCode(Keys.SHIFT_LEFT)!!.evdevKeycode)
        assertEquals(0xFFE1, X11KeyMap.mapAndroidKeyCode(Keys.SHIFT_LEFT)!!.keysym)
        assertEquals(0xFFE2, X11KeyMap.mapAndroidKeyCode(Keys.SHIFT_RIGHT)!!.keysym)
        assertEquals(37, X11KeyMap.mapAndroidKeyCode(Keys.CTRL_LEFT)!!.evdevKeycode)
        assertEquals(0xFFE3, X11KeyMap.mapAndroidKeyCode(Keys.CTRL_LEFT)!!.keysym)
        assertEquals(0xFFE4, X11KeyMap.mapAndroidKeyCode(Keys.CTRL_RIGHT)!!.keysym)
        assertEquals(64, X11KeyMap.mapAndroidKeyCode(Keys.ALT_LEFT)!!.evdevKeycode)
        assertEquals(0xFFE9, X11KeyMap.mapAndroidKeyCode(Keys.ALT_LEFT)!!.keysym)
        assertEquals(133, X11KeyMap.mapAndroidKeyCode(Keys.META_LEFT)!!.evdevKeycode)
        assertEquals(0xFFEB, X11KeyMap.mapAndroidKeyCode(Keys.META_LEFT)!!.keysym)
        assertEquals(0xFFEC, X11KeyMap.mapAndroidKeyCode(Keys.META_RIGHT)!!.keysym)
        assertEquals(0xFFE5, X11KeyMap.mapAndroidKeyCode(Keys.CAPS_LOCK)!!.keysym)
        assertEquals(0xFF7F, X11KeyMap.mapAndroidKeyCode(Keys.NUM_LOCK)!!.keysym)
        assertEquals(0xFF14, X11KeyMap.mapAndroidKeyCode(Keys.SCROLL_LOCK)!!.keysym)
        assertEquals(0xFF61, X11KeyMap.mapAndroidKeyCode(Keys.SYSRQ)!!.keysym)
        assertEquals(0xFF13, X11KeyMap.mapAndroidKeyCode(Keys.BREAK)!!.keysym)
    }

    @Test
    fun `numpad uses the KP keysyms`() {
        val kp0 = X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_0)!!
        assertEquals(90, kp0.evdevKeycode)
        assertEquals(0xFFB0, kp0.keysym)

        assertEquals(0xFFB9, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_9)!!.keysym)
        assertEquals(106, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_DIVIDE)!!.evdevKeycode)
        assertEquals(0xFFAF, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_DIVIDE)!!.keysym)
        assertEquals(0xFFAA, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_MULTIPLY)!!.keysym)
        assertEquals(0xFFAD, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_SUBTRACT)!!.keysym)
        assertEquals(0xFFAB, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_ADD)!!.keysym)
        assertEquals(0xFFAE, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_DOT)!!.keysym)
        assertEquals(104, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_ENTER)!!.evdevKeycode)
        assertEquals(0xFF8D, X11KeyMap.mapAndroidKeyCode(Keys.NUMPAD_ENTER)!!.keysym)
    }

    @Test
    fun `android only keys are unmapped`() {
        assertNull(X11KeyMap.mapAndroidKeyCode(Keys.MENU))
        assertNull(X11KeyMap.mapAndroidKeyCode(Keys.BACK))
        assertNull(X11KeyMap.mapAndroidKeyCode(-1))
    }

    // ---------------------------------------------------------------------
    // Modifier bits
    // ---------------------------------------------------------------------

    @Test
    fun `modifier bit lookup follows the X11 masks`() {
        assertEquals(X11KeyMap.SHIFT_MASK, X11KeyMap.modifierBitForKeyCode(Keys.SHIFT_LEFT))
        assertEquals(X11KeyMap.SHIFT_MASK, X11KeyMap.modifierBitForKeyCode(Keys.SHIFT_RIGHT))
        assertEquals(X11KeyMap.LOCK_MASK, X11KeyMap.modifierBitForKeyCode(Keys.CAPS_LOCK))
        assertEquals(X11KeyMap.CONTROL_MASK, X11KeyMap.modifierBitForKeyCode(Keys.CTRL_LEFT))
        assertEquals(X11KeyMap.CONTROL_MASK, X11KeyMap.modifierBitForKeyCode(Keys.CTRL_RIGHT))
        assertEquals(X11KeyMap.MOD1_MASK, X11KeyMap.modifierBitForKeyCode(Keys.ALT_LEFT))
        assertEquals(X11KeyMap.MOD1_MASK, X11KeyMap.modifierBitForKeyCode(58)) // ALT_RIGHT
        assertEquals(X11KeyMap.MOD2_MASK, X11KeyMap.modifierBitForKeyCode(Keys.NUM_LOCK))
        assertEquals(X11KeyMap.MOD4_MASK, X11KeyMap.modifierBitForKeyCode(Keys.META_LEFT))
        assertEquals(X11KeyMap.MOD4_MASK, X11KeyMap.modifierBitForKeyCode(Keys.META_RIGHT))
        assertEquals(0, X11KeyMap.modifierBitForKeyCode(Keys.A))
        assertEquals(0, X11KeyMap.modifierBitForKeyCode(Keys.ENTER))
    }

    @Test
    fun `isModifier and isLockKey classify keys`() {
        assertTrue(X11KeyMap.isModifier(Keys.SHIFT_LEFT))
        assertTrue(X11KeyMap.isModifier(Keys.CAPS_LOCK))
        assertFalse(X11KeyMap.isModifier(Keys.A))
        assertTrue(X11KeyMap.isLockKey(Keys.CAPS_LOCK))
        assertTrue(X11KeyMap.isLockKey(Keys.NUM_LOCK))
        assertFalse(X11KeyMap.isLockKey(Keys.SHIFT_LEFT))
    }

    // ---------------------------------------------------------------------
    // Keysym resolution
    // ---------------------------------------------------------------------

    @Test
    fun `shift and caps lock resolve letter case`() {
        assertEquals(0x61, X11KeyMap.resolveKeysym(Keys.A, shiftActive = false))
        assertEquals(0x41, X11KeyMap.resolveKeysym(Keys.A, shiftActive = true))
        assertEquals(
            0x41,
            X11KeyMap.resolveKeysym(Keys.A, shiftActive = false, capsLockActive = true),
        )
        assertEquals(
            0x61,
            X11KeyMap.resolveKeysym(Keys.A, shiftActive = true, capsLockActive = true),
        )
        assertNull(X11KeyMap.resolveKeysym(Keys.MENU, shiftActive = true))
    }

    @Test
    fun `caps lock does not affect non letter keys`() {
        assertEquals('1'.code, X11KeyMap.resolveKeysym(Keys.DIGIT_1, false, capsLockActive = true))
        assertEquals('!'.code, X11KeyMap.resolveKeysym(Keys.DIGIT_1, true, capsLockActive = true))
        assertEquals('/', X11KeyMap.resolveKeysym(Keys.SLASH, false, capsLockActive = true)?.toChar())
        assertEquals(0xFFE1, X11KeyMap.resolveKeysym(Keys.SHIFT_LEFT, false, capsLockActive = true))
    }
}
