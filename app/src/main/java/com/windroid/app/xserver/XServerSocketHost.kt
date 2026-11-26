package com.windroid.app.xserver

import android.net.LocalServerSocket
import android.net.LocalSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepts X11 clients on the abstract Unix socket `tmp/.X11-unix/X<display>`.
 *
 * That is the name Wine, Xlib and Termux:X11 resolve for `DISPLAY=:0`, so a
 * container can simply export `DISPLAY` and connect without a TCP listener.
 *
 * Responsibilities:
 *  - perform the X11 connection setup handshake (little-endian) and answer with
 *    [X11Protocol.buildConnectionSetupReply];
 *  - read core requests and apply the ones a compositor needs (`CreateWindow`,
 *    `MapWindow`, `ConfigureWindow`, `PutImage`, `QueryPointer`,
 *    `GetInputFocus`, `NoOperation`);
 *  - broadcast the 32-byte input packets produced by [XServerState.onX11Event]
 *    to every connected client.
 *
 * Requests this stub does not implement are answered with a `BadRequest` error
 * instead of silence, so Xlib completes (and logs) the request rather than
 * blocking the client forever.
 *
 * All logging goes through [start]'s `onLog` callback; the class never touches
 * Logcat directly so the session HUD can mirror the transport log.
 */
class XServerSocketHost(
    val state: XServerState,
    val displayNumber: Int = 0,
) {

    /** Abstract namespace name handed to [LocalServerSocket]. */
    val socketName: String = "tmp/.X11-unix/X$displayNumber"

    /** Value to export as `DISPLAY`. */
    val displayString: String = ":$displayNumber"

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val clients = ConcurrentHashMap.newKeySet<ClientConnection>()
    private val windows = ConcurrentHashMap<Int, WindowInfo>()
    private val atoms = ConcurrentHashMap<String, Int>()
    private val unhandledOpcodes = ConcurrentHashMap.newKeySet<Int>()
    private val nextAtomId = AtomicInteger(0x1000)

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var hostScope: CoroutineScope? = null

    @Volatile
    private var logger: (String) -> Unit = {}

    @Volatile
    private var previousEventSink: ((ByteArray) -> Unit)? = null

    @Volatile
    private var stopped = true

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /**
     * Binds the socket and starts accepting clients on [Dispatchers.IO].
     *
     * [scope] owns every coroutine this host starts, so cancelling it (or calling
     * [stop]) tears the transport down without leaking threads.
     */
    fun start(scope: CoroutineScope, onLog: (String) -> Unit = {}) {
        if (_isRunning.value) {
            onLog("$TAG: already listening on $socketName")
            return
        }

        logger = onLog
        val bound = try {
            LocalServerSocket(socketName)
        } catch (e: IOException) {
            log("failed to bind $socketName: ${e.message}")
            _isRunning.value = false
            return
        }

        serverSocket = bound
        hostScope = scope
        stopped = false

        previousEventSink = state.onX11Event
        state.onX11Event = { packet -> broadcast(packet) }

        _isRunning.value = true
        log("listening on abstract unix socket $socketName (DISPLAY=$displayString)")
        log("screen ${state.width}x${state.height} @ ${state.dpi}dpi, root=${state.rootWindowId}")

        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(bound) }
    }

    /** Closes every client connection and the listening socket. Idempotent. */
    fun stop() {
        val wasRunning = _isRunning.value || serverSocket != null
        stopped = true
        acceptJob?.cancel()
        acceptJob = null
        hostScope = null

        serverSocket?.let { closeQuietly(it) }
        serverSocket = null

        for (client in clients.toList()) {
            client.close()
        }
        clients.clear()
        _connectedClients.value = 0

        state.onX11Event = previousEventSink
        previousEventSink = null

        _isRunning.value = false
        if (wasRunning) {
            state.onReleaseAllInputs()
            log("stopped listening on $socketName")
        }
    }

    // ---------------------------------------------------------------------
    // Accept loop
    // ---------------------------------------------------------------------

    private suspend fun acceptLoop(socket: LocalServerSocket) {
        while (currentCoroutineContext().isActive && !stopped) {
            val raw = try {
                socket.accept()
            } catch (e: IOException) {
                if (stopped) break
                log("accept failed: ${e.message}")
                delay(ACCEPT_RETRY_DELAY_MS)
                continue
            }

            if (stopped) {
                closeQuietly(raw)
                break
            }
            if (clients.size >= MAX_CLIENTS) {
                log("rejecting client: $MAX_CLIENTS already connected")
                closeQuietly(raw)
                continue
            }

            val connection = try {
                ClientConnection(raw, NEXT_CLIENT_ID.getAndIncrement())
            } catch (e: IOException) {
                log("failed to open client streams: ${e.message}")
                closeQuietly(raw)
                continue
            }

            clients.add(connection)
            _connectedClients.value = clients.size
            log("client #${connection.id} connected (${clients.size} active)")

            hostScope?.launch(Dispatchers.IO) { serveClient(connection) }
        }
    }

    // ---------------------------------------------------------------------
    // Per-client session
    // ---------------------------------------------------------------------

    private fun serveClient(client: ClientConnection) {
        try {
            if (!handshake(client)) {
                log("client #${client.id} handshake rejected")
                return
            }
            log("client #${client.id} handshake complete")

            while (!stopped && !client.closed) {
                val headerBytes = readFully(client.input, X11Protocol.REQUEST_HEADER_SIZE) ?: break
                val header = X11Protocol.parseRequestHeader(headerBytes, ByteOrder.LITTLE_ENDIAN)
                val payload = if (header.payloadLength > 0) {
                    readFully(client.input, header.payloadLength) ?: break
                } else {
                    EMPTY_PAYLOAD
                }

                client.sequence = (client.sequence + 1) and 0xFFFF
                dispatch(client, header, payload)
            }
        } catch (e: EOFException) {
            log("client #${client.id} closed the connection")
        } catch (e: IOException) {
            if (!stopped && !client.closed) log("client #${client.id} io error: ${e.message}")
        } catch (e: RuntimeException) {
            log("client #${client.id} protocol error: ${e.message}")
        } finally {
            removeClient(client)
        }
    }

    /**
     * Reads the 12-byte setup prefix plus the (ignored) authorisation payload and
     * answers with the Success reply. Only LSBFirst clients are supported, which
     * covers Android, Wine and Xlib on the supported ABIs.
     */
    private fun handshake(client: ClientConnection): Boolean {
        val prefix = readFully(client.input, CONNECTION_PREFIX_SIZE) ?: return false
        val byteOrder = prefix[0].toInt() and 0xFF
        if (byteOrder != X11Protocol.BYTE_ORDER_LSB) {
            log("client #${client.id} uses byte order 0x${byteOrder.toString(16)}; only LSBFirst supported")
            return false
        }

        val buffer = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
        val majorVersion = buffer.getShort(2).toInt() and 0xFFFF
        val authNameLength = buffer.getShort(6).toInt() and 0xFFFF
        val authDataLength = buffer.getShort(8).toInt() and 0xFFFF

        if (majorVersion != X11Protocol.PROTOCOL_MAJOR) {
            log("client #${client.id} speaks X11 $majorVersion, expected ${X11Protocol.PROTOCOL_MAJOR}")
        }

        if (authNameLength > 0 && readFully(client.input, pad4(authNameLength)) == null) return false
        if (authDataLength > 0 && readFully(client.input, pad4(authDataLength)) == null) return false

        val reply = X11Protocol.buildConnectionSetupReply(
            width = state.width,
            height = state.height,
            dpi = state.dpi,
            rootWindowId = state.rootWindowId,
            visualId = state.visualId,
        )
        return client.write(reply)
    }

    // ---------------------------------------------------------------------
    // Request dispatch
    // ---------------------------------------------------------------------

    private fun dispatch(
        client: ClientConnection,
        header: X11Protocol.RequestHeader,
        payload: ByteArray,
    ) {
        when (header.opcode) {
            X11Protocol.CREATE_WINDOW -> handleCreateWindow(header, payload)
            X11Protocol.MAP_WINDOW -> handleMapWindow(payload)
            X11Protocol.UNMAP_WINDOW -> handleUnmapWindow(payload)
            X11Protocol.CONFIGURE_WINDOW -> handleConfigureWindow(payload)
            X11Protocol.GET_GEOMETRY -> writeGetGeometry(client, payload)
            X11Protocol.QUERY_POINTER -> writeQueryPointer(client)
            X11Protocol.GET_INPUT_FOCUS -> writeGetInputFocus(client)
            X11Protocol.PUT_IMAGE -> handlePutImage(payload)
            X11Protocol.NO_OPERATION -> Unit
            X11Protocol.INTERN_ATOM -> writeInternAtom(client, payload)
            X11Protocol.QUERY_EXTENSION -> writeQueryExtension(client, payload)
            X11Protocol.GET_PROPERTY -> writeGetProperty(client)
            else -> {
                if (unhandledOpcodes.add(header.opcode)) {
                    log(
                        "unsupported request opcode ${header.opcode} " +
                            "(${header.byteLength} bytes) answered with BadRequest",
                    )
                }
                writeBadRequest(client, header.opcode)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Window bookkeeping
    // ---------------------------------------------------------------------

    private fun handleCreateWindow(header: X11Protocol.RequestHeader, payload: ByteArray) {
        if (payload.size < CREATE_WINDOW_PAYLOAD_SIZE) return
        val id = intAt(payload, 0)
        val info = WindowInfo(
            id = id,
            parent = intAt(payload, 4),
            depth = header.data and 0xFF,
            x = shortAt(payload, 8),
            y = shortAt(payload, 10),
            width = shortAt(payload, 12).coerceAtLeast(1),
            height = shortAt(payload, 14).coerceAtLeast(1),
        )
        windows[id] = info
        log(
            "CreateWindow 0x${id.toString(16)} ${info.width}x${info.height}" +
                " at (${info.x},${info.y}) depth=${info.depth}",
        )
    }

    private fun handleMapWindow(payload: ByteArray) {
        if (payload.size < 4) return
        val id = intAt(payload, 0)
        val info = windows[id]
        if (info == null) {
            log("MapWindow 0x${id.toString(16)} for unknown window")
            return
        }
        info.mapped = true
        log("MapWindow 0x${id.toString(16)}")

        // A freshly mapped window is fully exposed; ask the client to repaint.
        broadcast(
            X11Protocol.buildExposeEvent(
                windowId = id,
                x = info.x,
                y = info.y,
                width = info.width,
                height = info.height,
            ),
        )
    }

    private fun handleUnmapWindow(payload: ByteArray) {
        if (payload.size < 4) return
        val id = intAt(payload, 0)
        windows[id]?.mapped = false
        log("UnmapWindow 0x${id.toString(16)}")
    }

    private fun handleConfigureWindow(payload: ByteArray) {
        if (payload.size < 12) return
        val id = intAt(payload, 0)
        val info = windows[id] ?: return
        val mask = intAt(payload, 8)
        var offset = 12

        if (mask and CONFIGURE_X != 0) {
            info.x = intAt(payload, offset)
            offset += 4
        }
        if (mask and CONFIGURE_Y != 0) {
            info.y = intAt(payload, offset)
            offset += 4
        }
        if (mask and CONFIGURE_WIDTH != 0) {
            info.width = intAt(payload, offset).coerceAtLeast(1)
            offset += 4
        }
        if (mask and CONFIGURE_HEIGHT != 0) {
            info.height = intAt(payload, offset).coerceAtLeast(1)
        }
        log("ConfigureWindow 0x${id.toString(16)} -> ${info.width}x${info.height} (${info.x},${info.y})")
    }

    /**
     * `PutImage` with `ZPixmap` into `state.putImage`.
     *
     * Drawables that name a known window are offset by that window's origin so a
     * Wine window that positions itself at `(x, y)` lands where it belongs on the
     * single-screen root.
     */
    private fun handlePutImage(payload: ByteArray) {
        if (payload.size < PUT_IMAGE_HEADER_SIZE) return
        if ((payload[1].toInt() and 0xFF) != X11Protocol.IMAGE_FORMAT_Z_PIXMAP) return

        val drawable = intAt(payload, 0)
        val width = shortAt(payload, 8)
        val height = shortAt(payload, 10)
        val dstX = shortAt(payload, 12)
        val dstY = shortAt(payload, 14)
        val depth = payload[17].toInt() and 0xFF

        if (width <= 0 || height <= 0) return
        if (width > state.width * 4 || height > state.height * 4) {
            log("PutImage ${width}x$height rejected: exceeds screen bounds")
            return
        }
        if (depth !in intArrayOf(24, 32, 16, 8)) {
            log("PutImage depth=$depth ignored")
            return
        }

        val origin = windows[drawable]
        val originX = origin?.x ?: 0
        val originY = origin?.y ?: 0

        val argb = decodeZPixmap(payload, width, height)
        state.putImage(originX + dstX, originY + dstY, width, height, argb)
    }

    /**
     * Converts a `ZPixmap` payload to ARGB.
     *
     * Scanlines are padded to 32 bits, so the per-pixel width is inferred from the
     * observed stride: 4 bytes (advertised 32bpp), 3 bytes (24bpp RGB), 2 bytes
     * (RGB565) or 1 byte (grey) are all accepted.
     */
    private fun decodeZPixmap(payload: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val dataLength = payload.size - PUT_IMAGE_HEADER_SIZE
        if (dataLength <= 0) return pixels

        val stride = dataLength / height
        if (stride <= 0) return pixels

        val bytesPerPixel = when {
            stride >= width * 4 -> 4
            stride >= width * 3 -> 3
            stride >= width * 2 -> 2
            else -> 1
        }

        for (row in 0 until height) {
            val rowStart = PUT_IMAGE_HEADER_SIZE + row * stride
            val target = row * width
            for (column in 0 until width) {
                val offset = rowStart + column * bytesPerPixel
                if (offset + bytesPerPixel > payload.size) break
                pixels[target + column] = when (bytesPerPixel) {
                    4 -> {
                        val value = (payload[offset].toInt() and 0xFF) or
                            ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                            ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                            ((payload[offset + 3].toInt() and 0xFF) shl 24)
                        value or OPAQUE_ALPHA
                    }
                    3 -> {
                        val red = payload[offset + 2].toInt() and 0xFF
                        val green = payload[offset + 1].toInt() and 0xFF
                        val blue = payload[offset].toInt() and 0xFF
                        OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
                    }
                    2 -> {
                        val value = (payload[offset].toInt() and 0xFF) or
                            ((payload[offset + 1].toInt() and 0xFF) shl 8)
                        expandRgb565(value)
                    }
                    else -> {
                        val grey = payload[offset].toInt() and 0xFF
                        OPAQUE_ALPHA or (grey shl 16) or (grey shl 8) or grey
                    }
                }
            }
        }
        return pixels
    }

    private fun expandRgb565(value: Int): Int {
        val red = (value shr 11) and 0x1F
        val green = (value shr 5) and 0x3F
        val blue = value and 0x1F
        val r = (red shl 3) or (red shr 2)
        val g = (green shl 2) or (green shr 4)
        val b = (blue shl 3) or (blue shr 2)
        return OPAQUE_ALPHA or (r shl 16) or (g shl 8) or b
    }

    // ---------------------------------------------------------------------
    // Reply writers
    // ---------------------------------------------------------------------

    private fun writeGetGeometry(client: ClientConnection, payload: ByteArray) {
        if (payload.size < 4) return
        val drawable = intAt(payload, 0)
        val info = windows[drawable]
        val buffer = replyBuffer(client)
        buffer.put(1, X11Protocol.ROOT_DEPTH.toByte())
        buffer.putInt(8, state.rootWindowId)
        buffer.putShort(12, (info?.x ?: 0).toShort())
        buffer.putShort(14, (info?.y ?: 0).toShort())
        buffer.putShort(16, (info?.width ?: state.width).toShort())
        buffer.putShort(18, (info?.height ?: state.height).toShort())
        buffer.putShort(20, 0.toShort()) // border width
        client.write(buffer.array())
    }

    private fun writeQueryPointer(client: ClientConnection) {
        val buffer = replyBuffer(client)
        buffer.put(1, 1.toByte()) // same-screen
        buffer.putInt(8, state.rootWindowId)
        buffer.putInt(12, 0) // child: None
        buffer.putShort(16, state.pointerX.toShort())
        buffer.putShort(18, state.pointerY.toShort())
        buffer.putShort(20, state.pointerX.toShort())
        buffer.putShort(22, state.pointerY.toShort())
        buffer.putShort(24, state.currentStateMask().toShort())
        client.write(buffer.array())
    }

    private fun writeGetInputFocus(client: ClientConnection) {
        val buffer = replyBuffer(client)
        buffer.put(1, 1.toByte()) // revert-to: PointerRoot
        buffer.putInt(8, state.rootWindowId) // focus: PointerRoot resolves to root
        client.write(buffer.array())
    }

    private fun writeInternAtom(client: ClientConnection, payload: ByteArray) {
        val nameLength = if (payload.size >= 2) shortAt(payload, 0) else 0
        val name = if (nameLength in 1..(payload.size - 4)) {
            String(payload, 4, nameLength, Charsets.US_ASCII)
        } else {
            ""
        }
        val atom = if (name.isEmpty()) 0 else atoms.getOrPut(name) { nextAtomId.getAndIncrement() }
        val buffer = replyBuffer(client)
        buffer.putInt(8, atom)
        client.write(buffer.array())
    }

    private fun writeQueryExtension(client: ClientConnection, payload: ByteArray) {
        val nameLength = if (payload.size >= 2) shortAt(payload, 0) else 0
        val name = if (nameLength in 1..(payload.size - 4)) {
            String(payload, 4, nameLength, Charsets.US_ASCII)
        } else {
            "<unnamed>"
        }
        // No extensions: Xlib falls back to core-only behaviour.
        val buffer = replyBuffer(client)
        buffer.put(8, 0.toByte()) // present = false
        client.write(buffer.array())
        log("QueryExtension '$name' -> absent")
    }

    private fun writeGetProperty(client: ClientConnection) {
        // format = 0 (no such property); type / bytes-after / length all zero.
        client.write(replyBuffer(client).array())
    }

    private fun writeBadRequest(client: ClientConnection, opcode: Int) {
        val buffer = ByteBuffer.allocate(X11Protocol.EVENT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, X11Protocol.ERROR_CODE.toByte())
        buffer.put(1, X11Protocol.BAD_REQUEST.toByte())
        buffer.putShort(2, client.sequence.toShort())
        buffer.putInt(4, 0) // bad value
        buffer.putShort(8, 0.toShort()) // minor opcode
        buffer.put(10, opcode.toByte()) // major opcode
        client.write(buffer.array())
    }

    /** 32-byte reply envelope: reply code, sequence, zero length. */
    private fun replyBuffer(client: ClientConnection): ByteBuffer {
        val buffer = ByteBuffer.allocate(X11Protocol.EVENT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, X11Protocol.REPLY_CODE.toByte())
        buffer.put(1, 0.toByte())
        buffer.putShort(2, client.sequence.toShort())
        buffer.putInt(4, 0)
        return buffer
    }

    // ---------------------------------------------------------------------
    // Fan-out
    // ---------------------------------------------------------------------

    /**
     * Sends [packet] to every client.
     *
     * Writes happen inline; local sockets have a deep enough buffer for 32-byte
     * events, and a client that stopped reading is dropped on the first failure.
     */
    private fun broadcast(packet: ByteArray) {
        if (clients.isEmpty()) return
        for (client in clients) {
            if (!client.write(packet)) {
                removeClient(client)
            }
        }
    }

    private fun removeClient(client: ClientConnection) {
        if (!clients.remove(client)) return
        client.close()
        _connectedClients.value = clients.size
        log("client #${client.id} disconnected (${clients.size} active)")
    }

    // ---------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------

    private fun log(message: String) {
        logger("$TAG: $message")
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length == 0) return EMPTY_PAYLOAD
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count < 0) {
                if (read == 0) return null
                throw EOFException("truncated read ($read/$length bytes)")
            }
            read += count
        }
        return buffer
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun pad4(value: Int): Int = (value + 3) and 3.inv()

    private fun closeQuietly(socket: LocalServerSocket) {
        try {
            socket.close()
        } catch (ignored: IOException) {
            // Nothing useful to do while tearing down.
        }
    }

    private fun closeQuietly(socket: LocalSocket) {
        try {
            socket.close()
        } catch (ignored: IOException) {
            // Nothing useful to do while tearing down.
        }
    }

    /** One accepted client: streams, request sequence and write serialisation. */
    private class ClientConnection(
        private val socket: LocalSocket,
        val id: Int,
    ) {
        val input: InputStream = socket.inputStream
        val output: OutputStream = socket.outputStream

        private val writeLock = Any()

        @Volatile
        var closed: Boolean = false
            private set

        /** Sequence number of the last request read from this client. */
        @Volatile
        var sequence: Int = 0

        fun write(bytes: ByteArray): Boolean {
            synchronized(writeLock) {
                if (closed) return false
                return try {
                    output.write(bytes)
                    output.flush()
                    true
                } catch (e: IOException) {
                    closed = true
                    false
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try {
                socket.close()
            } catch (ignored: IOException) {
                // Already gone.
            }
        }
    }

    private class WindowInfo(
        val id: Int,
        val parent: Int,
        val depth: Int,
        var x: Int,
        var y: Int,
        var width: Int,
        var height: Int,
    ) {
        @Volatile
        var mapped: Boolean = false
    }

    private companion object {
        const val TAG = "XServerSocketHost"
        const val MAX_CLIENTS = 8
        const val ACCEPT_RETRY_DELAY_MS = 50L
        const val CONNECTION_PREFIX_SIZE = 12
        const val CREATE_WINDOW_PAYLOAD_SIZE = 28
        const val PUT_IMAGE_HEADER_SIZE = 20
        const val OPAQUE_ALPHA = -0x1000000 // 0xFF000000

        const val CONFIGURE_X = 1
        const val CONFIGURE_Y = 2
        const val CONFIGURE_WIDTH = 4
        const val CONFIGURE_HEIGHT = 8

        val EMPTY_PAYLOAD = ByteArray(0)
        val NEXT_CLIENT_ID = AtomicInteger(1)
    }
}
