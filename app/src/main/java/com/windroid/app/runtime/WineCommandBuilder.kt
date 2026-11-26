package com.windroid.app.runtime

import com.windroid.app.core.model.AudioDriver
import com.windroid.app.core.model.Box64Preset
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.DriveMapping
import com.windroid.app.core.model.DxWrapper
import com.windroid.app.core.model.GraphicsDriver
import java.io.File

/**
 * Everything needed to spawn a container: the exact argv handed to
 * [ProcessBuilder], the environment it runs with, and the working directory.
 *
 * Kept as a plain data class (no Android types) so the whole launch path can be
 * asserted from a JVM unit test without an emulator.
 *
 * @property command argv; `command[0]` is always the proot binary
 * @property environment complete environment, already merged and de-duplicated
 * @property workingDir cwd for the process — the guest home inside the rootfs
 */
data class LaunchSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDir: File,
)

/**
 * Translates a [ContainerConfig] into a Winlator-style
 * `proot -> box64 -> wine` invocation.
 *
 * The layering mirrors what actually happens on the device:
 *
 * 1. **proot** fakes `chroot`/`root` without root: it re-roots the filesystem at
 *    the imagefs and bind-mounts `/dev`, `/proc`, the app's tmp dir and every
 *    user drive onto it.
 * 2. **box64** dynarecs x86_64 guest code onto the arm64 host. Its behaviour is
 *    entirely env-driven (`BOX64_DYNAREC_*`), hence [box64EnvForPreset].
 * 3. **wine** runs the actual Windows payload, with the Direct3D translation
 *    layer chosen by [graphicsEnv].
 */
object WineCommandBuilder {

    // --- Graphics stack constants ----------------------------------------

    /** Mesa Vulkan ICD for Adreno / Turnip (`freedreno`). */
    const val FREEDRENO_ICD: String =
        "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"

    /** Mesa Vulkan ICD for the software (lavapipe) fallback. */
    const val LAVAPIPE_ICD: String =
        "/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"

    /** VirGL's Vulkan ICD, used by the universal OpenGL path. */
    const val VIRTIO_ICD: String =
        "/usr/share/vulkan/icd.d/virtio_icd.aarch64.json"

    /** DLL overrides that force Wine to load DXVK's Vulkan-backed implementations. */
    const val DXVK_OVERRIDES: String = "d3d9,d3d10core,d3d11,dxgi=n,b"

    /** DLL overrides that force VKD3D-Proton for D3D12. */
    const val VKD3D_OVERRIDES: String = "d3d12,d3d12core=n,b;d3d9,d3d10core,d3d11,dxgi=n,b"

    /** DLL overrides that pin Wine's own OpenGL implementation (no Vulkan). */
    const val WINED3D_OVERRIDES: String = "d3d9,d3d10core,d3d11,d3d12,dxgi=b"

    /** DLL overrides for the old DirectDraw path used by CNC-era titles. */
    const val CNC_DDRAW_OVERRIDES: String = "ddraw=n,b;d3d8,d3d9=n,b;d3dimm=n,b"

    /** PulseAudio endpoint exported from the host audio bridge. */
    const val DEFAULT_PULSE_SERVER: String = "tcp:127.0.0.1:4713"

    // --- Box64 -----------------------------------------------------------

    /**
     * Maps a user-facing [Box64Preset] onto the `BOX64_DYNAREC_*` env block.
     *
     * The values that matter for correctness are the ones trading IEEE
     * compliance for speed:
     *
     * * `SAFEFLAGS` — 0 skips x86 flag emulation on memory access; fastest, but
     *   breaks software that probes page protection.
     * * `FASTNAN` — forward NaNs instead of the x86 quiet-NaN payload; fast, and
     *   subtly wrong for code comparing NaN bit patterns.
     * * `FASTROUND` — skip x87 rounding-mode changes.
     * * `X87DOUBLE` — run x87 in double precision; slow, but classic installers
     *   and VB6-era software expect it.
     * * `BIGBLOCK` — dynarec block size (0 = off, 3 = aggressive).
     * * `STRONGMEM` — memory-model fences (0 = none, 2 = full).
     */
    fun box64EnvForPreset(preset: Box64Preset): Map<String, String> {
        val tuning = when (preset) {
            Box64Preset.PERFORMANCE -> Box64Tuning(
                safeFlags = 0,
                fastNan = 1,
                fastRound = 1,
                x87Double = 0,
                bigBlock = 3,
                strongMem = 0,
            )

            Box64Preset.INTERMEDIATE -> Box64Tuning(
                safeFlags = 1,
                fastNan = 1,
                fastRound = 1,
                x87Double = 0,
                bigBlock = 2,
                strongMem = 1,
            )

            Box64Preset.COMPATIBILITY -> Box64Tuning(
                safeFlags = 2,
                fastNan = 0,
                fastRound = 1,
                x87Double = 0,
                bigBlock = 1,
                strongMem = 1,
            )

            Box64Preset.STABILITY -> Box64Tuning(
                safeFlags = 2,
                fastNan = 0,
                fastRound = 0,
                x87Double = 1,
                bigBlock = 0,
                strongMem = 2,
            )
        }
        return tuning.toEnv()
    }

    // --- Graphics --------------------------------------------------------

    /**
     * Builds the graphics environment block for [driver] + [dxWrapper].
     *
     * The two halves are independent on purpose: the *driver* decides which GL /
     * Vulkan implementation Mesa exposes to the guest, the *wrapper* decides which
     * Windows DLL gets overridden and therefore which of those APIs Wine reaches
     * for. `showFps` only means something when a Vulkan layer is in play, so the
     * HUD variable is emitted for DXVK / VKD3D and omitted for the pure-GL paths.
     */
    fun graphicsEnv(
        driver: GraphicsDriver,
        dxWrapper: DxWrapper,
        showFps: Boolean,
    ): Map<String, String> {
        val env = linkedMapOf<String, String>()

        // Harmless everywhere; stops Mesa aborting on shader warnings.
        env["MESA_NO_ERROR"] = "1"
        env["mesa_glthread"] = "true"

        when (driver) {
            GraphicsDriver.TURNIP -> {
                // Adreno: Vulkan straight through freedreno, GL on top via zink.
                env["VK_ICD_FILENAMES"] = FREEDRENO_ICD
                env["GALLIUM_DRIVER"] = "zink"
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["TU_DEBUG"] = "noconform"
                env["LIBGL_ALWAYS_SOFTWARE"] = "0"
            }

            GraphicsDriver.VIRGL -> {
                // Universal path: guest GL is forwarded to the host GPU by virglrenderer.
                env["GALLIUM_DRIVER"] = "virpipe"
                env["VIRGL_NO_READBACK"] = "1"
                env["MESA_GL_VERSION_OVERRIDE"] = "4.5"
                env["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                env["LIBGL_ALWAYS_SOFTWARE"] = "0"
            }

            GraphicsDriver.ZINK -> {
                // OpenGL implemented on top of Vulkan; needs a Vulkan ICD.
                env["GALLIUM_DRIVER"] = "zink"
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["VK_ICD_FILENAMES"] = FREEDRENO_ICD
                env["ZINK_DESCRIPTORS"] = "lazy"
                env["LIBGL_ALWAYS_SOFTWARE"] = "0"
            }

            GraphicsDriver.LLVMPIPE -> {
                // CPU rasteriser: the always-works fallback, quite slow.
                env["GALLIUM_DRIVER"] = "llvmpipe"
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
                env["VK_ICD_FILENAMES"] = LAVAPIPE_ICD
                env["LP_NUM_THREADS"] = "4"
            }
        }

        when (dxWrapper) {
            DxWrapper.DXVK -> {
                env["WINEDLLOVERRIDES"] = DXVK_OVERRIDES
                env["DXVK_HUD"] = if (showFps) "fps" else "0"
                env["DXVK_ASYNC"] = "1"
            }

            DxWrapper.VKD3D -> {
                env["WINEDLLOVERRIDES"] = VKD3D_OVERRIDES
                env["VKD3D_FEATURE_LEVEL"] = "12_1"
                // Shader cache lives under tmpDir; the caller adds that path.
                env["VKD3D_SHADER_CACHE"] = "1"
                env["DXVK_HUD"] = if (showFps) "fps" else "0"
            }

            DxWrapper.WINED3D -> {
                env["WINEDLLOVERRIDES"] = WINED3D_OVERRIDES
            }

            DxWrapper.CNC_DDRAW -> {
                env["WINEDLLOVERRIDES"] = CNC_DDRAW_OVERRIDES
                env["CNC_DDRAW_FORCE_PALETTE"] = "1"
            }
        }

        return env
    }

    /** Audio environment block for [driver]. */
    fun audioEnv(driver: AudioDriver): Map<String, String> = when (driver) {
        AudioDriver.PULSEAUDIO -> mapOf(
            // The host-side bridge listens on loopback; the guest reaches it via proot.
            "PULSE_SERVER" to DEFAULT_PULSE_SERVER,
            "PULSE_LATENCY_MSEC" to "60",
        )

        AudioDriver.ALSA -> mapOf(
            // Direct ALSA through Wine's winealsa; no pulse daemon involved.
            "WINEDROID_AUDIO_DRIVER" to "alsa",
            "ALSA_CONFIG_PATH" to "/usr/share/alsa/alsa.conf",
        )

        AudioDriver.DISABLED -> mapOf(
            "WINEDROID_AUDIO_DRIVER" to "none",
            // Wine skips loading the audio drivers entirely.
            "WINEDLLOVERRIDES_AUDIO" to "winealsa.drv,winepulse.drv=d",
        )
    }

    // --- Launch spec -----------------------------------------------------

    /**
     * Assembles the full launch description for [container].
     *
     * @param rootFsDir extracted imagefs root, re-rooted by proot
     * @param tmpDir host scratch dir, bind-mounted at `/tmp` (Wine needs a
     *   writable tmp for its sockets, and Android's real `/tmp` is not usable)
     * @param displayNumber X11 display index matching the running
     *   `XServerSocketHost`, i.e. `DISPLAY=:$displayNumber`
     */
    fun buildLaunchSpec(
        container: ContainerConfig,
        rootFsDir: File,
        tmpDir: File,
        displayNumber: Int = 0,
    ): LaunchSpec {
        val homeDir = File(rootFsDir, HOME_RELATIVE)

        val command = mutableListOf<String>()
        command += "proot"
        command += PROOT_FLAGS
        command += listOf("-r", rootFsDir.absolutePath)
        command += listOf("-w", homeDir.absolutePath)

        // Standard pseudo-filesystems the guest expects to find mounted.
        command += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")

        // Android has no usable /tmp; give the guest our private scratch dir.
        command += listOf("-b", "${tmpDir.absolutePath}:/tmp")

        // Every user drive becomes a plain directory the guest can see; Wine's
        // dosdevices symlinks (created with the prefix) point at these paths.
        container.drives.forEach { drive ->
            command += listOf("-b", "${drive.hostPath}:${driveMountPoint(drive)}")
        }

        // The payload: box64 (x86_64 -> arm64) hosting wine.
        command += File(rootFsDir, ImageFsManager.RELATIVE_BOX64).absolutePath
        command += File(rootFsDir, ImageFsManager.RELATIVE_WINE).absolutePath
        command += tokenize(container.startupCommand)

        val environment = linkedMapOf<String, String>()

        // --- display / wine base ---
        environment["DISPLAY"] = ":$displayNumber"
        environment["WINEPREFIX"] = File(rootFsDir, PREFIX_RELATIVE).absolutePath
        environment["HOME"] = homeDir.absolutePath
        environment["TMPDIR"] = "/tmp"
        environment["XDG_RUNTIME_DIR"] = "/tmp"
        environment["PATH"] = "/opt/wine/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        environment["LD_LIBRARY_PATH"] = "/opt/wine/lib:/opt/wine/lib/wine/x86_64-unix:" +
            "/usr/lib/aarch64-linux-gnu:/usr/lib"
        environment["WINEARCH"] = "win64"
        environment["WINEESYNC"] = "1"
        environment["WINEFSYNC"] = "1"
        environment["WINEDEBUG"] = "-all"

        // --- audio ---
        environment.putAll(audioEnv(container.audioDriver))

        // --- CPU translation ---
        environment.putAll(box64EnvForPreset(container.box64Preset))
        environment["BOX64_LOG"] = "0"
        environment["BOX64_NOBANNER"] = "1"
        environment["BOX64_MMAP32"] = "1"
        if (container.cpuCores.isNotEmpty()) {
            environment["WINEDROID_CPU_CORES"] = container.cpuCores.joinToString(",")
        }

        // --- graphics ---
        environment.putAll(
            graphicsEnv(container.graphicsDriver, container.dxWrapper, container.showFps),
        )
        environment["DXVK_STATE_CACHE_PATH"] = File(tmpDir, "dxvk-cache").absolutePath
        environment["MESA_SHADER_CACHE_DIR"] = File(tmpDir, "mesa-cache").absolutePath

        // --- user overrides win, so a power user can always unbreak a config ---
        environment.putAll(container.envVars)

        return LaunchSpec(
            command = command,
            environment = environment,
            workingDir = homeDir,
        )
    }

    /** Guest-visible mount point for [drive], e.g. `/mnt/drives/D`. */
    fun driveMountPoint(drive: DriveMapping): String =
        "/mnt/drives/${drive.letter.uppercaseChar()}"

    /**
     * Splits a Windows-style command line into argv, honouring double quotes so
     * `wine "C:\\Program Files\\App\\app.exe"` stays one token.
     */
    fun tokenize(commandLine: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var sawToken = false

        commandLine.forEach { ch ->
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sawToken = true
                }

                ch.isWhitespace() && !inQuotes -> {
                    if (sawToken || current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                        sawToken = false
                    }
                }

                else -> {
                    current.append(ch)
                    sawToken = true
                }
            }
        }
        if (sawToken || current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    // --- Internals -------------------------------------------------------

    /** Host home directory inside the rootfs; also the proot `-w` target. */
    private const val HOME_RELATIVE = "home/windroid"

    /** Wine prefix inside the rootfs (mounted as `C:`). */
    private const val PREFIX_RELATIVE = "opt/prefix"

    /**
     * proot flags shared by every launch.
     *
     * * `--link2symlink` — turn hardlinks into symlinks; Android's sdcardfs
     *   refuses guest hardlinks and Wine creates plenty during prefix setup.
     * * `--sysvipc` — emulate SysV IPC, which Wine's wineserver needs.
     * * `--kill-on-exit` — tear the whole guest down with the parent process.
     * * `-0` — present as uid/gid 0 inside the rootfs.
     */
    private val PROOT_FLAGS = listOf("--link2symlink", "--sysvipc", "--kill-on-exit", "-0")

    private data class Box64Tuning(
        val safeFlags: Int,
        val fastNan: Int,
        val fastRound: Int,
        val x87Double: Int,
        val bigBlock: Int,
        val strongMem: Int,
    ) {
        fun toEnv(): Map<String, String> = linkedMapOf(
            "BOX64_DYNAREC_SAFEFLAGS" to safeFlags.toString(),
            "BOX64_DYNAREC_FASTNAN" to fastNan.toString(),
            "BOX64_DYNAREC_FASTROUND" to fastRound.toString(),
            "BOX64_DYNAREC_X87DOUBLE" to x87Double.toString(),
            "BOX64_DYNAREC_BIGBLOCK" to bigBlock.toString(),
            "BOX64_DYNAREC_STRONGMEM" to strongMem.toString(),
        )
    }
}
