package com.windroid.app

import android.app.Application
import android.content.Context
import com.windroid.app.data.ContainerRepository
import com.windroid.app.runtime.ImageFsManager
import java.io.File

/**
 * Process-wide entry point for WinDroid.
 *
 * This class is the service locator for the two long-lived singletons the UI
 * needs:
 *
 *  * [containerRepository] — JSON-backed store of every
 *    [com.windroid.app.core.model.ContainerConfig], persisted at
 *    `filesDir/containers.json`.
 *  * [imageFsManager] — owner of the extracted Wine + Box64 rootfs under
 *    `filesDir`.
 *
 * Both are constructed eagerly in [onCreate] so any Activity (including
 * [SessionActivity], which the system may relaunch on its own) can read them
 * straight off the application without null checks or re-initialisation.
 */
class WinDroidApp : Application() {

    /** Persisted containers, shared by the list, the editor and the session. */
    lateinit var containerRepository: ContainerRepository
        private set

    /** Extracted runtime rootfs / Wine / Box64 installation manager. */
    lateinit var imageFsManager: ImageFsManager
        private set

    override fun onCreate() {
        super.onCreate()

        containerRepository = ContainerRepository(File(filesDir, CONTAINER_STORAGE_NAME))
        imageFsManager = ImageFsManager(filesDir)

        // Populate `containers` before the first composition reads it.
        containerRepository.load()
    }

    private companion object {
        const val CONTAINER_STORAGE_NAME = "containers.json"
    }
}

/**
 * Convenience accessor used by Activities and Composables.
 *
 * @return the process-wide [WinDroidApp] instance.
 */
fun Context.winDroidApp(): WinDroidApp = applicationContext as WinDroidApp
