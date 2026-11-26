package com.windroid.app.core.model

/** Which runtime back end a session will actually run on. */
enum class RuntimeMode {
    /** Real Wine + Box64 + proot stack installed in the imagefs. */
    WINE_BOX64,

    /** Built-in demo surface used when the runtime payload is absent. */
    BUILTIN_DEMO,
}

/**
 * Installation state of a single runtime component.
 *
 * @property name component identifier, e.g. "wine" or "box64"
 * @property installed whether the component is present and executable
 * @property path absolute path to the component on the device
 * @property detail free-form diagnostic, e.g. version string or failure reason
 */
data class RuntimeComponentStatus(
    val name: String,
    val installed: Boolean,
    val path: String,
    val detail: String = "",
)

/** Aggregate readiness of the Wine + Box64 runtime. */
data class RuntimeStatus(
    val imageFsInstalled: Boolean,
    val prootInstalled: Boolean,
    val box64Installed: Boolean,
    val wineInstalled: Boolean,
    val components: List<RuntimeComponentStatus> = emptyList(),
) {
    val isFullRuntimeReady: Boolean
        get() = imageFsInstalled && prootInstalled && box64Installed && wineInstalled
}

/**
 * Progress event emitted while installing or verifying the runtime.
 *
 * @property stage coarse stage id, e.g. "extract" or "verify"
 * @property fraction progress in the 0f..1f range
 * @property message human readable status line
 * @property isComplete terminal success marker
 * @property error non-null when the stage failed
 */
data class InstallProgress(
    val stage: String,
    val fraction: Float,
    val message: String,
    val isComplete: Boolean = false,
    val error: String? = null,
)

/** Lifecycle of a running container session. */
enum class SessionState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}
