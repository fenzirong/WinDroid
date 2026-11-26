package com.windroid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.SessionState
import com.windroid.app.input.ExternalDeviceMonitor
import com.windroid.app.input.KeyboardInputProcessor
import com.windroid.app.runtime.ImageFsManager
import com.windroid.app.runtime.WineSessionController
import com.windroid.app.ui.session.SessionHudOverlay
import com.windroid.app.ui.theme.WinDroidTheme
import com.windroid.app.xserver.XServerSurfaceView

/**
 * Full-screen Windows session.
 *
 * The activity window itself stays out of the way: immersive system bars, screen
 * kept awake, and a black background so any letterboxing around the X11 canvas is
 * invisible. All session logic lives in [WineSessionController]; every in-session
 * affordance lives in [SessionHudOverlay], which floats above the surface and can
 * be collapsed to a single translucent pill so it never blocks an external mouse.
 */
class SessionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = applicationContext as WinDroidApp
        val container = intent.getStringExtra(EXTRA_CONTAINER_ID)
            ?.let { containerId -> app.containerRepository.getById(containerId) }

        if (container == null) {
            // Nothing to run: bail out instead of showing an empty black screen.
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveMode()

        setContent {
            // Sessions always use the dark ramp: the canvas behind the HUD is black.
            WinDroidTheme(darkTheme = true) {
                SessionScreen(
                    container = container,
                    imageFsManager = app.imageFsManager,
                    onExit = { finish() },
                )
            }
        }
    }

    /** The system bar can reappear after a gesture or focus change; re-hide it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    private fun applyImmersiveMode() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }

    companion object {

        /** Container to launch, resolved through `ContainerRepository.getById`. */
        const val EXTRA_CONTAINER_ID: String = "com.windroid.app.extra.CONTAINER_ID"

        /** Builds the launch intent for [containerId]. */
        fun newIntent(context: Context, containerId: String): Intent =
            Intent(context, SessionActivity::class.java)
                .putExtra(EXTRA_CONTAINER_ID, containerId)
    }
}

/**
 * Composes the X11 surface, the session controller and the floating HUD.
 *
 * The controller is created once per container id and its `start`/`stop` pair is
 * tied to the composition lifecycle, so leaving the activity always tears the
 * session down.
 */
@Composable
private fun SessionScreen(
    container: ContainerConfig,
    imageFsManager: ImageFsManager,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val compositionScope = rememberCoroutineScope()

    val controller = remember(container.id) {
        WineSessionController(imageFsManager = imageFsManager, container = container)
    }
    val sessionState by controller.sessionState.collectAsStateWithLifecycle()
    val runtimeMode by controller.runtimeMode.collectAsStateWithLifecycle()
    val logs by controller.logs.collectAsStateWithLifecycle()

    // Live badge for externally attached mouse / keyboard inside the session.
    val deviceMonitor = remember(context) { ExternalDeviceMonitor(context) }
    val peripherals by deviceMonitor.peripherals.collectAsStateWithLifecycle()
    DisposableEffect(deviceMonitor) {
        deviceMonitor.start()
        onDispose { deviceMonitor.stop() }
    }

    var surfaceView by remember { mutableStateOf<XServerSurfaceView?>(null) }
    var pointerCaptured by remember { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }

    DisposableEffect(controller) {
        controller.start(compositionScope)
        onDispose { controller.stop() }
    }

    // Attach once the surface view instance exists: AndroidView creates it during
    // layout, which re-keys this effect on the following composition.
    DisposableEffect(surfaceView) {
        val view = surfaceView
        if (view != null) {
            view.attachSession(
                state = controller.xServerState,
                inputConfig = container.inputConfig,
                onHotkey = { hotkey ->
                    when (hotkey) {
                        KeyboardInputProcessor.SessionHotkey.TOGGLE_POINTER_CAPTURE -> {
                            val next = !pointerCaptured
                            pointerCaptured = next
                            view.setPointerCaptured(next)
                        }

                        KeyboardInputProcessor.SessionHotkey.TOGGLE_HUD -> {
                            hudVisible = !hudVisible
                        }

                        KeyboardInputProcessor.SessionHotkey.EXIT_SESSION -> onExit()
                    }
                },
                onPointerCaptureChanged = { captured -> pointerCaptured = captured },
            )
        }
        onDispose { view?.detachSession() }
    }

    LaunchedEffect(surfaceView) {
        surfaceView?.updateInputConfig(container.inputConfig)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                XServerSurfaceView(ctx).also { created -> surfaceView = created }
            },
        )

        when (sessionState) {
            SessionState.STARTING -> SessionStartingOverlay()
            SessionState.ERROR -> SessionErrorOverlay()
            else -> Unit
        }

        if (hudVisible) {
            SessionHudOverlay(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                sessionState = sessionState,
                runtimeMode = runtimeMode,
                pointerCaptured = pointerCaptured,
                peripherals = peripherals,
                logs = logs,
                onTogglePointerCapture = { next ->
                    pointerCaptured = next
                    surfaceView?.setPointerCaptured(next)
                },
                onSendCombo = { keyCodes ->
                    surfaceView?.sendSyntheticCombo(*keyCodes)
                },
                onHideHud = { hudVisible = false },
                onExit = {
                    controller.stop()
                    onExit()
                },
            )
        } else {
            // Tiny restore handle after "hide HUD" or Ctrl+Alt+M.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clickable { hudVisible = true },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.32f),
                contentColor = Color(0xFFF3F3F3),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.hud_show),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Shown while the X server / Wine stack is still coming up. */
@Composable
private fun SessionStartingOverlay() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color(0xFF4CC2FF))
        Text(
            text = stringResource(R.string.session_state_starting),
            color = Color(0xFFF3F3F3),
        )
    }
}

/** Shown when the controller reports a terminal failure. */
@Composable
private fun SessionErrorOverlay() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.session_state_error),
            color = Color(0xFFFF6B6B),
        )
    }
}
