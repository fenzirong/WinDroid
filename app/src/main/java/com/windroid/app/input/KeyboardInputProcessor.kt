package com.windroid.app.input

import android.view.KeyEvent
import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.InputEventSink

/**
 * Translates Android hardware keyboard events into X11 key events and owns the
 * session hotkeys.
 *
 * Because Windows grabs focus while a session runs, the host has to provide its
 * own escape hatch. Chords with Ctrl+Alt held are intercepted here and never
 * reach the guest:
 *
 * | Chord                | Action                 |
 * |----------------------|------------------------|
 * | Ctrl + Alt + G       | toggle pointer capture |
 * | Ctrl + Alt + M       | toggle the HUD overlay |
 * | Ctrl + Alt + Shift+Q | exit the session       |
 *
 * Everything else is forwarded as `(keycode, keysym, pressed, modifiers)`; the
 * modifier mask is rebuilt from the set of physically held keys so a dropped UP
 * event can never leave Shift/Ctrl stuck down.
 *
 * @param sink transport that forwards the events to the X server
 * @param initialConfig initial container input tuning
 * @param onHotkey invoked when a session hotkey fires
 */
class KeyboardInputProcessor(
    private val sink: InputEventSink,
    initialConfig: InputConfig,
    private val onHotkey: (SessionHotkey) -> Unit,
) {

    /** Session level shortcuts intercepted before they reach Windows. */
    enum class SessionHotkey {
        /** Switch between absolute and captured-relative pointer modes. */
        TOGGLE_POINTER_CAPTURE,

        /** Show/hide the on-screen HUD with the session controls. */
        TOGGLE_HUD,

        /** Tear the session down and return to the launcher. */
        EXIT_SESSION,
    }

    /** Active tuning; change it through [updateConfig]. */
    var config: InputConfig = initialConfig
        private set

    private val pressedKeys = LinkedHashSet<Int>()

    /**
     * Keys whose DOWN was consumed by a hotkey, so the matching UP must be
     * swallowed as well instead of sending a stray release to X11.
     */
    private val hotkeySwallowedKeys = LinkedHashSet<Int>()

    /** Currently held X11 modifiers (OR of the `*_MASK` constants). */
    var modifierMask: Int = 0
        private set

    /** Last known Caps Lock state; mirrors the physical lock. */
    var isCapsLockOn: Boolean = false
        private set

    /** Last known Num Lock state; mirrors the physical lock. */
    var isNumLockOn: Boolean = false
        private set

    /** Snapshot of the Android key codes currently held down. */
    val pressedKeyCodes: Set<Int>
        get() = pressedKeys.toSet()

    /** `true` while a synthetic combo is being injected (hotkeys disabled). */
    private var injectingCombo: Boolean = false

    /** Applies new tuning; held keys are unaffected. */
    fun updateConfig(config: InputConfig) {
        this.config = config
    }

    // ---------------------------------------------------------------------
    // Key events
    // ---------------------------------------------------------------------

    /**
     * Handles a hardware key DOWN.
     *
     * @param androidKeyCode Android `KeyEvent.KEYCODE_*` from the event
     * @param repeatCount `KeyEvent.getRepeatCount()`; `> 0` marks an auto-repeat
     * @param isCapsLockOn advisory lock hint; use [syncLockState] for the
     * authoritative value (see [updateLockStates])
     * @param isNumLockOn advisory lock hint; use [syncLockState] for the
     * authoritative value
     * @return `true` when the event was consumed (mapped or a hotkey), `false`
     * when the key has no X11 equivalent
     */
    fun handleKeyDown(
        androidKeyCode: Int,
        repeatCount: Int,
        isCapsLockOn: Boolean = false,
        isNumLockOn: Boolean = false,
    ): Boolean {
        val mapping = X11KeyMap.mapAndroidKeyCode(androidKeyCode)
        updateLockStates(androidKeyCode, isCapsLockOn, isNumLockOn)
        if (mapping == null) return false

        val modifierBit = X11KeyMap.modifierBitForKeyCode(androidKeyCode)

        // A trigger key that was swallowed stays swallowed until it is released,
        // so the auto-repeat stream cannot fire the hotkey again.
        if (hotkeySwallowedKeys.contains(androidKeyCode)) return true

        // Auto-repeat (or a duplicate DOWN) never re-triggers a hotkey and never
        // re-arms the modifier mask: it only re-sends the press so Windows keeps
        // repeating the character.
        if (pressedKeys.contains(androidKeyCode)) {
            sink.onKeyEvent(
                mapping.evdevKeycode,
                resolveKeysym(androidKeyCode, mapping),
                true,
                modifierMask,
            )
            return true
        }

        if (!injectingCombo && modifierBit == 0) {
            val hotkey = hotkeyFor(androidKeyCode)
            if (hotkey != null) {
                hotkeySwallowedKeys.add(androidKeyCode)
                onHotkey(hotkey)
                return true
            }
        }

        pressedKeys.add(androidKeyCode)
        if (modifierBit != 0) {
            modifierMask = rebuildMask()
        }
        sink.onKeyEvent(mapping.evdevKeycode, resolveKeysym(androidKeyCode, mapping), true, modifierMask)
        return true
    }

    /**
     * Handles a hardware key UP.
     *
     * A release is always forwarded (X11 tolerates an extra release), then the key
     * is dropped from the pressed set and the modifier mask is rebuilt.
     *
     * @return `true` when the event was consumed
     */
    fun handleKeyUp(androidKeyCode: Int): Boolean {
        if (hotkeySwallowedKeys.remove(androidKeyCode)) return true

        val mapping = X11KeyMap.mapAndroidKeyCode(androidKeyCode) ?: return false
        sink.onKeyEvent(mapping.evdevKeycode, resolveKeysym(androidKeyCode, mapping), false, modifierMask)
        pressedKeys.remove(androidKeyCode)
        modifierMask = rebuildMask()
        return true
    }

    /**
     * Injects a chord: presses every key in order, then releases them in reverse
     * order (e.g. `sendSyntheticCombo(CTRL_LEFT, KEYCODE_V)` for Ctrl+V).
     *
     * Hotkey interception is disabled for the duration, so a synthetic
     * `Ctrl+Alt+Q` reaches Windows instead of tearing the session down.
     */
    fun sendSyntheticCombo(vararg androidKeyCodes: Int) {
        val accepted = androidKeyCodes.filter { X11KeyMap.mapAndroidKeyCode(it) != null }
        if (accepted.isEmpty()) return

        val wasInjecting = injectingCombo
        injectingCombo = true
        try {
            accepted.forEach { handleKeyDown(it, repeatCount = 0) }
            accepted.asReversed().forEach { handleKeyUp(it) }
        } finally {
            injectingCombo = wasInjecting
        }
    }

    /**
     * Releases every tracked key (reverse press order) and then asks the sink to
     * drop any remaining server-side state.
     *
     * Called on focus loss, pointer-capture changes and session teardown.
     */
    fun releaseAllKeys() {
        val held = pressedKeys.toList().asReversed()
        for (androidKeyCode in held) {
            val mapping = X11KeyMap.mapAndroidKeyCode(androidKeyCode) ?: continue
            sink.onKeyEvent(
                mapping.evdevKeycode,
                resolveKeysym(androidKeyCode, mapping),
                false,
                modifierMask,
            )
        }
        pressedKeys.clear()
        hotkeySwallowedKeys.clear()
        modifierMask = 0
        sink.onReleaseAllInputs()
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Mirrors the physical lock state.
     *
     * The lock flags on [handleKeyDown] are advisory only (a caller commonly
     * forwards `metaState`-derived booleans there, which are noisy); the
     * processor therefore tracks the two locks from the lock keys themselves and
     * accepts an authoritative value through [syncLockState]. This keeps Caps
     * Lock from being silently cleared by an unrelated key press — and stops a
     * stray Shift flag from inverting letter case.
     */
    private fun updateLockStates(
        androidKeyCode: Int,
        @Suppress("UNUSED_PARAMETER") capsLockDown: Boolean,
        @Suppress("UNUSED_PARAMETER") numLockDown: Boolean,
    ) {
        when (androidKeyCode) {
            KeyEvent.KEYCODE_CAPS_LOCK -> isCapsLockOn = !isCapsLockOn
            KeyEvent.KEYCODE_NUM_LOCK -> isNumLockOn = !isNumLockOn
            else -> Unit
        }
    }

    /**
     * Pushes the lock state observed from `InputDevice` / `KeyEvent` meta state.
     *
     * Use this once when the session starts (and whenever the activity regains
     * focus) so the host mirrors the physical keyboard's locks.
     */
    fun syncLockState(isCapsLockOn: Boolean, isNumLockOn: Boolean) {
        this.isCapsLockOn = isCapsLockOn
        this.isNumLockOn = isNumLockOn
    }

    /** Resolves the hotkey a chord trigger would fire with the current mask. */
    private fun hotkeyFor(androidKeyCode: Int): SessionHotkey? {
        val required = X11KeyMap.CONTROL_MASK or X11KeyMap.MOD1_MASK
        if (modifierMask and required != required) return null
        val shift = modifierMask and X11KeyMap.SHIFT_MASK != 0
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_G -> if (shift) null else SessionHotkey.TOGGLE_POINTER_CAPTURE
            KeyEvent.KEYCODE_M -> if (shift) null else SessionHotkey.TOGGLE_HUD
            KeyEvent.KEYCODE_Q -> if (shift) SessionHotkey.EXIT_SESSION else null
            else -> null
        }
    }

    private fun resolveKeysym(androidKeyCode: Int, mapping: X11KeyMapping): Int {
        val shift = modifierMask and X11KeyMap.SHIFT_MASK != 0
        return X11KeyMap.resolveKeysym(androidKeyCode, shift, isCapsLockOn) ?: mapping.keysym
    }

    private fun rebuildMask(): Int = pressedKeys.fold(0) { mask, androidKeyCode ->
        mask or X11KeyMap.modifierBitForKeyCode(androidKeyCode)
    }
}
