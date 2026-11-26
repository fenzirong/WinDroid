package com.windroid.app.runtime

import com.windroid.app.core.model.AudioDriver
import com.windroid.app.core.model.Box64Preset
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.DisplaySpec
import com.windroid.app.core.model.DriveMapping
import com.windroid.app.core.model.DxWrapper
import com.windroid.app.core.model.GraphicsDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure JVM tests for the `proot -> box64 -> wine` launch path.
 *
 * Nothing here touches Android: [WineCommandBuilder] only produces an argv list
 * and an environment map, so the whole translation layer can be pinned down in
 * a unit test instead of an emulator round trip.
 */
class WineCommandBuilderTest {

    private val rootFs: File = Files.createTempDirectory("windroid-rootfs").toFile()
    private val tmpDir: File = Files.createTempDirectory("windroid-tmp").toFile()

    @After
    fun tearDown() {
        rootFs.deleteRecursively()
        tmpDir.deleteRecursively()
    }

    private fun newContainer(
        preset: Box64Preset = Box64Preset.COMPATIBILITY,
        driver: GraphicsDriver = GraphicsDriver.TURNIP,
        dx: DxWrapper = DxWrapper.DXVK,
        audio: AudioDriver = AudioDriver.PULSEAUDIO,
        showFps: Boolean = true,
        envVars: Map<String, String> = emptyMap(),
        drives: List<DriveMapping> = listOf(DriveMapping('D', "/sdcard/Download")),
        startupCommand: String = "explorer.exe /desktop=shell,1280x720",
    ): ContainerConfig = ContainerConfig(
        id = "test-container",
        name = "Test Container",
        display = DisplaySpec.PRESET_720P,
        graphicsDriver = driver,
        dxWrapper = dx,
        box64Preset = preset,
        audioDriver = audio,
        envVars = envVars,
        drives = drives,
        showFps = showFps,
        startupCommand = startupCommand,
    )

    private fun specFor(container: ContainerConfig, displayNumber: Int = 0): LaunchSpec =
        WineCommandBuilder.buildLaunchSpec(container, rootFs, tmpDir, displayNumber)

    private fun assertBind(command: List<String>, host: String, guest: String) {
        val expected = if (host == guest) host else "$host:$guest"
        var found = false
        command.forEachIndexed { index, token ->
            if (token == "-b" && command.getOrNull(index + 1) == expected) found = true
        }
        assertTrue(
            "expected bind '$expected' in: ${command.joinToString(" ")}",
            found,
        )
    }

    // ------------------------------------------------------------------
    // Box64 presets
    // ------------------------------------------------------------------

    @Test
    fun `every box64 preset exposes all six dynarec knobs`() {
        val expectedKeys = setOf(
            "BOX64_DYNAREC_SAFEFLAGS",
            "BOX64_DYNAREC_FASTNAN",
            "BOX64_DYNAREC_FASTROUND",
            "BOX64_DYNAREC_X87DOUBLE",
            "BOX64_DYNAREC_BIGBLOCK",
            "BOX64_DYNAREC_STRONGMEM",
        )

        Box64Preset.values().forEach { preset ->
            val env = WineCommandBuilder.box64EnvForPreset(preset)
            assertEquals("key set for $preset", expectedKeys, env.keys)
            env.forEach { (key, value) ->
                assertNotNull("$key for $preset must be numeric", value.toIntOrNull())
            }
        }
    }

    @Test
    fun `presets are all distinct so the selector actually changes behaviour`() {
        val rendered = Box64Preset.values().map { preset ->
            WineCommandBuilder.box64EnvForPreset(preset).toSortedMap().toString()
        }
        assertEquals(rendered.size, rendered.toSet().size)
    }

    @Test
    fun `performance preset trades correctness for speed`() {
        val env = WineCommandBuilder.box64EnvForPreset(Box64Preset.PERFORMANCE)
        assertEquals("0", env["BOX64_DYNAREC_SAFEFLAGS"])
        assertEquals("1", env["BOX64_DYNAREC_FASTNAN"])
        assertEquals("1", env["BOX64_DYNAREC_FASTROUND"])
        assertEquals("3", env["BOX64_DYNAREC_BIGBLOCK"])
        assertEquals("0", env["BOX64_DYNAREC_STRONGMEM"])
    }

    @Test
    fun `stability preset restores ieee semantics`() {
        val env = WineCommandBuilder.box64EnvForPreset(Box64Preset.STABILITY)
        assertEquals("2", env["BOX64_DYNAREC_SAFEFLAGS"])
        assertEquals("0", env["BOX64_DYNAREC_FASTNAN"])
        assertEquals("0", env["BOX64_DYNAREC_FASTROUND"])
        assertEquals("1", env["BOX64_DYNAREC_X87DOUBLE"])
        assertEquals("2", env["BOX64_DYNAREC_STRONGMEM"])
    }

    @Test
    fun `bigblock shrinks monotonically from performance to stability`() {
        val bigBlock = listOf(
            Box64Preset.PERFORMANCE,
            Box64Preset.INTERMEDIATE,
            Box64Preset.COMPATIBILITY,
            Box64Preset.STABILITY,
        ).map {
            WineCommandBuilder.box64EnvForPreset(it).getValue("BOX64_DYNAREC_BIGBLOCK").toInt()
        }

        bigBlock.zipWithNext { faster, safer ->
            assertTrue("bigblock must not grow towards safety: $bigBlock", faster > safer)
        }
    }

    // ------------------------------------------------------------------
    // Graphics stack
    // ------------------------------------------------------------------

    @Test
    fun `turnip driver selects the freedreno vulkan icd over zink`() {
        val env = WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.DXVK, showFps = true)
        assertEquals(WineCommandBuilder.FREEDRENO_ICD, env["VK_ICD_FILENAMES"])
        assertEquals("zink", env["GALLIUM_DRIVER"])
        assertEquals("zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
    }

    @Test
    fun `virgl driver uses the universal virpipe path`() {
        val env = WineCommandBuilder.graphicsEnv(GraphicsDriver.VIRGL, DxWrapper.DXVK, showFps = true)
        assertEquals("virpipe", env["GALLIUM_DRIVER"])
        assertEquals("0", env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("4.5", env["MESA_GL_VERSION_OVERRIDE"])
    }

    @Test
    fun `zink driver routes opengl through vulkan`() {
        val env = WineCommandBuilder.graphicsEnv(GraphicsDriver.ZINK, DxWrapper.DXVK, showFps = true)
        assertEquals("zink", env["GALLIUM_DRIVER"])
        assertEquals(WineCommandBuilder.FREEDRENO_ICD, env["VK_ICD_FILENAMES"])
    }

    @Test
    fun `llvmpipe driver forces software rendering`() {
        val env = WineCommandBuilder.graphicsEnv(GraphicsDriver.LLVMPIPE, DxWrapper.WINED3D, showFps = false)
        assertEquals("llvmpipe", env["GALLIUM_DRIVER"])
        assertEquals("1", env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals(WineCommandBuilder.LAVAPIPE_ICD, env["VK_ICD_FILENAMES"])
    }

    @Test
    fun `fps hud follows the container flag`() {
        assertEquals(
            "fps",
            WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.DXVK, showFps = true)["DXVK_HUD"],
        )
        assertEquals(
            "0",
            WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.DXVK, showFps = false)["DXVK_HUD"],
        )
        assertNull(
            WineCommandBuilder.graphicsEnv(
                GraphicsDriver.TURNIP,
                DxWrapper.WINED3D,
                showFps = true,
            )["DXVK_HUD"],
        )
    }

    @Test
    fun `dx wrapper decides the dll overrides`() {
        val dxvk = WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.DXVK, showFps = true)
        assertEquals(WineCommandBuilder.DXVK_OVERRIDES, dxvk["WINEDLLOVERRIDES"])

        val vkd3d = WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.VKD3D, showFps = true)
        assertTrue(vkd3d.getValue("WINEDLLOVERRIDES").contains("d3d12"))

        val wineD3d = WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.WINED3D, showFps = true)
        assertEquals(WineCommandBuilder.WINED3D_OVERRIDES, wineD3d["WINEDLLOVERRIDES"])

        val cnc = WineCommandBuilder.graphicsEnv(GraphicsDriver.TURNIP, DxWrapper.CNC_DDRAW, showFps = true)
        assertTrue(cnc.getValue("WINEDLLOVERRIDES").contains("ddraw"))
    }

    // ------------------------------------------------------------------
    // Launch command
    // ------------------------------------------------------------------

    @Test
    fun `command re-roots through proot and binds pseudo filesystems`() {
        val spec = specFor(newContainer())

        assertEquals("proot", spec.command.first())
        assertTrue(spec.command.contains("--link2symlink"))
        assertTrue(spec.command.contains("-0"))

        val rootIndex = spec.command.indexOf("-r")
        assertTrue("proot needs -r", rootIndex >= 0)
        assertEquals(rootFs.absolutePath, spec.command[rootIndex + 1])

        assertBind(spec.command, "/dev", "/dev")
        assertBind(spec.command, "/proc", "/proc")
        assertBind(spec.command, tmpDir.absolutePath, "/tmp")
    }

    @Test
    fun `command runs box64 hosting wine with the startup arguments`() {
        val spec = specFor(newContainer())
        val box64 = File(rootFs, ImageFsManager.RELATIVE_BOX64).absolutePath
        val wine = File(rootFs, ImageFsManager.RELATIVE_WINE).absolutePath

        val boxIndex = spec.command.indexOf(box64)
        val wineIndex = spec.command.indexOf(wine)

        assertTrue("box64 must be launched", boxIndex > 0)
        assertEquals("wine must follow box64 directly", boxIndex + 1, wineIndex)
        assertEquals(
            listOf("explorer.exe", "/desktop=shell,1280x720"),
            spec.command.subList(wineIndex + 1, spec.command.size),
        )
    }

    @Test
    fun `every drive mapping becomes a guest bind`() {
        val spec = specFor(
            newContainer(
                drives = listOf(
                    DriveMapping('D', "/sdcard/Download"),
                    DriveMapping('E', "/sdcard/Documents"),
                    DriveMapping('Z', "/storage/emulated/0"),
                ),
            ),
        )

        assertBind(spec.command, "/sdcard/Download", "/mnt/drives/D")
        assertBind(spec.command, "/sdcard/Documents", "/mnt/drives/E")
        assertBind(spec.command, "/storage/emulated/0", "/mnt/drives/Z")
    }

    @Test
    fun `drive letters are upper-cased for the guest mount point`() {
        val spec = specFor(newContainer(drives = listOf(DriveMapping('g', "/sdcard/Games"))))
        assertBind(spec.command, "/sdcard/Games", "/mnt/drives/G")
    }

    @Test
    fun `startup command is tokenised with quote support`() {
        val spec = specFor(
            newContainer(startupCommand = "\"C:\\GOG Games\\GK3\\GK3.exe\" -nointro --safe"),
        )

        assertTrue(spec.command.contains("C:\\GOG Games\\GK3\\GK3.exe"))
        assertTrue(spec.command.contains("-nointro"))
        assertTrue(spec.command.contains("--safe"))
        assertEquals(3, WineCommandBuilder.tokenize("\"a b\" c\td").size)
    }

    @Test
    fun `working dir is the guest home inside the rootfs`() {
        val spec = specFor(newContainer())
        assertEquals(File(rootFs, "home/windroid"), spec.workingDir)
        val workingIndex = spec.command.indexOf("-w")
        assertTrue(workingIndex >= 0)
        assertEquals(spec.workingDir.absolutePath, spec.command[workingIndex + 1])
    }

    // ------------------------------------------------------------------
    // Launch environment
    // ------------------------------------------------------------------

    @Test
    fun `environment wires display prefix esync and pulse`() {
        val spec = specFor(newContainer(), displayNumber = 3)

        assertEquals(":3", spec.environment["DISPLAY"])
        assertEquals("1", spec.environment["WINEESYNC"])
        assertEquals(File(rootFs, "opt/prefix").absolutePath, spec.environment["WINEPREFIX"])
        assertEquals(File(rootFs, "home/windroid").absolutePath, spec.environment["HOME"])
        assertEquals(WineCommandBuilder.DEFAULT_PULSE_SERVER, spec.environment["PULSE_SERVER"])
        assertTrue(spec.environment.getValue("PATH").contains("/opt/wine/bin"))
        assertEquals("win64", spec.environment["WINEARCH"])
    }

    @Test
    fun `environment carries the box64 preset tuning`() {
        val spec = specFor(newContainer(preset = Box64Preset.PERFORMANCE))
        assertEquals("3", spec.environment["BOX64_DYNAREC_BIGBLOCK"])
        assertEquals("0", spec.environment["BOX64_DYNAREC_SAFEFLAGS"])
    }

    @Test
    fun `environment carries the graphics stack`() {
        val spec = specFor(newContainer(driver = GraphicsDriver.LLVMPIPE, dx = DxWrapper.WINED3D))
        assertEquals("llvmpipe", spec.environment["GALLIUM_DRIVER"])
        assertEquals(WineCommandBuilder.WINED3D_OVERRIDES, spec.environment["WINEDLLOVERRIDES"])
        assertEquals(
            File(tmpDir, "dxvk-cache").absolutePath,
            spec.environment["DXVK_STATE_CACHE_PATH"],
        )
    }

    @Test
    fun `user env vars override generated defaults`() {
        val spec = specFor(
            newContainer(
                envVars = mapOf(
                    "DXVK_HUD" to "full",
                    "PULSE_SERVER" to "unix:/tmp/pulse-socket",
                    "WINEDROID_CUSTOM" to "1",
                ),
            ),
        )

        assertEquals("full", spec.environment["DXVK_HUD"])
        assertEquals("unix:/tmp/pulse-socket", spec.environment["PULSE_SERVER"])
        assertEquals("1", spec.environment["WINEDROID_CUSTOM"])
    }

    @Test
    fun `pulse server only exists for the pulseaudio driver`() {
        val pulse = specFor(newContainer(audio = AudioDriver.PULSEAUDIO))
        assertEquals(WineCommandBuilder.DEFAULT_PULSE_SERVER, pulse.environment["PULSE_SERVER"])

        val alsa = specFor(newContainer(audio = AudioDriver.ALSA))
        assertNull(alsa.environment["PULSE_SERVER"])
        assertEquals("alsa", alsa.environment["WINEDROID_AUDIO_DRIVER"])

        val disabled = specFor(newContainer(audio = AudioDriver.DISABLED))
        assertEquals("none", disabled.environment["WINEDROID_AUDIO_DRIVER"])
    }

    @Test
    fun `cpu core selection is exported for the launcher`() {
        val spec = specFor(newContainer())
        assertEquals("0,1,2,3", spec.environment["WINEDROID_CPU_CORES"])
        assertFalse(spec.environment.getValue("WINEDROID_CPU_CORES").contains(" "))
    }

    @Test
    fun `display number is honoured for arbitrary displays`() {
        assertEquals(":0", specFor(newContainer(), 0).environment["DISPLAY"])
        assertEquals(":1", specFor(newContainer(), 1).environment["DISPLAY"])
        assertEquals(":9", specFor(newContainer(), 9).environment["DISPLAY"])
    }

    @Test
    fun `drive mount point helper normalises the letter`() {
        assertEquals("/mnt/drives/D", WineCommandBuilder.driveMountPoint(DriveMapping('d', "/x")))
        assertEquals("/mnt/drives/Z", WineCommandBuilder.driveMountPoint(DriveMapping('Z', "/y")))
    }
}
