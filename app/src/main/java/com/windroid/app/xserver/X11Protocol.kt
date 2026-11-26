package com.windroid.app.xserver

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * X11 wire-protocol constants plus little-endian packet builders.
 *
 * The embedded server speaks the little-endian connection flavour (`'l'`) that
 * Xlib uses on the ARM/x86 devices WinDroid targets: every field below is laid
 * out exactly as the X11 core protocol specifies, with `CARD16`/`CARD32` values
 * written through a [ByteOrder.LITTLE_ENDIAN] [ByteBuffer].
 *
 * Input events are always [EVENT_PACKET_SIZE] bytes and share one fixed layout:
 * `code, detail, sequence, time, root, event, child, root-x, root-y, event-x,
 * event-y, state, same-screen, pad`. [buildExposeEvent] uses the Expose-specific
 * layout of the same size.
 */
object X11Protocol {

    // ---------------------------------------------------------------------
    // Core request opcodes
    // ---------------------------------------------------------------------

    const val CREATE_WINDOW = 1
    const val MAP_WINDOW = 8
    const val UNMAP_WINDOW = 10
    const val CONFIGURE_WINDOW = 12
    const val GET_GEOMETRY = 14
    const val INTERN_ATOM = 16
    const val GET_ATOM_NAME = 17
    const val GET_PROPERTY = 20
    const val QUERY_POINTER = 38
    const val GET_INPUT_FOCUS = 43
    const val PUT_IMAGE = 72
    const val QUERY_EXTENSION = 98
    const val NO_OPERATION = 127

    // ---------------------------------------------------------------------
    // Event codes
    // ---------------------------------------------------------------------

    const val KEY_PRESS = 2
    const val KEY_RELEASE = 3
    const val BUTTON_PRESS = 4
    const val BUTTON_RELEASE = 5
    const val MOTION_NOTIFY = 6
    const val EXPOSE = 12

    /** First/last core event code a client may select on a window. */
    const val FIRST_EVENT_CODE = 2
    const val LAST_EVENT_CODE = 34

    /** Reply code. */
    const val REPLY_CODE = 1

    /** Error envelope code. */
    const val ERROR_CODE = 0

    /** X11 `BadRequest` error number. */
    const val BAD_REQUEST = 1

    // ---------------------------------------------------------------------
    // Modifier / button state masks (X11 `state` field)
    // ---------------------------------------------------------------------

    const val SHIFT_MASK = 0x0001
    const val LOCK_MASK = 0x0002
    const val CONTROL_MASK = 0x0004
    const val MOD1_MASK = 0x0008
    const val MOD2_MASK = 0x0010
    const val MOD3_MASK = 0x0020
    const val MOD4_MASK = 0x0040
    const val MOD5_MASK = 0x0080
    const val BUTTON1_MASK = 0x0100
    const val BUTTON2_MASK = 0x0200
    const val BUTTON3_MASK = 0x0400
    const val BUTTON4_MASK = 0x0800
    const val BUTTON5_MASK = 0x1000

    /** Every modifier bit, in wire order (`Shift` … `Mod5`). */
    const val ALL_MODIFIER_MASK = 0x00FF

    // ---------------------------------------------------------------------
    // Wire sizes / connection constants
    // ---------------------------------------------------------------------

    /** Every core event (and every error/reply) is exactly 32 bytes. */
    const val EVENT_PACKET_SIZE = 32

    /** `opcode, data, length` — the fixed request prefix. */
    const val REQUEST_HEADER_SIZE = 4

    /** `'l'` — LSBFirst. */
    const val BYTE_ORDER_LSB = 0x6C

    /** `'B'` — MSBFirst. */
    const val BYTE_ORDER_MSB = 0x42

    const val PROTOCOL_MAJOR = 11
    const val PROTOCOL_MINOR = 0

    /** Size of the fixed part of the connection-setup Success reply. */
    const val SETUP_HEADER_SIZE = 40

    private const val FORMAT_SIZE = 8
    private const val SCREEN_HEADER_SIZE = 40
    private const val DEPTH_SIZE = 8
    private const val VISUAL_SIZE = 24
    private const val SCREEN_SIZE = SCREEN_HEADER_SIZE + DEPTH_SIZE + VISUAL_SIZE

    // ---------------------------------------------------------------------
    // Image / visual constants
    // ---------------------------------------------------------------------

    /** `ZPixmap` — the only image format Wine actually pushes. */
    const val IMAGE_FORMAT_Z_PIXMAP = 2

    const val IMAGE_BYTE_ORDER_LSB_FIRST = 0
    const val BITMAP_BIT_ORDER_LSB_FIRST = 0
    const val BITMAP_SCANLINE_UNIT = 32
    const val BITMAP_SCANLINE_PAD = 32

    const val MIN_KEYCODE = 8
    const val MAX_KEYCODE = 255

    const val ROOT_WINDOW_ID = 1
    const val DEFAULT_COLORMAP_ID = 0x20
    const val DEFAULT_VISUAL_ID = 0x21

    const val TRUE_COLOR_CLASS = 4

    /** Root depth; the framebuffer is always 32bpp ARGB internally. */
    const val ROOT_DEPTH = 24
    const val BITS_PER_PIXEL = 32

    const val RED_MASK = 0x00FF0000
    const val GREEN_MASK = 0x0000FF00
    const val BLUE_MASK = 0x000000FF

    const val RESOURCE_ID_BASE = 0x00400000
    const val RESOURCE_ID_MASK = 0x003FFFFF

    const val RELEASE_NUMBER = 12_101_000
    const val MOTION_BUFFER_SIZE = 256
    const val MAX_REQUEST_LENGTH = 65_535

    /** Server identification string surfaced to clients. */
    const val VENDOR = "WinDroid X11"

    private val PIXMAP_FORMATS = listOf(
        PixmapFormat(depth = 24, bitsPerPixel = 32, scanlinePad = 32),
        PixmapFormat(depth = 16, bitsPerPixel = 16, scanlinePad = 32),
        PixmapFormat(depth = 8, bitsPerPixel = 8, scanlinePad = 32),
    )

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    /** Decoded 4-byte request prefix. */
    data class RequestHeader(
        val opcode: Int,
        val data: Int,
        val lengthInWords: Int,
    ) {
        /** Total request size in bytes, header included. */
        val byteLength: Int
            get() = if (lengthInWords <= 0) REQUEST_HEADER_SIZE else lengthInWords * 4

        /** Bytes that follow the header and must still be consumed. */
        val payloadLength: Int
            get() = (byteLength - REQUEST_HEADER_SIZE).coerceAtLeast(0)
    }

    /**
     * Reads the fixed 4-byte request prefix.
     *
     * X11 counts a request's own header in `lengthInWords`, so a length of `6`
     * means 24 bytes in total.
     */
    fun parseRequestHeader(
        header: ByteArray,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): RequestHeader {
        require(header.size >= REQUEST_HEADER_SIZE) {
            "request header needs $REQUEST_HEADER_SIZE bytes, got ${header.size}"
        }
        val buffer = ByteBuffer.wrap(header, 0, REQUEST_HEADER_SIZE).order(byteOrder)
        return RequestHeader(
            opcode = buffer.get(0).toInt() and 0xFF,
            data = buffer.get(1).toInt() and 0xFF,
            lengthInWords = buffer.getShort(2).toInt() and 0xFFFF,
        )
    }

    // ---------------------------------------------------------------------
    // Event builders
    // ---------------------------------------------------------------------

    /** Builds a 32-byte `KeyPress`/`KeyRelease` event. */
    fun buildKeyEvent(
        eventCode: Int,
        keycode: Int,
        sequence: Int,
        time: Int,
        rootWindowId: Int,
        eventWindowId: Int,
        rootX: Int,
        rootY: Int,
        eventX: Int,
        eventY: Int,
        state: Int,
        sameScreen: Boolean = true,
    ): ByteArray = buildPointerStyleEvent(
        code = eventCode,
        detail = keycode,
        sequence = sequence,
        time = time,
        rootWindowId = rootWindowId,
        eventWindowId = eventWindowId,
        rootX = rootX,
        rootY = rootY,
        eventX = eventX,
        eventY = eventY,
        state = state,
        sameScreen = sameScreen,
    )

    /** Builds a 32-byte `ButtonPress`/`ButtonRelease` event; `detail` is the button. */
    fun buildButtonEvent(
        eventCode: Int,
        button: Int,
        sequence: Int,
        time: Int,
        rootWindowId: Int,
        eventWindowId: Int,
        rootX: Int,
        rootY: Int,
        eventX: Int,
        eventY: Int,
        state: Int,
        sameScreen: Boolean = true,
    ): ByteArray = buildPointerStyleEvent(
        code = eventCode,
        detail = button,
        sequence = sequence,
        time = time,
        rootWindowId = rootWindowId,
        eventWindowId = eventWindowId,
        rootX = rootX,
        rootY = rootY,
        eventX = eventX,
        eventY = eventY,
        state = state,
        sameScreen = sameScreen,
    )

    /** Builds a 32-byte `MotionNotify` event. */
    fun buildMotionEvent(
        sequence: Int,
        time: Int,
        rootWindowId: Int,
        eventWindowId: Int,
        rootX: Int,
        rootY: Int,
        eventX: Int,
        eventY: Int,
        state: Int,
        detail: Int = 0,
        sameScreen: Boolean = true,
    ): ByteArray = buildPointerStyleEvent(
        code = MOTION_NOTIFY,
        detail = detail,
        sequence = sequence,
        time = time,
        rootWindowId = rootWindowId,
        eventWindowId = eventWindowId,
        rootX = rootX,
        rootY = rootY,
        eventX = eventX,
        eventY = eventY,
        state = state,
        sameScreen = sameScreen,
    )

    /** Builds a 32-byte `Expose` event for a window region. */
    fun buildExposeEvent(
        windowId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        sequence: Int = 0,
        count: Int = 0,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(EVENT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, EXPOSE.toByte())
        buffer.put(1, 0.toByte())
        buffer.putShort(2, sequence.toShort())
        buffer.putInt(4, windowId)
        buffer.putShort(8, x.toShort())
        buffer.putShort(10, y.toShort())
        buffer.putShort(12, width.toShort())
        buffer.putShort(14, height.toShort())
        buffer.putShort(16, count.toShort())
        // bytes 18..31 are padding and stay zero
        return buffer.array()
    }

    private fun buildPointerStyleEvent(
        code: Int,
        detail: Int,
        sequence: Int,
        time: Int,
        rootWindowId: Int,
        eventWindowId: Int,
        rootX: Int,
        rootY: Int,
        eventX: Int,
        eventY: Int,
        state: Int,
        sameScreen: Boolean,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(EVENT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, code.toByte())
        buffer.put(1, detail.toByte())
        buffer.putShort(2, sequence.toShort())
        buffer.putInt(4, time)
        buffer.putInt(8, rootWindowId)
        buffer.putInt(12, eventWindowId)
        buffer.putInt(16, 0) // child: always None for a root-only pointer
        buffer.putShort(20, rootX.toShort())
        buffer.putShort(22, rootY.toShort())
        buffer.putShort(24, eventX.toShort())
        buffer.putShort(26, eventY.toShort())
        buffer.putShort(28, state.toShort())
        buffer.put(30, if (sameScreen) 1.toByte() else 0.toByte())
        buffer.put(31, 0.toByte())
        return buffer.array()
    }

    // ---------------------------------------------------------------------
    // Connection setup
    // ---------------------------------------------------------------------

    /**
     * Builds the connection-setup *Success* reply.
     *
     * One screen, one TrueColor visual at [ROOT_DEPTH], three pixmap formats and
     * `width x height` pixels with a DPI-derived physical size. The `length`
     * field counts 4-byte units after the 8-byte generic reply header, which is
     * what `_XConnect` uses to size the remaining read.
     */
    fun buildConnectionSetupReply(
        width: Int,
        height: Int,
        dpi: Int,
        rootWindowId: Int = ROOT_WINDOW_ID,
        visualId: Int = DEFAULT_VISUAL_ID,
    ): ByteArray {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeDpi = dpi.coerceAtLeast(1)

        val vendorBytes = VENDOR.toByteArray(Charsets.US_ASCII)
        val vendorPadded = padded4(vendorBytes.size)
        val screenOffset = SETUP_HEADER_SIZE + vendorPadded + PIXMAP_FORMATS.size * FORMAT_SIZE
        val total = screenOffset + SCREEN_SIZE

        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0, 1.toByte()) // Success
        buffer.put(1, 0.toByte())
        buffer.putShort(2, PROTOCOL_MAJOR.toShort())
        buffer.putShort(4, PROTOCOL_MINOR.toShort())
        buffer.putShort(6, (total / 4 - 2).toShort())
        buffer.putInt(8, RELEASE_NUMBER)
        buffer.putInt(12, RESOURCE_ID_BASE)
        buffer.putInt(16, RESOURCE_ID_MASK)
        buffer.putInt(20, MOTION_BUFFER_SIZE)
        buffer.putShort(24, vendorBytes.size.toShort())
        buffer.putShort(26, MAX_REQUEST_LENGTH.toShort())
        buffer.put(28, 1.toByte()) // one SCREEN
        buffer.put(29, PIXMAP_FORMATS.size.toByte())
        buffer.put(30, IMAGE_BYTE_ORDER_LSB_FIRST.toByte())
        buffer.put(31, BITMAP_BIT_ORDER_LSB_FIRST.toByte())
        buffer.put(32, BITMAP_SCANLINE_UNIT.toByte())
        buffer.put(33, BITMAP_SCANLINE_PAD.toByte())
        buffer.put(34, MIN_KEYCODE.toByte())
        buffer.put(35, MAX_KEYCODE.toByte())
        // bytes 36..39 are unused

        var cursor = SETUP_HEADER_SIZE
        vendorBytes.copyInto(buffer.array(), destinationOffset = cursor)
        cursor += vendorPadded

        for (format in PIXMAP_FORMATS) {
            buffer.put(cursor, format.depth.toByte())
            buffer.put(cursor + 1, format.bitsPerPixel.toByte())
            buffer.put(cursor + 2, format.scanlinePad.toByte())
            // cursor + 3 .. cursor + 7 are padding
            cursor += FORMAT_SIZE
        }

        val widthMm = (safeWidth * 25.4f / safeDpi).roundToInt().coerceAtLeast(1)
        val heightMm = (safeHeight * 25.4f / safeDpi).roundToInt().coerceAtLeast(1)

        buffer.putInt(screenOffset, rootWindowId)
        buffer.putInt(screenOffset + 4, DEFAULT_COLORMAP_ID)
        buffer.putInt(screenOffset + 8, 0x00FFFFFF) // white pixel
        buffer.putInt(screenOffset + 12, 0) // black pixel
        buffer.putInt(screenOffset + 16, 0) // current input masks
        buffer.putShort(screenOffset + 20, safeWidth.toShort())
        buffer.putShort(screenOffset + 22, safeHeight.toShort())
        buffer.putShort(screenOffset + 24, widthMm.toShort())
        buffer.putShort(screenOffset + 26, heightMm.toShort())
        buffer.putShort(screenOffset + 28, 1.toShort()) // min installed maps
        buffer.putShort(screenOffset + 30, 1.toShort()) // max installed maps
        buffer.putInt(screenOffset + 32, visualId)
        buffer.put(screenOffset + 36, 0.toByte()) // backing store: Never
        buffer.put(screenOffset + 37, 1.toByte()) // save unders
        buffer.put(screenOffset + 38, ROOT_DEPTH.toByte())
        buffer.put(screenOffset + 39, 1.toByte()) // one DEPTH

        val depthOffset = screenOffset + SCREEN_HEADER_SIZE
        buffer.put(depthOffset, ROOT_DEPTH.toByte())
        buffer.put(depthOffset + 1, 0.toByte())
        buffer.putShort(depthOffset + 2, 1.toShort()) // one VISUALTYPE
        buffer.putInt(depthOffset + 4, 0)

        val visualOffset = depthOffset + DEPTH_SIZE
        buffer.putInt(visualOffset, visualId)
        buffer.put(visualOffset + 4, TRUE_COLOR_CLASS.toByte())
        buffer.put(visualOffset + 5, 8.toByte()) // bits per RGB value
        buffer.putShort(visualOffset + 6, 256.toShort()) // colormap entries
        buffer.putInt(visualOffset + 8, RED_MASK)
        buffer.putInt(visualOffset + 12, GREEN_MASK)
        buffer.putInt(visualOffset + 16, BLUE_MASK)
        // visualOffset + 20 .. + 23 are padding

        return buffer.array()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** X11 `state` button bit for a pointer button, or `0` for buttons without one. */
    fun buttonStateMask(x11Button: Int): Int = when (x11Button) {
        1 -> BUTTON1_MASK
        2 -> BUTTON2_MASK
        3 -> BUTTON3_MASK
        4 -> BUTTON4_MASK
        5 -> BUTTON5_MASK
        else -> 0
    }

    /** Rounds [value] up to the next multiple of four. */
    fun padded4(value: Int): Int = (value + 3) and 3.inv()

    private data class PixmapFormat(
        val depth: Int,
        val bitsPerPixel: Int,
        val scanlinePad: Int,
    )
}
