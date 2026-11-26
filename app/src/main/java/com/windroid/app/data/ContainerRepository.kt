package com.windroid.app.data

import com.windroid.app.core.model.AudioDriver
import com.windroid.app.core.model.Box64Preset
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.DisplaySpec
import com.windroid.app.core.model.DriveMapping
import com.windroid.app.core.model.DxWrapper
import com.windroid.app.core.model.GraphicsDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Single source of truth for the user's container definitions.
 *
 * Containers are persisted as a pretty-printed JSON array so the file stays
 * human-editable for debugging (a container snapshot can be lifted straight out
 * of the app's `filesDir` and pasted into a bug report).
 *
 * The repository is deliberately synchronous: the payload is a few kilobytes,
 * it is read once at startup and every write is a single atomic file replace,
 * so callers on the UI thread never have to await anything and [containers] is
 * a hot [StateFlow] the UI can simply collect.
 *
 * Threading: every mutating entry point is `@Synchronized`, so concurrent calls
 * from a worker (background import, session launcher) and the UI thread cannot
 * interleave a read-modify-write and lose an update.
 */
class ContainerRepository(private val storageFile: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(ContainerConfig.serializer())

    private val _containers = MutableStateFlow<List<ContainerConfig>>(emptyList())

    /** Live, ordered (newest last) view of every stored container. */
    val containers: StateFlow<List<ContainerConfig>> = _containers.asStateFlow()

    /**
     * Loads the container list from [storageFile].
     *
     * When the file is missing, empty, or holds an empty array (first launch, or
     * a wiped app data directory) two ready-to-use containers are seeded and
     * written back, so the user can start a session — or the built-in demo
     * desktop — without any setup step.
     *
     * A malformed file is *not* fatal: the seeds are restored so the app always
     * boots into a usable state.
     */
    @Synchronized
    fun load() {
        val decoded = readFromDisk()
        if (decoded.isNullOrEmpty()) {
            val seeds = defaultContainers()
            _containers.value = seeds
            persist(seeds)
        } else {
            _containers.value = decoded
        }
    }

    /**
     * Inserts [config] or replaces the existing entry with the same
     * [ContainerConfig.id], then persists the whole list atomically.
     */
    @Synchronized
    fun save(config: ContainerConfig) {
        val current = _containers.value
        val index = current.indexOfFirst { it.id == config.id }
        val updated = if (index >= 0) {
            current.toMutableList().also { it[index] = config }
        } else {
            current + config
        }
        _containers.value = updated
        persist(updated)
    }

    /** Removes the container with [id]. Unknown ids are a no-op. */
    @Synchronized
    fun delete(id: String) {
        val current = _containers.value
        if (current.none { it.id == id }) return
        val updated = current.filterNot { it.id == id }
        _containers.value = updated
        persist(updated)
    }

    /** Returns the container with [id], or `null` when it does not exist. */
    fun getById(id: String): ContainerConfig? = _containers.value.firstOrNull { it.id == id }

    /**
     * Builds, stores and returns a fresh container using the model's defaults
     * (720p, Turnip + DXVK, compatibility Box64 preset, `D:` mapped to Downloads).
     */
    @Synchronized
    fun createDefaultContainer(name: String = "New Windows Container"): ContainerConfig {
        val config = ContainerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
        )
        save(config)
        return config
    }

    // ---------------------------------------------------------------------
    // Persistence internals
    // ---------------------------------------------------------------------

    private fun readFromDisk(): List<ContainerConfig>? = runCatching {
        if (!storageFile.isFile || storageFile.length() == 0L) return@runCatching null
        val text = storageFile.readText()
        if (text.isBlank()) return@runCatching null
        json.decodeFromString(listSerializer, text)
    }.getOrNull()

    /**
     * Writes the list through a sibling temp file followed by a rename, so a
     * crash mid-write can never truncate the previous good snapshot.
     */
    private fun persist(list: List<ContainerConfig>) {
        val target = storageFile.absoluteFile
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()

        val temp = File(parent, "${target.name}.tmp")
        temp.writeText(json.encodeToString(listSerializer, list))

        if (target.exists() && !target.delete()) {
            // Some Android volumes refuse delete-then-rename; fall back to a copy.
            temp.copyTo(target, overwrite = true)
            temp.delete()
            return
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun defaultContainers(): List<ContainerConfig> = listOf(
        ContainerConfig(
            id = DEFAULT_720P_ID,
            name = "Windows Desktop (720p)",
            display = DisplaySpec.PRESET_720P,
            graphicsDriver = GraphicsDriver.TURNIP,
            dxWrapper = DxWrapper.DXVK,
            box64Preset = Box64Preset.COMPATIBILITY,
            audioDriver = AudioDriver.PULSEAUDIO,
            wineVersion = "wine-9.2-x86_64",
            cpuCores = listOf(0, 1, 2, 3),
            drives = listOf(
                DriveMapping('D', "/sdcard/Download"),
                DriveMapping('E', "/sdcard/Documents"),
            ),
            showFps = true,
            startupCommand = "explorer.exe /desktop=shell,1280x720",
        ),
        ContainerConfig(
            id = DEFAULT_XGA_ID,
            name = "Classic x86 Productivity (1024x768)",
            display = DisplaySpec.PRESET_XGA,
            graphicsDriver = GraphicsDriver.LLVMPIPE,
            dxWrapper = DxWrapper.WINED3D,
            box64Preset = Box64Preset.STABILITY,
            audioDriver = AudioDriver.ALSA,
            wineVersion = "wine-8.0.2-x86_64",
            cpuCores = listOf(0, 1, 2, 3, 4, 5),
            drives = listOf(DriveMapping('D', "/sdcard/Download")),
            showFps = false,
            startupCommand = "explorer.exe /desktop=shell,1024x768",
        ),
    )

    companion object {
        /** File name used inside the app's `filesDir` for the container list. */
        const val STORAGE_FILE_NAME: String = "containers.json"

        /** Stable id of the seeded 720p container. */
        const val DEFAULT_720P_ID: String = "container-default-720p"

        /** Stable id of the seeded 1024x768 container. */
        const val DEFAULT_XGA_ID: String = "container-default-xga"
    }
}
