package com.windroid.app.xserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure JVM checks for the X11 wire format: every event is 32 bytes, every
 * multi-byte field is little-endian, and the connection setup reply advertises
 * the framebuffer the session was created with.
 */
class X11ProtocolTest {

    private fun le(packet: ByteArray): ByteBuffer =
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

    private fun u16(packet: ByteArray, offset: Int): Int =
        le(packet).getShort(offset).toInt() and 0xFFFF

    private fun u32(packet: ByteArray, offset: Int): Int = le(packet).getInt(offset)

    @Test
    fun `opcodes and event codes match the X11 core protocol`() {
        assertEquals(1, X11Protocol.CREATE_WINDOW)
        assertEquals(8, X11Protocol.MAP_WINDOW)
        assertEquals(10, X11Protocol.UNMAP_WINDOW)
        assertEquals(12, X11Protocol.CONFIGURE_WINDOW)
        assertEquals(14, X11Protocol.GET_GEOMETRY)
        assertEquals(38, X11Protocol.QUERY_POINTER)
        assertEquals(43, X11Protocol.GET_INPUT_FOCUS)
        assertEquals(72, X11Protocol.PUT_IMAGE)
        assertEquals(127, X11Protocol.NO_OPERATION)

        assertEquals(2, X11Protocol.KEY_PRESS)
        assertEquals(3, X11Protocol.KEY_RELEASE)
        assertEquals(4, X11Protocol.BUTTON_PRESS)
        assertEquals(5, X11Protocol.BUTTON_RELEASE)
        assertEquals(6, X11Protocol.MOTION_NOTIFY)
        assertEquals(12, X11Protocol.EXPOSE)

        assertEquals(0x0100, X11Protocol.BUTTON1_MASK)
        assertEquals(0x0200, X11Protocol.BUTTON2_MASK)
        assertEquals(0x0400, X11Protocol.BUTTON3_MASK)
        assertEquals(0x0800, X11Protocol.BUTTON4_MASK)
        assertEquals(0x1000, X11Protocol.BUTTON5_MASK)
        assertEquals(4, X11Protocol.CONTROL_MASK)
    }

    @Test
    fun `button state masks stop after button five`() {
        assertEquals(X11Protocol.BUTTON1_MASK, X11Protocol.buttonStateMask(1))
        assertEquals(X11Protocol.BUTTON5_MASK, X11Protocol.buttonStateMask(5))
        assertEquals(0, X11Protocol.buttonStateMask(6))
        assertEquals(0, X11Protocol.buttonStateMask(9))
    }

    @Test
    fun `key press event is a 32 byte little endian packet`() {
        val packet = X11Protocol.buildKeyEvent(
            eventCode = X11Protocol.KEY_PRESS,
            keycode = 38,
            sequence = 0x1234,
            time = 0x0A0B0C0D,
            rootWindowId = 1,
            eventWindowId = 0x00200001,
            rootX = 100,
            rootY = 200,
            eventX = 30,
            eventY = 40,
            state = X11Protocol.CONTROL_MASK,
        )

        assertEquals(X11Protocol.EVENT_PACKET_SIZE, packet.size)
        assertEquals(32, packet.size)
        assertEquals(X11Protocol.KEY_PRESS, packet[0].toInt())
        assertEquals(38, packet[1].toInt())
        assertEquals(0x1234, u16(packet, 2))
        assertEquals(0x0A0B0C0D, u32(packet, 4))
        assertEquals(1, u32(packet, 8))
        assertEquals(0x00200001, u32(packet, 12))
        assertEquals(0, u32(packet, 16))
        assertEquals(100, u16(packet, 20))
        assertEquals(200, u16(packet, 22))
        assertEquals(30, u16(packet, 24))
        assertEquals(40, u16(packet, 26))
        assertEquals(X11Protocol.CONTROL_MASK, u16(packet, 28))
        assertEquals(1, packet[30].toInt())
        assertEquals(0, packet[31].toInt())
    }

    @Test
    fun `key release keeps the same layout with a different code`() {
        val packet = X11Protocol.buildKeyEvent(
            eventCode = X11Protocol.KEY_RELEASE,
            keycode = 65,
            sequence = 9,
            time = 11,
            rootWindowId = 1,
            eventWindowId = 1,
            rootX = 5,
            rootY = 6,
            eventX = 5,
            eventY = 6,
            state = 0,
        )

        assertEquals(X11Protocol.EVENT_PACKET_SIZE, packet.size)
        assertEquals(X11Protocol.KEY_RELEASE, packet[0].toInt())
        assertEquals(65, packet[1].toInt())
    }

    @Test
    fun `button press encodes the button number as detail`() {
        val packet = X11Protocol.buildButtonEvent(
            eventCode = X11Protocol.BUTTON_PRESS,
            button = 3,
            sequence = 7,
            time = 42,
            rootWindowId = 1,
            eventWindowId = 1,
            rootX = 10,
            rootY = 11,
            eventX = 10,
            eventY = 11,
            state = X11Protocol.BUTTON1_MASK,
        )

        assertEquals(X11Protocol.EVENT_PACKET_SIZE, packet.size)
        assertEquals(X11Protocol.BUTTON_PRESS, packet[0].toInt())
        assertEquals(3, packet[1].toInt())
        assertEquals(7, u16(packet, 2))
        assertEquals(42, u32(packet, 4))
        assertEquals(10, u16(packet, 20))
        assertEquals(11, u16(packet, 22))
        assertEquals(X11Protocol.BUTTON1_MASK, u16(packet, 28))
    }

    @Test
    fun `motion notify carries root and event coordinates`() {
        val packet = X11Protocol.buildMotionEvent(
            sequence = 1,
            time = 2,
            rootWindowId = 1,
            eventWindowId = 0x00200001,
            rootX = 640,
            rootY = 360,
            eventX = 12,
            eventY = 34,
            state = X11Protocol.SHIFT_MASK or X11Protocol.BUTTON1_MASK,
        )

        assertEquals(X11Protocol.EVENT_PACKET_SIZE, packet.size)
        assertEquals(X11Protocol.MOTION_NOTIFY, packet[0].toInt())
        assertEquals(0, packet[1].toInt()) // detail: absolute motion
        assertEquals(640, u16(packet, 20))
        assertEquals(360, u16(packet, 22))
        assertEquals(12, u16(packet, 24))
        assertEquals(34, u16(packet, 26))
        assertEquals(X11Protocol.SHIFT_MASK or X11Protocol.BUTTON1_MASK, u16(packet, 28))
    }

    @Test
    fun `expose event uses the expose layout`() {
        val packet = X11Protocol.buildExposeEvent(
            windowId = 0x00200001,
            x = 3,
            y = 4,
            width = 800,
            height = 600,
            sequence = 5,
            count = 0,
        )

        assertEquals(X11Protocol.EVENT_PACKET_SIZE, packet.size)
        assertEquals(X11Protocol.EXPOSE, packet[0].toInt())
        assertEquals(0, packet[1].toInt())
        assertEquals(5, u16(packet, 2))
        assertEquals(0x00200001, u32(packet, 4))
        assertEquals(3, u16(packet, 8))
        assertEquals(4, u16(packet, 10))
        assertEquals(800, u16(packet, 12))
        assertEquals(600, u16(packet, 14))
        assertEquals(0, u16(packet, 16))
        for (index in 18 until 32) {
            assertEquals(0, packet[index].toInt())
        }
    }

    @Test
    fun `connection setup reply advertises one true colour screen`() {
        val reply = X11Protocol.buildConnectionSetupReply(
            width = 1280,
            height = 720,
            dpi = 160,
            rootWindowId = 1,
            visualId = 0x21,
        )

        // 40 byte header + padded vendor + 3 formats + one 72 byte screen.
        assertEquals(148, reply.size)
        assertEquals(1, reply[0].toInt()) // Success
        assertEquals(0, reply[1].toInt())
        assertEquals(11, u16(reply, 2))
        assertEquals(0, u16(reply, 4))
        assertEquals(reply.size / 4 - 2, u16(reply, 6))

        assertEquals(X11Protocol.RELEASE_NUMBER, u32(reply, 8))
        assertEquals(X11Protocol.RESOURCE_ID_BASE, u32(reply, 12))
        assertEquals(X11Protocol.RESOURCE_ID_MASK, u32(reply, 16))
        assertEquals(X11Protocol.MOTION_BUFFER_SIZE, u32(reply, 20))
        assertEquals(X11Protocol.VENDOR.length, u16(reply, 24))
        assertEquals(X11Protocol.MAX_REQUEST_LENGTH, u16(reply, 26))

        assertEquals(1, reply[28].toInt()) // one screen
        assertEquals(3, reply[29].toInt()) // three formats
        assertEquals(0, reply[30].toInt()) // LSBFirst image byte order
        assertEquals(0, reply[31].toInt()) // LSBFirst bitmap bit order
        assertEquals(32, reply[32].toInt()) // scanline unit
        assertEquals(32, reply[33].toInt()) // scanline pad
        assertEquals(8, reply[34].toInt()) // min keycode
        assertEquals(255, reply[35].toInt() and 0xFF) // max keycode

        // Vendor string lives directly after the 40 byte header.
        assertEquals(
            X11Protocol.VENDOR,
            String(reply, 40, X11Protocol.VENDOR.length, Charsets.US_ASCII),
        )

        // Pixmap formats start at 40 + 12.
        assertEquals(24, reply[52].toInt())
        assertEquals(32, reply[53].toInt())
        assertEquals(32, reply[54].toInt())
    }

    @Test
    fun `connection setup reply describes the screen geometry and visual`() {
        val reply = X11Protocol.buildConnectionSetupReply(
            width = 1280,
            height = 720,
            dpi = 160,
            rootWindowId = 1,
            visualId = 0x21,
        )

        assertEquals(1, u32(reply, 76)) // root window
        assertEquals(X11Protocol.DEFAULT_COLORMAP_ID, u32(reply, 80))
        assertEquals(0x00FFFFFF, u32(reply, 84)) // white pixel
        assertEquals(0, u32(reply, 88)) // black pixel
        assertEquals(0, u32(reply, 92)) // current input masks
        assertEquals(1280, u16(reply, 96))
        assertEquals(720, u16(reply, 98))
        assertEquals(203, u16(reply, 100)) // round(1280 * 25.4 / 160)
        assertEquals(114, u16(reply, 102)) // round(720 * 25.4 / 160)
        assertEquals(1, u16(reply, 104))
        assertEquals(1, u16(reply, 106))
        assertEquals(0x21, u32(reply, 108)) // root visual
        assertEquals(0, reply[112].toInt()) // backing store: Never
        assertEquals(1, reply[113].toInt()) // save unders
        assertEquals(24, reply[114].toInt()) // root depth
        assertEquals(1, reply[115].toInt()) // one depth

        assertEquals(1, u16(reply, 118)) // one visual type
        assertEquals(0x21, u32(reply, 124))
        assertEquals(X11Protocol.TRUE_COLOR_CLASS, reply[128].toInt())
        assertEquals(8, reply[129].toInt()) // bits per rgb value
        assertEquals(256, u16(reply, 130)) // colormap entries
        assertEquals(X11Protocol.RED_MASK, u32(reply, 132))
        assertEquals(X11Protocol.GREEN_MASK, u32(reply, 136))
        assertEquals(X11Protocol.BLUE_MASK, u32(reply, 140))
    }

    @Test
    fun `request header counts its own words`() {
        val header = byteArrayOf(72, 2, 6, 0) // PutImage, format ZPixmap, 6 words

        val parsed = X11Protocol.parseRequestHeader(header)

        assertEquals(X11Protocol.PUT_IMAGE, parsed.opcode)
        assertEquals(X11Protocol.IMAGE_FORMAT_Z_PIXMAP, parsed.data)
        assertEquals(6, parsed.lengthInWords)
        assertEquals(24, parsed.byteLength)
        assertEquals(20, parsed.payloadLength)
    }

    @Test
    fun `request header honours a big endian prefix`() {
        val header = byteArrayOf(72, 2, 0, 6)

        val parsed = X11Protocol.parseRequestHeader(header, ByteOrder.BIG_ENDIAN)

        assertEquals(6, parsed.lengthInWords)
        assertEquals(24, parsed.byteLength)
    }

    @Test
    fun `zero length requests do not produce a negative payload`() {
        val parsed = X11Protocol.parseRequestHeader(byteArrayOf(127, 0, 0, 0))

        assertEquals(0, parsed.payloadLength)
        assertEquals(X11Protocol.REQUEST_HEADER_SIZE, parsed.byteLength)
        assertEquals(X11Protocol.NO_OPERATION, parsed.opcode)
    }

    @Test
    fun `padded4 rounds up to whole words`() {
        assertEquals(0, X11Protocol.padded4(0))
        assertEquals(4, X11Protocol.padded4(1))
        assertEquals(4, X11Protocol.padded4(4))
        assertEquals(16, X11Protocol.padded4(13))
        assertEquals(16, X11Protocol.padded4(16))
    }

    @Test
    fun `packets are independent arrays`() {
        val first = X11Protocol.buildExposeEvent(1, 0, 0, 1, 1)
        val second = X11Protocol.buildExposeEvent(1, 0, 0, 1, 1)

        assertNotEquals(first, second) // distinct instances
        assertTrue(first.contentEquals(second))
    }
}
