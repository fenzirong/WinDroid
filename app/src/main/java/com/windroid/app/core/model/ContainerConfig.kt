package com.windroid.app.core.model

import kotlinx.serialization.Serializable

/** GPU backend used to render a container's X11 server. */
@Serializable
enum class GraphicsDriver(val displayName: String) {
    TURNIP("Turnip (Adreno Vulkan)"),
    VIRGL("VirGL (Universal OpenGL)"),
    ZINK("Zink (OpenGL over Vulkan)"),
    LLVMPIPE("LLVMpipe (Software CPU)"),
}

/** Direct3D translation layer used by Wine. */
@Serializable
enum class DxWrapper(val displayName: String) {
    DXVK("DXVK (D3D9-11 -> Vulkan)"),
    VKD3D("VKD3D-Proton (D3D12 -> Vulkan)"),
    WINED3D("WineD3D (OpenGL)"),
    CNC_DDRAW("CNC DDraw (DirectDraw)"),
}

/** Box64 tuning preset: lower compatibility, higher translation speed. */
@Serializable
enum class Box64Preset(val displayName: String) {
    PERFORMANCE("Performance"),
    INTERMEDIATE("Intermediate"),
    COMPATIBILITY("Compatibility"),
    STABILITY("Stability"),
}

/** Audio backend exposed to the Windows guest. */
@Serializable
enum class AudioDriver(val displayName: String) {
    PULSEAUDIO("PulseAudio"),
    ALSA("ALSA"),
    DISABLED("Disabled"),
}

/**
 * A Windows drive letter mapped onto an Android host path.
 *
 * @property letter single upper-case drive letter, e.g. 'D'
 * @property hostPath absolute host path mounted as that drive root
 */
@Serializable
data class DriveMapping(
    val letter: Char,
    val hostPath: String,
)

/**
 * Full definition of a Windows container: graphics stack, translation layers,
 * CPU topology, mounted drives and input behaviour.
 */
@Serializable
data class ContainerConfig(
    val id: String,
    val name: String,
    val display: DisplaySpec = DisplaySpec(),
    val graphicsDriver: GraphicsDriver = GraphicsDriver.TURNIP,
    val dxWrapper: DxWrapper = DxWrapper.DXVK,
    val box64Preset: Box64Preset = Box64Preset.COMPATIBILITY,
    val audioDriver: AudioDriver = AudioDriver.PULSEAUDIO,
    val wineVersion: String = "wine-9.2-x86_64",
    val cpuCores: List<Int> = listOf(0, 1, 2, 3),
    val envVars: Map<String, String> = emptyMap(),
    val drives: List<DriveMapping> = listOf(DriveMapping('D', "/sdcard/Download")),
    val showFps: Boolean = true,
    val inputConfig: InputConfig = InputConfig(),
    val startupCommand: String = "explorer.exe /desktop=shell,1280x720",
)
