package com.windroid.app.runtime

import com.windroid.app.core.model.AudioDriver
import com.windroid.app.core.model.Box64Preset
import com.windroid.app.core.model.ContainerConfig
import com.windroid.app.core.model.DisplaySpec
import com.windroid.app.core.model.DriveMapping
import com.windroid.app.core.model.DxWrapper
import com.windroid.app.core.model.GraphicsDriver
import com.windroid.app.core.model.InputConfig
import com.windroid.app.core.model.MouseMode
import com.windroid.app.core.model.RightClickBehavior
import com.windroid.app.data.ContainerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence tests for [ContainerRepository].
 *
 * Where persistence matters every assertion goes through a *second* repository
 * instance, so a test can never pass on the in-memory StateFlow alone — the JSON
 * file has to actually round-trip.
 */
class ContainerRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var storageFile: File

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("app-files")
        storageFile = File(storageDir, ContainerRepository.STORAGE_FILE_NAME)
    }

    /** A container that touches every persisted field, including nested config. */
    private fun fullyPopulatedContainer(): ContainerConfig = ContainerConfig(
        id = "custom-1",
        name = "Gabriel Knight 3",
        display = DisplaySpec(width = 800, height = 600, dpi = 120, refreshRate = 75),
        graphicsDriver = GraphicsDriver.ZINK,
        dxWrapper = DxWrapper.CNC_DDRAW,
        box64Preset = Box64Preset.PERFORMANCE,
        audioDriver = AudioDriver.DISABLED,
        wineVersion = "wine-9.0-x86_64",
        cpuCores = listOf(4, 5, 6, 7),
        envVars = mapOf("DXVK_HUD" to "full", "WINEDEBUG" to "warn+all"),
        drives = listOf(
            DriveMapping('D', "/sdcard/Games/GK3"),
            DriveMapping('Z', "/storage/emulated/0"),
        ),
        showFps = false,
        inputConfig = InputConfig(
            mouseMode = MouseMode.CAPTURED_RELATIVE,
            mouseSensitivity = 1.4f,
            scrollSpeed = 0.75f,
            rightClickBehavior = RightClickBehavior.ANDROID_BACK,
            capturePointerOnClick = true,
        ),
        startupCommand = "\"C:\\GOG Games\\GK3\\GK3.exe\" -nointro",
    )

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    @Test
    fun `load seeds two ready to use containers on first launch`() {
        val repo = ContainerRepository(storageFile)
        repo.load()

        val names = repo.containers.value.map { it.name }
        assertEquals(2, names.size)
        assertEquals("Windows Desktop (720p)", names[0])
        assertEquals("Classic x86 Productivity (1024x768)", names[1])

        val desktop = repo.containers.value[0]
        assertEquals(1280, desktop.display.width)
        assertEquals(720, desktop.display.height)
        assertEquals(GraphicsDriver.TURNIP, desktop.graphicsDriver)
        assertEquals(DxWrapper.DXVK, desktop.dxWrapper)
        assertTrue(desktop.drives.isNotEmpty())

        val productivity = repo.containers.value[1]
        assertEquals(1024, productivity.display.width)
        assertEquals(768, productivity.display.height)
    }

    @Test
    fun `seeds are written to disk immediately`() {
        ContainerRepository(storageFile).load()

        assertTrue("storage file must exist after load()", storageFile.isFile)
        val raw = storageFile.readText()
        assertTrue(raw.contains("Windows Desktop (720p)"))
        assertTrue(raw.contains("Classic x86 Productivity (1024x768)"))
        // encodeDefaults = true keeps the file fully hydrated for hand editing.
        assertTrue(raw.contains("\"graphicsDriver\""))
        assertTrue(raw.contains("\"startupCommand\""))
    }

    @Test
    fun `an empty stored array is re-seeded`() {
        storageFile.writeText("[]")

        val repo = ContainerRepository(storageFile)
        repo.load()

        assertEquals(2, repo.containers.value.size)
    }

    @Test
    fun `a blank stored file is re-seeded`() {
        storageFile.writeText("")

        val repo = ContainerRepository(storageFile)
        repo.load()

        assertEquals(2, repo.containers.value.size)
    }

    @Test
    fun `unknown json keys from newer builds are ignored`() {
        storageFile.writeText(
            """[{"id":"legacy","name":"Legacy Container","futureField":{"nested":1}}]""",
        )

        val repo = ContainerRepository(storageFile)
        repo.load()

        assertEquals(1, repo.containers.value.size)
        assertEquals("Legacy Container", repo.getById("legacy")?.name)
    }

    // ------------------------------------------------------------------
    // JSON round trip
    // ------------------------------------------------------------------

    @Test
    fun `save persists every field and survives a fresh repository`() {
        val repo = ContainerRepository(storageFile)
        repo.load()
        val custom = fullyPopulatedContainer()
        repo.save(custom)

        val reopened = ContainerRepository(storageFile)
        reopened.load()

        val restored = requireNotNull(reopened.getById("custom-1")) {
            "custom container must survive a restart"
        }
        assertEquals(custom, restored)
        assertEquals(custom.display, restored.display)
        assertEquals(custom.drives, restored.drives)
        assertEquals(custom.envVars, restored.envVars)
        assertEquals(custom.cpuCores, restored.cpuCores)
        assertEquals(custom.inputConfig, restored.inputConfig)
        assertEquals(custom.startupCommand, restored.startupCommand)
        assertEquals(3, reopened.containers.value.size)
    }

    @Test
    fun `round trip keeps drive letters and nested input config intact`() {
        val repo = ContainerRepository(storageFile)
        repo.load()
        repo.save(fullyPopulatedContainer())

        val reopened = ContainerRepository(storageFile)
        reopened.load()
        val restored = reopened.getById("custom-1")!!

        assertEquals('D', restored.drives[0].letter)
        assertEquals("/sdcard/Games/GK3", restored.drives[0].hostPath)
        assertEquals(MouseMode.CAPTURED_RELATIVE, restored.inputConfig.mouseMode)
        assertEquals(RightClickBehavior.ANDROID_BACK, restored.inputConfig.rightClickBehavior)
        assertEquals(1.4f, restored.inputConfig.mouseSensitivity, 0.0001f)
    }

    @Test
    fun `saved file stays valid pretty printed json`() {
        val repo = ContainerRepository(storageFile)
        repo.load()
        repo.save(fullyPopulatedContainer())

        val raw = storageFile.readText()
        assertTrue("prettyPrint must add newlines", raw.contains("\n"))
        assertTrue(raw.trimStart().startsWith("["))
        assertTrue(raw.trimEnd().endsWith("]"))
        // The atomic-write temp file must not be left behind.
        assertFalse(File(storageDir, "${storageFile.name}.tmp").exists())
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    @Test
    fun `save upserts by id instead of duplicating`() {
        val repo = ContainerRepository(storageFile)
        repo.load()
        val existing = repo.containers.value.first()

        repo.save(existing.copy(name = "Renamed Desktop"))

        assertEquals(2, repo.containers.value.size)
        assertEquals("Renamed Desktop", repo.getById(existing.id)?.name)
    }

    @Test
    fun `delete removes the container and persists the removal`() {
        val repo = ContainerRepository(storageFile)
        repo.load()
        val victim = repo.containers.value.first().id

        repo.delete(victim)

        assertNull(repo.getById(victim))
        assertEquals(1, repo.containers.value.size)

        val reopened = ContainerRepository(storageFile)
        reopened.load()
        assertNull(reopened.getById(victim))
        assertEquals(1, reopened.containers.value.size)
    }

    @Test
    fun `deleting an unknown id is a no op`() {
        val repo = ContainerRepository(storageFile)
        repo.load()

        repo.delete("does-not-exist")

        assertEquals(2, repo.containers.value.size)
    }

    @Test
    fun `getById returns null for unknown ids`() {
        val repo = ContainerRepository(storageFile)
        repo.load()

        assertNull(repo.getById("missing"))
        assertNotNull(repo.getById(ContainerRepository.DEFAULT_720P_ID))
        assertNotNull(repo.getById(ContainerRepository.DEFAULT_XGA_ID))
    }

    @Test
    fun `createDefaultContainer appends a unique persisted container`() {
        val repo = ContainerRepository(storageFile)
        repo.load()

        val created = repo.createDefaultContainer("Counter-Strike 1.6")
        assertEquals("Counter-Strike 1.6", created.name)
        assertEquals(3, repo.containers.value.size)
        assertEquals(created, repo.getById(created.id))

        val second = repo.createDefaultContainer()
        assertEquals("New Windows Container", second.name)
        assertNotEquals(created.id, second.id)
        assertEquals(4, repo.containers.value.size)

        val reopened = ContainerRepository(storageFile)
        reopened.load()
        assertEquals(4, reopened.containers.value.size)
        assertNotNull(reopened.getById(created.id))
    }

    @Test
    fun `load is idempotent and keeps the stored order`() {
        val repo = ContainerRepository(storageFile)
        repo.load()
        val first = repo.containers.value.map { it.id }

        repo.load()
        repo.load()

        assertEquals(first, repo.containers.value.map { it.id })
        assertEquals(2, repo.containers.value.size)
    }

    @Test
    fun `repository creates missing parent directories`() {
        val nested = File(storageDir, "deep/nested/dir/${ContainerRepository.STORAGE_FILE_NAME}")
        val repo = ContainerRepository(nested)

        repo.load()
        repo.save(fullyPopulatedContainer())

        assertTrue(nested.isFile)
        assertFalse(nested.readText().isBlank())
    }
}
