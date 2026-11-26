package com.windroid.app.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import com.windroid.app.core.model.ConnectedPeripherals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the Android input stack for physically attached mice and keyboards.
 *
 * USB OTG dongles, Bluetooth mice and Bluetooth keyboards all arrive as
 * [InputDevice]s; when one shows up the session screen can hide the on-screen
 * virtual controls (see `InputConfig.hideVirtualControlsWhenHardwareConnected`)
 * and switch the pointer into captured-relative mode.
 *
 * Detection rules:
 *  - **mouse** — reports [InputDevice.SOURCE_MOUSE] or
 *    [InputDevice.SOURCE_MOUSE_RELATIVE] and is not virtual;
 *  - **keyboard** — reports [InputDevice.SOURCE_KEYBOARD], has
 *    [InputDevice.KEYBOARD_TYPE_ALPHABETIC] (a real typing keyboard, not a
 *    volume-key/remote style device) and is not virtual.
 *
 * The published [peripherals] snapshot is the single source of truth for the
 * UI; [start]/[stop] are idempotent so they can be bound to the session
 * lifecycle without extra bookkeeping.
 *
 * @param context any context; the application context is retained
 */
class ExternalDeviceMonitor(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    private val inputManager: InputManager? =
        appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private val handler: Handler = Handler(Looper.getMainLooper())

    private val _peripherals = MutableStateFlow(scanDevices())

    /** Latest snapshot of the physically attached peripherals. */
    val peripherals: StateFlow<ConnectedPeripherals> = _peripherals.asStateFlow()

    /** `true` between a successful [start] and the matching [stop]. */
    var isMonitoring: Boolean = false
        private set

    /** Convenience flag for the "hardware present" branch of the UI. */
    val hasAnyExternalDevice: Boolean
        get() = peripherals.value.hasExternalMouse || peripherals.value.hasExternalKeyboard

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            refreshNow()
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            refreshNow()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            refreshNow()
        }
    }

    /** Starts observing hot-plug events and publishes an initial snapshot. */
    fun start() {
        if (isMonitoring) return
        val manager = inputManager ?: return
        manager.registerInputDeviceListener(listener, handler)
        isMonitoring = true
        refreshNow()
    }

    /** Stops observing hot-plug events. The last snapshot is kept. */
    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false
        inputManager?.unregisterInputDeviceListener(listener)
    }

    /** Re-enumerates the devices and republishes [peripherals]. */
    fun refreshNow() {
        _peripherals.value = scanDevices()
    }

    // ---------------------------------------------------------------------
    // Enumeration
    // ---------------------------------------------------------------------

    private fun scanDevices(): ConnectedPeripherals {
        val manager = inputManager ?: return ConnectedPeripherals()

        val mouseNames = ArrayList<String>()
        val keyboardNames = ArrayList<String>()

        for (deviceId in manager.inputDeviceIds) {
            val device = runCatching { manager.getInputDevice(deviceId) }.getOrNull() ?: continue
            if (device.isVirtual) continue

            if (isPhysicalMouse(device)) mouseNames += displayName(device, deviceId)
            if (isPhysicalKeyboard(device)) keyboardNames += displayName(device, deviceId)
        }

        return ConnectedPeripherals(
            miceCount = mouseNames.size,
            keyboardsCount = keyboardNames.size,
            mouseNames = mouseNames.toList(),
            keyboardNames = keyboardNames.toList(),
        )
    }

    private fun displayName(device: InputDevice, deviceId: Int): String {
        val name = device.name?.trim().orEmpty()
        return name.ifEmpty { "input-device-$deviceId" }
    }

    companion object {
        /**
         * `true` when the device is a real pointing device (USB/Bluetooth mouse
         * or a trackball), as opposed to a touchscreen or an emulated pointer.
         */
        fun isPhysicalMouse(device: InputDevice): Boolean {
            val sources = device.sources
            val absoluteMouse = sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
            val relativeMouse =
                sources and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE
            return absoluteMouse || relativeMouse
        }

        /**
         * `true` when the device can type — a source keyboard with an alphabetic
         * layout, excluding remotes, touch-bars and virtual (IME) keyboards.
         */
        fun isPhysicalKeyboard(device: InputDevice): Boolean {
            val sources = device.sources
            if (sources and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD) return false
            return device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }
    }
}
