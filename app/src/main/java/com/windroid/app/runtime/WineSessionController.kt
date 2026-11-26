package com.windroid.app.runtime

import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.RuntimeMode
import com.windroid.app.core.model.SessionState
import com.windroid.app.xserver.XServerSocketHost
import com.windroid.app.xserver.XServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Owns one running container: the X11 server, the guest process tree, and the
 * log stream the Session UI renders.
 *
 * The class has two mutually exclusive modes, decided at [start] time by
 * probing the imagefs:
 *
 * * [RuntimeMode.WINE_BOX64] — a real `proot -> box64 -> wine` process is
 *   spawned from [WineCommandBuilder.buildLaunchSpec] and its stdout/stderr are
 *   streamed into [logs].
 * * [RuntimeMode.BUILTIN_DEMO] — no runtime payload on disk yet, so the
 *   [DemoDesktopRenderer] paints an interactive desktop into the very same
 *   [XServerState]. The X11 socket host runs in both modes, so the surface, the
 *   input pipeline and the whole UI path are exercised identically — the only
 *   difference is who paints the framebuffer.
 *
 * Threading: [xServerState] is mutated by the renderer/guest threads and read by
 * the display loop, exactly like a real X server; [sessionState], [runtimeMode]
 * and [logs] are [StateFlow]s that are safe to collect from any thread.
 */
class WineSessionController(
    private val imageFsManager: ImageFsManager,
    val container: ContainerConfig,
) {

    /** Framebuffer + input hub shared by the renderer and the display surface. */
    val xServerState: XServerState = XServerState(
        container.display.width,
        container.display.height,
        container.display.dpi,
    )

    private val _sessionState = MutableStateFlow(SessionState.IDLE)

    /** Lifecycle of this session: IDLE -> STARTING -> RUNNING -> STOPPING -> IDLE. */
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _runtimeMode = MutableStateFlow(RuntimeMode.BUILTIN_DEMO)

    /** Which back end is actually painting the surface right now. */
    val runtimeMode: StateFlow<RuntimeMode> = _runtimeMode.asStateFlow()

    private val logLock = Any()
    private val _logs = MutableStateFlow<List<String>>(emptyList())

    /** Ring buffer of the last [MAX_LOG_LINES] lines, oldest first. */
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var socketHost: XServerSocketHost? = null
    private var guestProcess: Process? = null
    private var demoRenderer: DemoDesktopRenderer? = null
    private val jobs = mutableListOf<Job>()

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    /**
     * Starts the X11 server and then either the real Wine runtime or the demo.
     *
     * Idempotent: calling it while a session is already starting or running is a
     * no-op, so an Activity re-creation cannot spawn a second guest.
     */
    fun start(scope: CoroutineScope) {
        val current = _sessionState.value
        if (current == SessionState.STARTING || current == SessionState.RUNNING) return

        _sessionState.value = SessionState.STARTING
        log("WinDroid session starting")
        log(
            "container '${container.name}' · ${container.display.resolutionLabel}" +
                " @ ${container.display.dpi} dpi",
        )
        log(
            "graphics=${container.graphicsDriver.name} dx=${container.dxWrapper.name} " +
                "box64=${container.box64Preset.name} audio=${container.audioDriver.name}",
        )

        startXServer(scope)

        val status = imageFsManager.inspectRuntime()
        status.components.forEach { component ->
            val mark = if (component.installed) "OK  " else "MISS"
            log("[$mark] ${component.name}: ${component.detail}")
        }

        if (status.isFullRuntimeReady) {
            launchWineRuntime(scope)
        } else {
            startDemoMode()
        }
    }

    /**
     * Tears the session down: kills the guest process tree, stops the demo
     * renderer and closes the X11 socket. Safe to call when nothing is running.
     */
    fun stop() {
        val wasActive = _sessionState.value != SessionState.IDLE
        _sessionState.value = SessionState.STOPPING

        jobs.forEach { it.cancel() }
        jobs.clear()

        guestProcess?.let { process ->
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        guestProcess = null

        demoRenderer?.stop()
        demoRenderer = null

        socketHost?.stop()
        socketHost = null

        // Guest scratch data is per-session; leaving it behind only costs space.
        imageFsManager.tmpDir.deleteRecursively()

        if (wasActive) log("session stopped")

        _sessionState.value = SessionState.IDLE
    }

    // ---------------------------------------------------------------------
    // X11 server
    // ---------------------------------------------------------------------

    private fun startXServer(scope: CoroutineScope) {
        val host = XServerSocketHost(state = xServerState, displayNumber = DISPLAY_NUMBER)
        socketHost = host

        host.start(scope) { line -> log("x11| $line") }

        jobs += scope.launch {
            host.isRunning.collect { running ->
                log(if (running) "X11 socket ready on :$DISPLAY_NUMBER" else "X11 socket closed")
            }
        }
        jobs += scope.launch {
            host.connectedClients.collect { clients ->
                if (clients > 0) log("X11 client connected ($clients active)")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Real runtime
    // ---------------------------------------------------------------------

    private fun launchWineRuntime(scope: CoroutineScope) {
        _runtimeMode.value = RuntimeMode.WINE_BOX64

        val rootFs = imageFsManager.rootFsDir
        val tmp = imageFsManager.tmpDir
        val spec = WineCommandBuilder.buildLaunchSpec(
            container = container,
            rootFsDir = rootFs,
            tmpDir = tmp,
            displayNumber = DISPLAY_NUMBER,
        )

        // Directories the guest expects to exist before proot re-roots.
        spec.workingDir.mkdirs()
        tmp.mkdirs()
        imageFsManager.winePrefixDir.mkdirs()

        log("launching: ${spec.command.joinToString(" ")}")

        try {
            val builder = ProcessBuilder(spec.command)
                .directory(spec.workingDir)
                .redirectErrorStream(false)

            // Start from a clean slab: the Android process environment leaks
            // variables (LD_PRELOAD, JAVA_HOME, ...) that break box64's loader.
            builder.environment().clear()
            builder.environment().putAll(spec.environment)

            val process = builder.start()
            guestProcess = process

            _sessionState.value = SessionState.RUNNING
            // `java.lang.Process.pid()` is not part of the Android SDK surface,
            // so the process handle itself is the most portable identity we have.
            log("wine runtime up: $process")

            pumpStream(scope, process.inputStream.bufferedReader(), "wine")
            pumpStream(scope, process.errorStream.bufferedReader(), "wine!")

            jobs += scope.launch {
                val exitCode = withContext(Dispatchers.IO) {
                    runCatching { process.waitFor() }.getOrDefault(-1)
                }
                val stillOurs = guestProcess === process
                if (stillOurs) guestProcess = null
                log("wine exited with code $exitCode")
                if (stillOurs) _sessionState.value = SessionState.IDLE
            }
        } catch (io: IOException) {
            log("failed to launch wine runtime: ${io.message}")
            log("falling back to the built-in demo desktop")
            _sessionState.value = SessionState.ERROR
            startDemoMode()
        }
    }

    /** Streams a guest stream into [logs] without blocking the caller. */
    private fun pumpStream(scope: CoroutineScope, reader: BufferedReader, tag: String) {
        val job = scope.launch(Dispatchers.IO) {
            runCatching {
                reader.useLines { lines ->
                    lines.forEach { line -> log("$tag| $line") }
                }
            }
        }
        jobs += job
    }

    // ---------------------------------------------------------------------
    // Demo mode
    // ---------------------------------------------------------------------

    private fun startDemoMode() {
        _runtimeMode.value = RuntimeMode.BUILTIN_DEMO

        val renderer = demoRenderer ?: DemoDesktopRenderer(xServerState).also { demoRenderer = it }
        renderer.start()

        log("no Wine/Box64 imagefs found — starting the BUILTIN DEMO desktop")
        log("your external mouse and keyboard are live: move, click, scroll and type")
        log("right-click the desktop to prove the Android back button maps to X11 button 3")
        log("--- to unlock real Windows apps ---")
        log("1. obtain a Winlator-compatible imagefs (usr/bin/proot, usr/local/bin/box64, opt/wine)")
        log("2. repack it as .zip ('zip -r -y imagefs.zip .') — .tar.xz cannot be unpacked on-device")
        log("3. import it from Settings > Runtime, then relaunch this container")
        log("runtime root: ${imageFsManager.rootFsDir.absolutePath}")

        _sessionState.value = SessionState.RUNNING
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private fun log(line: String) {
        val stamped = "${TIME_FORMAT.format(LocalTime.now())} $line"
        synchronized(logLock) {
            val current = _logs.value
            val next = if (current.size < MAX_LOG_LINES) {
                current + stamped
            } else {
                current.subList(current.size - MAX_LOG_LINES + 1, current.size) + stamped
            }
            _logs.value = next
        }
    }

    companion object {
        /** X11 display index handed to both the socket host and `DISPLAY`. */
        const val DISPLAY_NUMBER: Int = 0

        /** Ring buffer depth; keeps the log pane bounded on long sessions. */
        const val MAX_LOG_LINES: Int = 300

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
