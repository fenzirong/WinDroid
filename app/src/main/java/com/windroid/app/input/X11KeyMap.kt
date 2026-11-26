package com.windroid.app.input

import android.view.KeyEvent

/**
 * One Android key resolved into its X11 wire representation.
 *
 * [evdevKeycode] is the *X11* keycode, i.e. the kernel evdev code plus
 * [X11KeyMap.EVDEV_X11_OFFSET] (8) — that is what `XTestFakeKeyEvent` and
 * `xcb_key_press` expect. [keysym] is the unshifted X11 keysym, [shiftKeysym]
 * the keysym produced while Shift (or Caps Lock, for letters) is active.
 *
 * @property evdevKeycode X11 keycode (evdev + 8)
 * @property keysym unshifted X11 keysym
 * @property shiftKeysym keysym emitted with Shift / Caps Lock applied
 */
data class X11KeyMapping(
    val evdevKeycode: Int,
    val keysym: Int,
    val shiftKeysym: Int = keysym,
)

/**
 * Static table mapping Android `KeyEvent.KEYCODE_*` constants onto X11 keycodes
 * and keysyms.
 *
 * A physical keyboard on Android delivers *Android* key codes (which are layout
 * agnostic), so the host has to translate them into an X11 keycode + keysym
 * pair before pushing them into the X server — the same approach Termux:X11 and
 * Winlator take. Only US layout keysyms are produced; the Windows side applies
 * its own layout on top of them.
 *
 * Everything here is pure JVM logic (integer lookups only), so it is unit
 * testable without Robolectric.
 */
object X11KeyMap {

    // ---------------------------------------------------------------------
    // X11 modifier masks (X.h)
    // ---------------------------------------------------------------------

    const val SHIFT_MASK = 1
    const val LOCK_MASK = 2
    const val CONTROL_MASK = 4
    const val MOD1_MASK = 8
    const val MOD2_MASK = 16
    const val MOD4_MASK = 64

    /** X11 keycodes are the kernel evdev codes shifted by this constant. */
    const val EVDEV_X11_OFFSET = 8

    // ---------------------------------------------------------------------
    // X11 keysyms (keysymdef.h)
    // ---------------------------------------------------------------------

    private const val XK_BackSpace = 0xFF08
    private const val XK_Tab = 0xFF09
    private const val XK_Return = 0xFF0D
    private const val XK_Pause = 0xFF13
    private const val XK_Scroll_Lock = 0xFF14
    private const val XK_Escape = 0xFF1B
    private const val XK_Home = 0xFF50
    private const val XK_Left = 0xFF51
    private const val XK_Up = 0xFF52
    private const val XK_Right = 0xFF53
    private const val XK_Down = 0xFF54
    private const val XK_Prior = 0xFF55 // Page Up
    private const val XK_Next = 0xFF56 // Page Down
    private const val XK_End = 0xFF57
    private const val XK_Print = 0xFF61
    private const val XK_Insert = 0xFF63
    private const val XK_Help = 0xFF6A
    private const val XK_Num_Lock = 0xFF7F
    private const val XK_KP_Enter = 0xFF8D
    private const val XK_KP_Multiply = 0xFFAA
    private const val XK_KP_Add = 0xFFAB
    private const val XK_KP_Subtract = 0xFFAD
    private const val XK_KP_Decimal = 0xFFAE
    private const val XK_KP_Divide = 0xFFAF
    private const val XK_KP_0 = 0xFFB0 // XK_KP_0 .. XK_KP_9 are consecutive
    private const val XK_F1 = 0xFFBE // XK_F1 .. XK_F12 are consecutive
    private const val XK_Shift_L = 0xFFE1
    private const val XK_Shift_R = 0xFFE2
    private const val XK_Control_L = 0xFFE3
    private const val XK_Control_R = 0xFFE4
    private const val XK_Caps_Lock = 0xFFE5
    private const val XK_Alt_L = 0xFFE9
    private const val XK_Alt_R = 0xFFEA
    private const val XK_Super_L = 0xFFEB
    private const val XK_Super_R = 0xFFEC
    private const val XK_Delete = 0xFFFF

    // ---------------------------------------------------------------------
    // evdev key codes (linux/input-event-codes.h)
    // ---------------------------------------------------------------------

    /** evdev codes for `A`..`Z`, in Android `KEYCODE_A`..`KEYCODE_Z` order. */
    private val LETTER_EVDEV_KEYCODES = intArrayOf(
        30, // A
        48, // B
        46, // C
        32, // D
        18, // E
        33, // F
        34, // G
        35, // H
        23, // I
        36, // J
        37, // K
        38, // L
        50, // M
        49, // N
        24, // O
        25, // P
        16, // Q
        19, // R
        31, // S
        20, // T
        22, // U
        47, // V
        17, // W
        45, // X
        21, // Y
        44, // Z
    )

    /** evdev codes for the top-row `0`..`9` keys. */
    private val DIGIT_EVDEV_KEYCODES = intArrayOf(11, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    /** US-layout shifted symbols for `0`..`9`. */
    private val DIGIT_SHIFT_KEYSYMS = intArrayOf(
        ')'.code,
        '!'.code,
        '@'.code,
        '#'.code,
        '$'.code,
        '%'.code,
        '^'.code,
        '&'.code,
        '*'.code,
        '('.code,
    )

    /** evdev codes for `KEY_F1`..`KEY_F12` (F11/F12 are not contiguous). */
    private val FUNCTION_EVDEV_KEYCODES = intArrayOf(59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88)

    /** evdev codes for the numeric keypad `KP0`..`KP9`. */
    private val NUMPAD_EVDEV_KEYCODES = intArrayOf(82, 79, 80, 81, 75, 76, 77, 71, 72, 73)

    // ---------------------------------------------------------------------
    // Table
    // ---------------------------------------------------------------------

    /**
     * Full Android key code → [X11KeyMapping] table (100+ entries).
     *
     * Exposed read-only so the UI can render a keyboard map preview and so tests
     * can assert coverage/uniqueness.
     */
    val mappings: Map<Int, X11KeyMapping> = buildMap {
        // A..Z ------------------------------------------------------------
        for (index in LETTER_EVDEV_KEYCODES.indices) {
            val lower = 'a'.code + index
            val upper = 'A'.code + index
            put(KeyEvent.KEYCODE_A + index, x11(LETTER_EVDEV_KEYCODES[index], lower, upper))
        }

        // 0..9 ------------------------------------------------------------
        for (index in DIGIT_EVDEV_KEYCODES.indices) {
            put(
                KeyEvent.KEYCODE_0 + index,
                x11(DIGIT_EVDEV_KEYCODES[index], '0'.code + index, DIGIT_SHIFT_KEYSYMS[index]),
            )
        }

        // F1..F12 ---------------------------------------------------------
        for (index in FUNCTION_EVDEV_KEYCODES.indices) {
            put(KeyEvent.KEYCODE_F1 + index, x11(FUNCTION_EVDEV_KEYCODES[index], XK_F1 + index))
        }

        // Numpad 0..9 -----------------------------------------------------
        for (index in NUMPAD_EVDEV_KEYCODES.indices) {
            put(
                KeyEvent.KEYCODE_NUMPAD_0 + index,
                x11(NUMPAD_EVDEV_KEYCODES[index], XK_KP_0 + index),
            )
        }

        // Editing / navigation --------------------------------------------
        put(KeyEvent.KEYCODE_ESCAPE, x11(1, XK_Escape))
        put(KeyEvent.KEYCODE_TAB, x11(15, XK_Tab))
        put(KeyEvent.KEYCODE_ENTER, x11(28, XK_Return))
        put(KeyEvent.KEYCODE_DEL, x11(14, XK_BackSpace))
        put(KeyEvent.KEYCODE_FORWARD_DEL, x11(111, XK_Delete))
        put(KeyEvent.KEYCODE_SPACE, x11(57, ' '.code))
        put(KeyEvent.KEYCODE_INSERT, x11(110, XK_Insert))
        put(KeyEvent.KEYCODE_MOVE_HOME, x11(102, XK_Home))
        put(KeyEvent.KEYCODE_MOVE_END, x11(107, XK_End))
        put(KeyEvent.KEYCODE_PAGE_UP, x11(104, XK_Prior))
        put(KeyEvent.KEYCODE_PAGE_DOWN, x11(109, XK_Next))
        put(KeyEvent.KEYCODE_DPAD_UP, x11(103, XK_Up))
        put(KeyEvent.KEYCODE_DPAD_DOWN, x11(108, XK_Down))
        put(KeyEvent.KEYCODE_DPAD_LEFT, x11(105, XK_Left))
        put(KeyEvent.KEYCODE_DPAD_RIGHT, x11(106, XK_Right))
        put(KeyEvent.KEYCODE_HELP, x11(138, XK_Help))

        // Modifiers & locks -----------------------------------------------
        put(KeyEvent.KEYCODE_SHIFT_LEFT, x11(42, XK_Shift_L))
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, x11(54, XK_Shift_R))
        put(KeyEvent.KEYCODE_CTRL_LEFT, x11(29, XK_Control_L))
        put(KeyEvent.KEYCODE_CTRL_RIGHT, x11(97, XK_Control_R))
        put(KeyEvent.KEYCODE_ALT_LEFT, x11(56, XK_Alt_L))
        put(KeyEvent.KEYCODE_ALT_RIGHT, x11(100, XK_Alt_R))
        put(KeyEvent.KEYCODE_META_LEFT, x11(125, XK_Super_L))
        put(KeyEvent.KEYCODE_META_RIGHT, x11(126, XK_Super_R))
        put(KeyEvent.KEYCODE_CAPS_LOCK, x11(58, XK_Caps_Lock))
        put(KeyEvent.KEYCODE_NUM_LOCK, x11(69, XK_Num_Lock))
        put(KeyEvent.KEYCODE_SCROLL_LOCK, x11(70, XK_Scroll_Lock))
        put(KeyEvent.KEYCODE_SYSRQ, x11(99, XK_Print))
        put(KeyEvent.KEYCODE_BREAK, x11(119, XK_Pause))

        // OEM punctuation (US layout) -------------------------------------
        put(KeyEvent.KEYCODE_MINUS, x11(12, '-'.code, '_'.code))
        put(KeyEvent.KEYCODE_EQUALS, x11(13, '='.code, '+'.code))
        put(KeyEvent.KEYCODE_LEFT_BRACKET, x11(26, '['.code, '{'.code))
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, x11(27, ']'.code, '}'.code))
        put(KeyEvent.KEYCODE_BACKSLASH, x11(43, '\\'.code, '|'.code))
        put(KeyEvent.KEYCODE_SEMICOLON, x11(39, ';'.code, ':'.code))
        put(KeyEvent.KEYCODE_APOSTROPHE, x11(40, '\''.code, '"'.code))
        put(KeyEvent.KEYCODE_COMMA, x11(51, ','.code, '<'.code))
        put(KeyEvent.KEYCODE_PERIOD, x11(52, '.'.code, '>'.code))
        put(KeyEvent.KEYCODE_SLASH, x11(53, '/'.code, '?'.code))
        put(KeyEvent.KEYCODE_GRAVE, x11(41, '`'.code, '~'.code))

        // Numpad operators -------------------------------------------------
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, x11(98, XK_KP_Divide))
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, x11(55, XK_KP_Multiply))
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, x11(74, XK_KP_Subtract))
        put(KeyEvent.KEYCODE_NUMPAD_ADD, x11(78, XK_KP_Add))
        put(KeyEvent.KEYCODE_NUMPAD_DOT, x11(83, XK_KP_Decimal))
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, x11(96, XK_KP_Enter))
    }

    /** Builds an [X11KeyMapping], applying the evdev → X11 keycode offset. */
    private fun x11(evdevKeycode: Int, keysym: Int, shiftKeysym: Int = keysym): X11KeyMapping =
        X11KeyMapping(evdevKeycode + EVDEV_X11_OFFSET, keysym, shiftKeysym)

    // ---------------------------------------------------------------------
    // Lookups
    // ---------------------------------------------------------------------

    /**
     * Resolves an Android key code to its X11 mapping.
     *
     * @return the mapping, or `null` when the key has no X11 equivalent
     * (media keys, Android-only buttons such as MENU/BACK, …).
     */
    fun mapAndroidKeyCode(androidKeyCode: Int): X11KeyMapping? = mappings[androidKeyCode]

    /**
     * Returns the X11 modifier mask contributed by [androidKeyCode], or `0` when
     * the key is not a modifier / lock.
     *
     * Both Shift keys share [SHIFT_MASK] and both Alt keys share [MOD1_MASK] —
     * X11 tracks the modifier state, not the physical key.
     */
    fun modifierBitForKeyCode(androidKeyCode: Int): Int = when (androidKeyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_MASK
        KeyEvent.KEYCODE_CAPS_LOCK -> LOCK_MASK
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_MASK
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> MOD1_MASK
        KeyEvent.KEYCODE_NUM_LOCK -> MOD2_MASK
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> MOD4_MASK
        else -> 0
    }

    /** `true` when the key contributes a modifier bit. */
    fun isModifier(androidKeyCode: Int): Boolean = modifierBitForKeyCode(androidKeyCode) != 0

    /** `true` for Caps Lock / Num Lock, the two locks the host mirrors itself. */
    fun isLockKey(androidKeyCode: Int): Boolean =
        androidKeyCode == KeyEvent.KEYCODE_CAPS_LOCK || androidKeyCode == KeyEvent.KEYCODE_NUM_LOCK

    /**
     * Resolves the keysym that should be sent for [androidKeyCode].
     *
     * Letters honour the classic `Shift xor Caps Lock` rule; every other key
     * only reacts to Shift.
     *
     * @return the keysym, or `null` when the key is unmapped.
     */
    fun resolveKeysym(
        androidKeyCode: Int,
        shiftActive: Boolean,
        capsLockActive: Boolean = false,
    ): Int? {
        val mapping = mappings[androidKeyCode] ?: return null
        val base = mapping.keysym
        val isLetter = base in 'a'.code..'z'.code
        val shifted = if (isLetter) shiftActive.xor(capsLockActive) else shiftActive
        return if (shifted) mapping.shiftKeysym else base
    }
}
