package com.windroid.app.runtime

import com.windroid.app.core.model.PointerButton
import com.windroid.app.xserver.InputActivity
import com.windroid.app.xserver.XServerState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Built-in, dependency-free "Windows 11"-looking interactive desktop.
 *
 * Why this exists: a fresh install has no `imagefs` yet (the Wine + Box64 bundle
 * is a 1-2 GB manual import), but the single most fragile part of the whole
 * product — *does the user's external mouse and keyboard deliver events into the
 * X11 surface at all?* — can be answered in the first ten seconds. So this
 * renderer paints a real desktop straight into the live [XServerState] and
 * reflects every pointer/key event back at the user:
 *
 * * pointer coordinates, last pressed button, accumulated scroll delta,
 * * a live text buffer fed from the physical keyboard,
 * * a right-click context menu, which proves the Android "back" gesture is being
 *   converted into an X11 button-3 press rather than closing the activity.
 *
 * Implementation notes:
 *
 * * Everything is drawn into a private `IntArray` framebuffer and flushed with a
 *   single [XServerState.putImage] call, so no Android `Canvas`, `Paint`, font or
 *   `Bitmap` is involved — the renderer stays pure Kotlin and unit-testable.
 * * Text uses a built-in 5x7 bitmap font (see [FONT_DATA]) rendered at an integer
 *   scale, which keeps glyphs pixel-crisp at any device density.
 * * The desktop is repainted on a slow tick thread (1 Hz, for the clock and the
 *   caret) and immediately on every input event, so the demo never feels laggy
 *   while costing almost nothing when idle.
 */
class DemoDesktopRenderer(private val state: XServerState) {

    private val width: Int = state.width
    private val height: Int = state.height

    /** Software framebuffer, ARGB, row-major. */
    private val frame = IntArray(width * height)

    private val lock = Any()

    @Volatile
    private var running = false

    private var renderThread: Thread? = null
    private var previousInputHandler: ((InputActivity) -> Unit)? = null

    // --- chrome metrics ---------------------------------------------------

    private val taskbarHeight: Int = max(36, height / 16)
    private val titleBarHeight: Int = max(26, height / 24)

    private val uiScale: Int
        get() = if (width >= 1100) 2 else 1

    // --- window geometry (mutable: the window is draggable) ---------------

    private var windowW: Int = (width * 0.64f).toInt().coerceAtLeast(320)
    private var windowH: Int = (height * 0.62f).toInt().coerceAtLeast(240)
    private var windowX: Int = (width - windowW) / 2
    private var windowY: Int = max(8, (height - taskbarHeight - windowH) / 2)

    // --- live demo state --------------------------------------------------

    private var pointerX: Int = state.pointerX.coerceIn(0, max(0, width - 1))
    private var pointerY: Int = state.pointerY.coerceIn(0, max(0, height - 1))
    private var lastButtonLabel: String = "NONE"
    private var scrollDelta: Int = 0
    private var keyCount: Int = 0
    private val textBuffer = StringBuilder()

    private var menuVisible: Boolean = false
    private var menuX: Int = 0
    private var menuY: Int = 0
    private var menuHighlight: Int = 0

    private var draggingWindow: Boolean = false
    private var dragGrabX: Int = 0
    private var dragGrabY: Int = 0

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /**
     * Paints the first frame, hooks [XServerState.onInputActivity] and starts the
     * 1 Hz tick. Any previously installed handler is restored by [stop].
     */
    fun start() {
        val thread = synchronized(lock) {
            if (running) return
            running = true
            previousInputHandler = state.onInputActivity
            state.onInputActivity = { activity -> handleInput(activity) }
            clampWindow()
            draw()
            val created = Thread({ tickLoop() }, "windroid-demo-desktop").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            renderThread = created
            created
        }
        thread.start()
    }

    /** Unhooks input and stops the tick thread. Safe to call twice. */
    fun stop() {
        val thread = synchronized(lock) {
            if (!running) {
                null
            } else {
                running = false
                state.onInputActivity = previousInputHandler
                previousInputHandler = null
                val current = renderThread
                renderThread = null
                current
            }
        }
        thread?.interrupt()
    }

    private fun tickLoop() {
        while (running) {
            try {
                Thread.sleep(CLOCK_TICK_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            draw()
        }
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    private fun handleInput(activity: InputActivity) {
        synchronized(lock) {
            when (activity) {
                is InputActivity.PointerMove -> {
                    pointerX = activity.x.coerceIn(0, max(0, width - 1))
                    pointerY = activity.y.coerceIn(0, max(0, height - 1))
                    if (draggingWindow) {
                        windowX = (pointerX - dragGrabX).coerceIn(0, max(0, width - windowW))
                        windowY = (pointerY - dragGrabY)
                            .coerceIn(0, max(0, height - taskbarHeight - windowH))
                    } else if (menuVisible) {
                        menuHighlight = menuIndexAt(pointerX, pointerY)
                    }
                }

                is InputActivity.PointerButton -> handleButton(activity)

                is InputActivity.Key -> if (activity.pressed) handleKeysym(activity.keysym)

                else -> {
                    // ReleaseAll: focus was lost, drop every transient state.
                    draggingWindow = false
                    menuVisible = false
                }
            }
            draw()
        }
    }

    private fun handleButton(event: InputActivity.PointerButton) {
        val x = event.x.coerceIn(0, max(0, width - 1))
        val y = event.y.coerceIn(0, max(0, height - 1))
        pointerX = x
        pointerY = y

        if (!event.pressed) {
            if (event.button == PointerButton.LEFT) draggingWindow = false
            return
        }

        lastButtonLabel = when (event.button) {
            PointerButton.LEFT -> "LEFT"
            PointerButton.RIGHT -> "RIGHT"
            PointerButton.MIDDLE -> "MIDDLE"
            PointerButton.BACK -> "BACK"
            PointerButton.FORWARD -> "FORWARD"
            PointerButton.WHEEL_UP -> "WHEEL UP"
            PointerButton.WHEEL_DOWN -> "WHEEL DOWN"
            PointerButton.WHEEL_LEFT -> "WHEEL LEFT"
            PointerButton.WHEEL_RIGHT -> "WHEEL RIGHT"
        }

        when (event.button) {
            PointerButton.WHEEL_UP -> {
                scrollDelta += SCROLL_STEP
                return
            }

            PointerButton.WHEEL_DOWN -> {
                scrollDelta -= SCROLL_STEP
                return
            }

            PointerButton.RIGHT -> {
                menuVisible = true
                menuX = x
                menuY = y
                menuHighlight = 0
                return
            }

            else -> Unit
        }

        if (event.button != PointerButton.LEFT) return

        if (menuVisible) {
            handleMenuClick(x, y)
            menuVisible = false
            return
        }

        val clear = clearButtonRect()
        if (x >= clear[0] && x < clear[0] + clear[2] && y >= clear[1] && y < clear[1] + clear[3]) {
            textBuffer.setLength(0)
            keyCount = 0
            return
        }

        if (isInTitleBar(x, y)) {
            draggingWindow = true
            dragGrabX = x - windowX
            dragGrabY = y - windowY
        }
    }

    private fun handleKeysym(keysym: Int) {
        when (keysym) {
            KEY_BACKSPACE, KEY_DELETE ->
                if (textBuffer.isNotEmpty()) textBuffer.deleteCharAt(textBuffer.length - 1)

            KEY_RETURN, KEY_KP_ENTER -> appendChar('\n')

            KEY_TAB -> repeat(4) { appendChar(' ') }

            KEY_ESCAPE -> menuVisible = false

            else -> if (keysym in 0x20..0x7E) appendChar(keysym.toChar())
        }
    }

    private fun appendChar(ch: Char) {
        if (textBuffer.length >= MAX_BUFFER_CHARS) return
        textBuffer.append(ch)
        keyCount++
    }

    private fun isInTitleBar(x: Int, y: Int): Boolean =
        x >= windowX && x < windowX + windowW && y >= windowY && y < windowY + titleBarHeight

    // ---------------------------------------------------------------------
    // Context menu
    // ---------------------------------------------------------------------

    /** `[x, y, w, h, padding, itemHeight]` — shared by drawing and hit-testing. */
    private fun menuBounds(): IntArray {
        val scale = uiScale
        val padding = 6 * scale
        val itemHeight = 20 * scale
        val widest = MENU_ITEMS.maxOf { it.length }
        val menuW = widest * 6 * scale + padding * 2 + 10 * scale
        val menuH = MENU_ITEMS.size * itemHeight + padding * 2
        val x = menuX.coerceIn(0, max(0, width - menuW - 4))
        val y = menuY.coerceIn(0, max(0, height - taskbarHeight - menuH - 4))
        return intArrayOf(x, y, menuW, menuH, padding, itemHeight)
    }

    private fun menuIndexAt(x: Int, y: Int): Int {
        val bounds = menuBounds()
        val insideX = x >= bounds[0] && x < bounds[0] + bounds[2]
        val insideY = y >= bounds[1] && y < bounds[1] + bounds[3]
        if (!insideX || !insideY) return -1
        val relative = y - bounds[1] - bounds[4]
        if (relative < 0) return -1
        val index = relative / bounds[5]
        return if (index in MENU_ITEMS.indices) index else -1
    }

    private fun handleMenuClick(x: Int, y: Int) {
        when (menuIndexAt(x, y)) {
            0 -> {
                textBuffer.setLength(0)
                keyCount = 0
            }

            1 -> scrollDelta = 0

            2 -> {
                windowX = (width - windowW) / 2
                windowY = max(8, (height - taskbarHeight - windowH) / 2)
            }

            else -> Unit // "ABOUT WINDROID" only acknowledges the click
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun draw() {
        synchronized(lock) {
            drawWallpaper()
            drawWindow()
            drawTaskbar()
            if (menuVisible) drawContextMenu()
            drawPointer()
            state.putImage(0, 0, width, height, frame)
        }
    }

    private fun drawWallpaper() {
        val top = 0xFF061B42.toInt()
        val bottom = 0xFF2B79C6.toInt()
        val span = max(1, height - 1)
        for (y in 0 until height) {
            fillSpan(y, 0, width, lerpColor(top, bottom, y.toFloat() / span), 255)
        }

        // Soft radial bloom, roughly where Windows 11 puts its "bloom" wallpaper.
        val bloomRadius = (height * 0.55f).toInt().coerceAtLeast(1)
        drawBloom((width * 0.70f).toInt(), (height * 0.34f).toInt(), bloomRadius, 0xFF9AD2FF.toInt())
    }

    private fun drawBloom(centerX: Int, centerY: Int, radius: Int, color: Int) {
        if (radius <= 0) return
        val radiusSquared = radius.toDouble() * radius
        val yStart = max(0, centerY - radius)
        val yEnd = min(height, centerY + radius)
        val xStart = max(0, centerX - radius)
        val xEnd = min(width, centerX + radius)
        for (y in yStart until yEnd) {
            val dy = y - centerY
            val rowBase = y * width
            for (x in xStart until xEnd) {
                val dx = x - centerX
                val distanceSquared = (dx * dx + dy * dy).toDouble()
                if (distanceSquared >= radiusSquared) continue
                val t = 1.0 - sqrt(distanceSquared) / radius
                val alpha = (t * t * BLOOM_ALPHA).toInt()
                if (alpha <= 0) continue
                val index = rowBase + x
                frame[index] = blend(frame[index], color, alpha)
            }
        }
    }

    private fun drawWindow() {
        val radius = (10 * uiScale).coerceIn(4, 26)
        val x = windowX
        val y = windowY
        val w = windowW
        val h = windowH

        // Layered drop shadow.
        fillRoundedRect(x + 3, y + 7, w, h, radius, SHADOW, 70)
        fillRoundedRect(x + 2, y + 4, w, h, radius, SHADOW, 90)

        // Window body + title bar (rounded top corners, square bottom edge).
        fillRoundedRect(x, y, w, h, radius, WINDOW_BODY, 255)
        fillRoundedRect(x, y, w, titleBarHeight + radius, radius, TITLE_BAR, 255)
        fillRect(x, y + radius, w, titleBarHeight - radius, TITLE_BAR, 255)

        // Caption buttons: minimize / maximize / close.
        val buttonSize = titleBarHeight
        val closeX = x + w - buttonSize
        val maximizeX = closeX - buttonSize
        val minimizeX = maximizeX - buttonSize
        drawMinimizeGlyph(minimizeX, y, buttonSize)
        drawMaximizeGlyph(maximizeX, y, buttonSize)
        drawCloseGlyph(closeX, y, buttonSize)
        fillRect(minimizeX, y, 1, titleBarHeight, TITLE_SEPARATOR, 255)

        val titleScale = uiScale
        drawText(
            "WINDROID INPUT TEST - NOTEPAD",
            x + 10 * titleScale,
            y + (titleBarHeight - 7 * titleScale) / 2,
            TITLE_TEXT,
            titleScale,
        )

        // Client area.
        val clientX = x + 1
        val clientY = y + titleBarHeight
        val clientW = w - 2
        val clientH = h - titleBarHeight - 1
        fillRect(clientX, clientY, clientW, clientH, CLIENT_BG, 255)

        val scale = uiScale
        val lineHeight = 10 * scale
        val left = clientX + 14 * scale
        val innerWidth = clientW - 28 * scale

        var cursorY = clientY + 10 * scale

        drawText("LIVE INPUT DIAGNOSTICS", left, cursorY, TEXT_DARK, scale)
        cursorY += lineHeight + 2 * scale
        drawText("EXTERNAL MOUSE + PHYSICAL KEYBOARD", left, cursorY, TEXT_MUTED, scale)
        cursorY += lineHeight + 3 * scale
        fillRect(left, cursorY, innerWidth, max(1, scale / 2), SEPARATOR, 255)
        cursorY += 8 * scale

        drawText("MOUSE: ($pointerX, $pointerY)", left, cursorY, TEXT_DARK, scale)
        cursorY += lineHeight
        drawText("LAST BUTTON: $lastButtonLabel", left, cursorY, TEXT_DARK, scale)
        cursorY += lineHeight
        drawText("SCROLL DELTA: $scrollDelta", left, cursorY, TEXT_DARK, scale)
        cursorY += lineHeight
        drawText("KEYS TYPED: $keyCount", left, cursorY, TEXT_DARK, scale)
        cursorY += lineHeight + 6 * scale

        drawText("TEXT BUFFER - TYPE ON YOUR KEYBOARD:", left, cursorY, TEXT_MUTED, scale)
        cursorY += lineHeight + 2 * scale

        // Text box, sized to whatever vertical space is left above the buttons.
        val clear = clearButtonRect()
        val boxBottom = clear[1] - 10 * scale
        val boxHeight = max(lineHeight, boxBottom - cursorY)
        fillRect(left, cursorY, innerWidth, boxHeight, TEXT_BOX_BG, 255)
        drawRectOutline(left, cursorY, innerWidth, boxHeight, TEXT_BOX_BORDER, max(1, scale / 2))

        val charsPerLine = max(1, (innerWidth - 10 * scale) / (6 * scale))
        val maxLines = max(1, (boxHeight - 10 * scale) / lineHeight)
        val lines = wrappedBufferLines(charsPerLine, maxLines)

        var textY = cursorY + 5 * scale
        lines.forEach { line ->
            drawText(line, left + 5 * scale, textY, TEXT_DARK, scale)
            textY += lineHeight
        }

        // Blinking caret after the last glyph.
        if (caretVisible) {
            val lastLine = lines.lastOrNull().orEmpty()
            val caretX = left + 5 * scale + lastLine.length * 6 * scale
            val caretY = cursorY + 5 * scale + (lines.size - 1) * lineHeight
            fillRect(caretX, caretY, 2 * scale, 7 * scale, TEXT_DARK, 255)
        }

        // Clear button.
        fillRoundedRect(clear[0], clear[1], clear[2], clear[3], 4 * scale, BUTTON_BG, 255)
        drawRectOutline(clear[0], clear[1], clear[2], clear[3], BUTTON_BORDER, max(1, scale / 2))
        val label = "CLEAR"
        drawText(
            label,
            clear[0] + (clear[2] - textWidth(label, scale)) / 2,
            clear[1] + (clear[3] - 7 * scale) / 2,
            TEXT_DARK,
            scale,
        )

        val hint = "RIGHT-CLICK ANYWHERE = CONTEXT MENU"
        drawText(
            hint,
            x + w - textWidth(hint, scale) - 14 * scale,
            clear[1] + (clear[3] - 7 * scale) / 2,
            TEXT_MUTED,
            scale,
        )
    }

    /** `[x, y, w, h]` of the CLEAR button, in screen pixels. */
    private fun clearButtonRect(): IntArray {
        val scale = uiScale
        val buttonW = textWidth("CLEAR", scale) + 16 * scale
        val buttonH = 10 * scale + 8 * scale
        return intArrayOf(
            windowX + 14 * scale,
            windowY + windowH - buttonH - 12 * scale,
            buttonW,
            buttonH,
        )
    }

    private fun drawTaskbar() {
        val barY = height - taskbarHeight
        fillRect(0, barY, width, taskbarHeight, TASKBAR, 240)
        fillRect(0, barY, width, 1, TASKBAR_EDGE, 200)

        val scale = uiScale
        val startSize = taskbarHeight - 10
        val startX = 10
        val startY = barY + 5
        val hovered = pointerX >= startX && pointerX < startX + startSize &&
            pointerY >= startY && pointerY < startY + startSize
        if (hovered) fillRoundedRect(startX, startY, startSize, startSize, 6, TASKBAR_HOVER, 255)
        drawWindowsLogo(startX + startSize / 4, startY + startSize / 4, startSize / 2)

        val textY = barY + (taskbarHeight - 7 * scale) / 2
        drawText("WINDROID DEMO", startX + startSize + 16 * scale, textY, TASKBAR_TEXT_DIM, scale)

        val clock = LocalTime.now().format(CLOCK_FORMAT)
        drawText(clock, width - textWidth(clock, scale) - 16 * scale, textY, TASKBAR_TEXT, scale)
    }

    private fun drawWindowsLogo(x: Int, y: Int, size: Int) {
        if (size < 4) return
        val cell = (size - 2) / 2
        if (cell <= 0) return
        fillRect(x, y, cell, cell, LOGO_COLOR, 255)
        fillRect(x + cell + 2, y, cell, cell, LOGO_COLOR, 255)
        fillRect(x, y + cell + 2, cell, cell, LOGO_COLOR, 255)
        fillRect(x + cell + 2, y + cell + 2, cell, cell, LOGO_COLOR, 255)
    }

    private fun drawContextMenu() {
        val bounds = menuBounds()
        val x = bounds[0]
        val y = bounds[1]
        val w = bounds[2]
        val h = bounds[3]
        val padding = bounds[4]
        val itemHeight = bounds[5]
        val scale = uiScale

        fillRoundedRect(x + 3, y + 5, w, h, 6 * scale, SHADOW, 90)
        fillRoundedRect(x, y, w, h, 6 * scale, MENU_BG, 255)
        drawRectOutline(x, y, w, h, MENU_BORDER, max(1, scale / 2))

        MENU_ITEMS.forEachIndexed { index, label ->
            val itemY = y + padding + index * itemHeight
            if (index == menuHighlight) {
                fillRect(x + padding, itemY, w - padding * 2, itemHeight, MENU_HOVER, 255)
                fillRect(x + padding, itemY, 3 * scale, itemHeight, MENU_ACCENT, 255)
            }
            drawText(
                label,
                x + padding + 8 * scale,
                itemY + (itemHeight - 7 * scale) / 2,
                TEXT_DARK,
                scale,
            )
        }
    }

    private fun drawMinimizeGlyph(x: Int, y: Int, size: Int) {
        val glyphW = size / 3
        fillRect(x + (size - glyphW) / 2, y + size / 2, glyphW, 1, TITLE_TEXT, 255)
    }

    private fun drawMaximizeGlyph(x: Int, y: Int, size: Int) {
        val glyph = size / 3
        val gx = x + (size - glyph) / 2
        val gy = y + (size - glyph) / 2
        drawRectOutline(gx, gy, glyph, glyph, TITLE_TEXT, 1)
    }

    private fun drawCloseGlyph(x: Int, y: Int, size: Int) {
        val glyph = size / 3
        val gx = x + (size - glyph) / 2
        val gy = y + (size - glyph) / 2
        for (i in 0 until glyph) {
            fillRect(gx + i, gy + i, 1, 1, TITLE_TEXT, 255)
            fillRect(gx + glyph - 1 - i, gy + i, 1, 1, TITLE_TEXT, 255)
        }
    }

    /** Classic arrow cursor; drawn last so it sits above every surface. */
    private fun drawPointer() {
        val x = pointerX
        val y = pointerY
        if (x < 0 || y < 0 || x >= width - 12 || y >= height - 16) return
        for (row in 0 until 14) {
            val span = if (row < 9) row / 2 + 1 else max(1, 14 - row)
            fillRect(x, y + row, max(1, span), 1, CURSOR_FILL, 255)
            fillRect(x + span, y + row, 1, 1, CURSOR_EDGE, 255)
        }
    }

    // --- text ------------------------------------------------------------

    private fun drawText(text: String, x: Int, y: Int, color: Int, scale: Int) {
        var cursor = x
        for (ch in text) {
            val glyph = glyphColumns(ch)
            for (column in 0 until 5) {
                val bits = glyph[column]
                if (bits == 0) continue
                for (row in 0 until 7) {
                    if (bits and (1 shl row) != 0) {
                        fillRect(cursor + column * scale, y + row * scale, scale, scale, color, 255)
                    }
                }
            }
            cursor += 6 * scale
        }
    }

    private fun textWidth(text: String, scale: Int): Int = text.length * 6 * scale

    private fun glyphColumns(ch: Char): IntArray {
        val code = ch.code
        val index = if (code in 32..126) code - 32 else '?'.code - 32
        return glyphTable[index]
    }

    private fun wrappedBufferLines(maxChars: Int, maxLines: Int): List<String> {
        val text = textBuffer.toString()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                lines += current.toString()
                current.setLength(0)
                continue
            }
            current.append(ch)
            if (current.length >= maxChars) {
                lines += current.toString()
                current.setLength(0)
            }
        }
        lines += current.toString()
        return if (lines.size > maxLines) lines.takeLast(maxLines) else lines
    }

    private val caretVisible: Boolean
        get() = (System.currentTimeMillis() / CARET_PERIOD_MS) % 2L == 0L

    // ---------------------------------------------------------------------
    // Low-level drawing primitives (all clipped to the framebuffer)
    // ---------------------------------------------------------------------

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int, alpha: Int) {
        if (w <= 0 || h <= 0) return
        val yStart = max(0, y)
        val yEnd = min(height, y + h)
        if (yEnd <= yStart) return
        for (row in yStart until yEnd) fillSpan(row, x, x + w, color, alpha)
    }

    private fun fillSpan(row: Int, x0: Int, x1: Int, color: Int, alpha: Int) {
        if (row < 0 || row >= height) return
        val start = max(0, x0)
        val end = min(width, x1)
        if (end <= start) return

        val base = row * width
        when {
            alpha >= 255 -> frame.fill(color, base + start, base + end)
            alpha <= 0 -> Unit
            else -> for (x in start until end) {
                val index = base + x
                frame[index] = blend(frame[index], color, alpha)
            }
        }
    }

    private fun drawRectOutline(x: Int, y: Int, w: Int, h: Int, color: Int, thickness: Int) {
        val t = max(1, thickness)
        fillRect(x, y, w, t, color, 255)
        fillRect(x, y + h - t, w, t, color, 255)
        fillRect(x, y, t, h, color, 255)
        fillRect(x + w - t, y, t, h, color, 255)
    }

    private fun fillRoundedRect(x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, alpha: Int) {
        if (w <= 0 || h <= 0) return
        val r = radius.coerceIn(0, min(w, h) / 2)
        for (row in 0 until h) {
            val inset = cornerInset(row, h, r)
            fillSpan(y + row, x + inset, x + w - inset, color, alpha)
        }
    }

    private fun cornerInset(row: Int, h: Int, r: Int): Int {
        if (r <= 0) return 0
        val dy = when {
            row < r -> r - row
            row > h - 1 - r -> row - (h - 1 - r)
            else -> 0
        }
        if (dy <= 0) return 0
        val inner = r * r - dy * dy
        if (inner <= 0) return r
        return (r - sqrt(inner.toDouble())).toInt().coerceIn(0, r)
    }

    private fun blend(dst: Int, src: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        if (a == 0) return dst
        if (a == 255) return src or (0xFF shl 24)
        val inv = 255 - a
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF
        val sr = (src ushr 16) and 0xFF
        val sg = (src ushr 8) and 0xFF
        val sb = src and 0xFF
        val r = (sr * a + dr * inv) / 255
        val g = (sg * a + dg * inv) / 255
        val b = (sb * a + db * inv) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val fr = (from ushr 16) and 0xFF
        val fg = (from ushr 8) and 0xFF
        val fb = from and 0xFF
        val tr = (to ushr 16) and 0xFF
        val tg = (to ushr 8) and 0xFF
        val tb = to and 0xFF
        val r = (fr + (tr - fr) * clamped).toInt().coerceIn(0, 255)
        val g = (fg + (tg - fg) * clamped).toInt().coerceIn(0, 255)
        val b = (fb + (tb - fb) * clamped).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clampWindow() {
        windowW = windowW.coerceIn(240, max(240, width))
        windowH = windowH.coerceIn(200, max(200, height - taskbarHeight - 16))
        windowX = windowX.coerceIn(0, max(0, width - windowW))
        windowY = windowY.coerceIn(0, max(0, height - taskbarHeight - windowH))
    }

    private companion object {
        const val CLOCK_TICK_MS = 1000L
        const val CARET_PERIOD_MS = 530L
        const val BLOOM_ALPHA = 46
        const val SCROLL_STEP = 1
        const val MAX_BUFFER_CHARS = 512

        const val KEY_BACKSPACE = 0xFF08
        const val KEY_TAB = 0xFF09
        const val KEY_RETURN = 0xFF0D
        const val KEY_KP_ENTER = 0xFF8D
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_DELETE = 0xFFFF

        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val MENU_ITEMS: List<String> = listOf(
            "CLEAR KEY LOG",
            "RESET SCROLL",
            "CENTER WINDOW",
            "ABOUT WINDROID",
        )

        // Palette (ARGB).
        val WINDOW_BODY = 0xFFF3F3F3.toInt()
        val CLIENT_BG = 0xFFFAFAFA.toInt()
        val TITLE_BAR = 0xFFEDEDF0.toInt()
        val TITLE_TEXT = 0xFF1B1B1F.toInt()
        val TITLE_SEPARATOR = 0xFFD6D6DC.toInt()
        val TEXT_DARK = 0xFF1B1B1F.toInt()
        val TEXT_MUTED = 0xFF6B7280.toInt()
        val SEPARATOR = 0xFFD9DDE5.toInt()
        val TEXT_BOX_BG = 0xFFFFFFFF.toInt()
        val TEXT_BOX_BORDER = 0xFFC9D0DB.toInt()
        val BUTTON_BG = 0xFFE9EDF3.toInt()
        val BUTTON_BORDER = 0xFFBCC4D1.toInt()
        val TASKBAR = 0xFF14161C.toInt()
        val TASKBAR_EDGE = 0xFF3B4353.toInt()
        val TASKBAR_HOVER = 0xFF2A303C.toInt()
        val TASKBAR_TEXT = 0xFFE9ECF2.toInt()
        val TASKBAR_TEXT_DIM = 0xFFA9B3C4.toInt()
        val LOGO_COLOR = 0xFF5EC7FF.toInt()
        val MENU_BG = 0xFFF7F7FA.toInt()
        val MENU_BORDER = 0xFFBFC5D2.toInt()
        val MENU_HOVER = 0xFFE4EEFB.toInt()
        val MENU_ACCENT = 0xFF0078D4.toInt()
        val SHADOW = 0xFF020A16.toInt()
        val CURSOR_FILL = 0xFFFFFFFF.toInt()
        val CURSOR_EDGE = 0xFF101418.toInt()

        /**
         * 5x7 bitmap font, printable ASCII 0x20..0x7E, one glyph per line.
         * Rows are top-to-bottom and separated by '|'; '#' lights a pixel.
         * Columns are packed into `IntArray(5)` with bit 0 = topmost row.
         */
        val FONT_DATA = """
.....|.....|.....|.....|.....|.....|.....
..#..|..#..|..#..|..#..|..#..|.....|..#..
.#.#.|.#.#.|.....|.....|.....|.....|.....
.#.#.|.#.#.|#####|.#.#.|#####|.#.#.|.#.#.
..#..|.####|#.#..|.###.|..#.#|####.|..#..
##..#|##..#|...#.|..#..|.#...|#..##|#..##
.##..|#..#.|#.#..|.#...|#.#.#|#..#.|.##.#
..#..|..#..|.....|.....|.....|.....|.....
...#.|..#..|.#...|.#...|.#...|..#..|...#.
.#...|..#..|...#.|...#.|...#.|..#..|.#...
.....|#.#.#|.###.|#####|.###.|#.#.#|.....
.....|..#..|..#..|#####|..#..|..#..|.....
.....|.....|.....|.....|.....|.##..|..#..
.....|.....|.....|#####|.....|.....|.....
.....|.....|.....|.....|.....|.##..|.##..
....#|...#.|...#.|..#..|.#...|.#...|#....
.###.|#...#|#..##|#.#.#|##..#|#...#|.###.
..#..|.##..|..#..|..#..|..#..|..#..|.###.
.###.|#...#|....#|...#.|..#..|.#...|#####
#####|...#.|..#..|...#.|....#|#...#|.###.
...#.|..##.|.#.#.|#..#.|#####|...#.|...#.
#####|#....|####.|....#|....#|#...#|.###.
..##.|.#...|#....|####.|#...#|#...#|.###.
#####|....#|...#.|..#..|.#...|.#...|.#...
.###.|#...#|#...#|.###.|#...#|#...#|.###.
.###.|#...#|#...#|.####|....#|...#.|.##..
.....|.##..|.##..|.....|.##..|.##..|.....
.....|.##..|.##..|.....|.##..|..#..|.#...
...#.|..#..|.#...|#....|.#...|..#..|...#.
.....|.....|#####|.....|#####|.....|.....
.#...|..#..|...#.|....#|...#.|..#..|.#...
.###.|#...#|....#|...#.|..#..|.....|..#..
.###.|#...#|#.###|#.#.#|#.###|#....|.###.
.###.|#...#|#...#|#####|#...#|#...#|#...#
####.|#...#|#...#|####.|#...#|#...#|####.
.###.|#...#|#....|#....|#....|#...#|.###.
###..|#..#.|#...#|#...#|#...#|#..#.|###..
#####|#....|#....|####.|#....|#....|#####
#####|#....|#....|####.|#....|#....|#....
.###.|#...#|#....|#.###|#...#|#...#|.####
#...#|#...#|#...#|#####|#...#|#...#|#...#
.###.|..#..|..#..|..#..|..#..|..#..|.###.
..###|...#.|...#.|...#.|...#.|#..#.|.##..
#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#
#....|#....|#....|#....|#....|#....|#####
#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#
#...#|##..#|#.#.#|#..##|#...#|#...#|#...#
.###.|#...#|#...#|#...#|#...#|#...#|.###.
####.|#...#|#...#|####.|#....|#....|#....
.###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#
####.|#...#|#...#|####.|#.#..|#..#.|#...#
####.|#....|#....|.###.|....#|....#|####.
#####|..#..|..#..|..#..|..#..|..#..|..#..
#...#|#...#|#...#|#...#|#...#|#...#|.###.
#...#|#...#|#...#|#...#|#...#|.#.#.|..#..
#...#|#...#|#...#|#.#.#|#.#.#|##.##|#...#
#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#
#...#|#...#|.#.#.|..#..|..#..|..#..|..#..
#####|....#|...#.|..#..|.#...|#....|#####
..###|..#..|..#..|..#..|..#..|..#..|..###
#....|.#...|.#...|..#..|...#.|...#.|....#
###..|..#..|..#..|..#..|..#..|..#..|###..
..#..|.#.#.|#...#|.....|.....|.....|.....
.....|.....|.....|.....|.....|.....|#####
.#...|..#..|.....|.....|.....|.....|.....
.....|.....|.###.|....#|.####|#...#|.####
#....|#....|####.|#...#|#...#|#...#|####.
.....|.....|.###.|#....|#....|#...#|.###.
....#|....#|.####|#...#|#...#|#...#|.####
.....|.....|.###.|#...#|#####|#....|.###.
..##.|.#...|####.|.#...|.#...|.#...|.#...
.....|.####|#...#|#...#|.####|....#|.###.
#....|#....|####.|#...#|#...#|#...#|#...#
..#..|.....|.##..|..#..|..#..|..#..|.###.
...#.|.....|..##.|...#.|...#.|#..#.|.##..
#....|#....|#..#.|#.#..|##...|#.#..|#..#.
.##..|..#..|..#..|..#..|..#..|..#..|.###.
.....|.....|##.#.|#.#.#|#.#.#|#.#.#|#.#.#
.....|.....|####.|#...#|#...#|#...#|#...#
.....|.....|.###.|#...#|#...#|#...#|.###.
.....|####.|#...#|#...#|####.|#....|#....
.....|.####|#...#|#...#|.####|....#|....#
.....|.....|#.##.|##...|#....|#....|#....
.....|.....|.####|#....|.###.|....#|####.
.#...|.#...|####.|.#...|.#...|.#..#|..##.
.....|.....|#...#|#...#|#...#|#...#|.####
.....|.....|#...#|#...#|#...#|.#.#.|..#..
.....|.....|#...#|#...#|#.#.#|#.#.#|.#.#.
.....|.....|#...#|.#.#.|..#..|.#.#.|#...#
.....|#...#|#...#|#...#|.####|....#|.###.
.....|.....|#####|...#.|..#..|.#...|#####
...#.|..#..|..#..|.#...|..#..|..#..|...#.
..#..|..#..|..#..|..#..|..#..|..#..|..#..
.#...|..#..|..#..|...#.|..#..|..#..|.#...
.....|.....|.#..#|#.#.#|#..#.|.....|.....
""".trim()

        /** Glyph table indexed by `char - 0x20`. */
        val glyphTable: Array<IntArray> = buildGlyphTable()

        fun buildGlyphTable(): Array<IntArray> {
            val rows = FONT_DATA.lines().filter { it.isNotBlank() }
            val table = Array(95) { IntArray(5) }
            rows.forEachIndexed { glyphIndex, line ->
                if (glyphIndex >= table.size) return@forEachIndexed
                val groups = line.split('|')
                val columns = IntArray(5)
                for (row in 0 until 7) {
                    val group = groups.getOrNull(row) ?: continue
                    for (column in 0 until 5) {
                        if (group.getOrNull(column) == '#') {
                            columns[column] = columns[column] or (1 shl row)
                        }
                    }
                }
                table[glyphIndex] = columns
            }
            return table
        }
    }
}
