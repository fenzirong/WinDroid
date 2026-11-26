package com.windroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windroid.app.core.model.RuntimeStatus
import com.windroid.app.input.ExternalDeviceMonitor
import com.windroid.app.ui.WinDroidNavHost
import com.windroid.app.ui.screens.DefaultInputConfigStore
import com.windroid.app.ui.theme.WinDroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher activity.
 *
 * Responsibilities are intentionally narrow:
 *
 *  * own the single [ExternalDeviceMonitor] so the container list, the input
 *    settings screen and the session HUD all read the same device snapshot;
 *  * own the global default `InputConfig` (persisted through
 *    [DefaultInputConfigStore]) and the cached `RuntimeStatus`;
 *  * hand everything to [WinDroidNavHost], which owns navigation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = applicationContext as WinDroidApp

        setContent {
            WinDroidTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                // One monitor for the whole UI: started when the shell composes,
                // stopped when the activity is torn down.
                val deviceMonitor = remember(context) { ExternalDeviceMonitor(context) }
                val peripherals by deviceMonitor.peripherals.collectAsStateWithLifecycle()
                DisposableEffect(deviceMonitor) {
                    deviceMonitor.start()
                    onDispose { deviceMonitor.stop() }
                }

                var defaultInputConfig by remember {
                    mutableStateOf(DefaultInputConfigStore.load(context))
                }
                var runtimeStatus by remember { mutableStateOf<RuntimeStatus?>(null) }

                // Runtime inspection touches the filesystem, so it never runs on
                // the main thread.
                LaunchedEffect(app.imageFsManager) {
                    runtimeStatus = withContext(Dispatchers.IO) {
                        app.imageFsManager.inspectRuntime()
                    }
                }

                val refreshRuntimeStatus: () -> Unit = {
                    scope.launch {
                        runtimeStatus = withContext(Dispatchers.IO) {
                            app.imageFsManager.inspectRuntime()
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WinDroidNavHost(
                        containerRepository = app.containerRepository,
                        imageFsManager = app.imageFsManager,
                        peripherals = peripherals,
                        onRefreshPeripherals = { deviceMonitor.refreshNow() },
                        runtimeStatus = runtimeStatus,
                        onRefreshRuntimeStatus = refreshRuntimeStatus,
                        defaultInputConfig = defaultInputConfig,
                        onUpdateDefaultInputConfig = { updated ->
                            defaultInputConfig = updated
                            DefaultInputConfigStore.save(context, updated)
                        },
                        onLaunchSession = { containerId ->
                            startActivity(SessionActivity.newIntent(this, containerId))
                        },
                    )
                }
            }
        }
    }
}
