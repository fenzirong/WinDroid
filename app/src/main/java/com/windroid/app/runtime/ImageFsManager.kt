package com.windroid.app.runtime

import com.windroid.app.core.model.InstallProgress
import com.windroid.app.core.model.RuntimeComponentStatus
import com.windroid.app.core.model.RuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Owns the on-device `imagefs` (an Android-friendly, unprivileged Linux rootfs)
 * and the scratch `tmp` directory that gets bind-mounted into the proot guest.
 *
 * Layout — deliberately identical to Winlator's, so upstream bundles drop
 * straight in without repacking:
 *
 * ```
 * filesDir/imagefs/                 <- the Linux rootfs overlay
 *   usr/bin/proot                   <- ptrace-based syscall translator
 *   usr/local/bin/box64             <- x86_64 -> arm64 dynarec
 *   opt/wine/bin/wine               <- Wine loader
 *   opt/prefix/                     <- WINEPREFIX (drive_c, dosdevices, ...)
 * filesDir/tmp/                     <- bound to /tmp inside the guest
 * ```
 *
 * Nothing here touches Android framework classes on purpose: the whole layer is
 * plain `java.io` + coroutines, which keeps it unit-testable on the JVM.
 */
class ImageFsManager(private val filesDir: File) {

    /** Linux rootfs root extracted from an imported bundle. */
    val rootFsDir: File = File(filesDir, "imagefs")

    /** Scratch directory bind-mounted as `/tmp` inside the container. */
    val tmpDir: File = File(filesDir, "tmp")

    /** Wine prefix created on first launch (i.e. the `C:` drive). */
    val winePrefixDir: File = File(rootFsDir, "opt/prefix")

    /** Absolute path of the proot binary inside the rootfs. */
    val prootFile: File = File(rootFsDir, RELATIVE_PROOT)

    /** Absolute path of the Box64 dynarec binary inside the rootfs. */
    val box64File: File = File(rootFsDir, RELATIVE_BOX64)

    /** Absolute path of the Wine loader inside the rootfs. */
    val wineFile: File = File(rootFsDir, RELATIVE_WINE)

    /**
     * Probes the four runtime components and reports per-component detail.
     *
     * The executable bit is checked in addition to existence, because Android's
     * unzip paths routinely drop unix mode bits and produce a rootfs whose
     * binaries exist but cannot be spawned — that failure mode deserves its own
     * diagnostic string instead of being reported as "installed".
     */
    fun inspectRuntime(): RuntimeStatus {
        val imageFsInstalled = rootFsDir.isDirectory && (rootFsDir.list()?.isNotEmpty() == true)

        val proot = component("proot", prootFile)
        val box64 = component("box64", box64File)
        val wine = component("wine", wineFile)

        val imageFsComponent = RuntimeComponentStatus(
            name = "imagefs",
            installed = imageFsInstalled,
            path = rootFsDir.absolutePath,
            detail = when {
                !rootFsDir.exists() -> "rootfs missing — import an imagefs bundle"
                !rootFsDir.isDirectory -> "path exists but is not a directory"
                !imageFsInstalled -> "rootfs directory is empty"
                else -> "rootfs present (${rootFsDir.list()?.size ?: 0} entries)"
            },
        )

        return RuntimeStatus(
            imageFsInstalled = imageFsInstalled,
            prootInstalled = proot.installed,
            box64Installed = box64.installed,
            wineInstalled = wine.installed,
            components = listOf(imageFsComponent, proot, box64, wine),
        )
    }

    /**
     * Imports a container bundle into [rootFsDir].
     *
     * * `.zip` — streamed out safely with Zip-Slip protection, progress events
     *   per chunk, and `bin/*` binaries marked executable afterwards.
     * * `.tar.xz` / `.txz` — explained instead of attempted: the APK ships no
     *   native liblzma, so the flow returns one actionable message telling the
     *   user how to repack (or fetch the `.zip` bundle) rather than crashing.
     *
     * The returned flow is cold and runs on [Dispatchers.IO]; collect it from a
     * coroutine tied to the screen that shows progress.
     */
    fun importArchive(inputStream: InputStream, fileName: String): Flow<InstallProgress> = flow {
        val lower = fileName.lowercase()
        when {
            lower.endsWith(".zip") -> emitAll(extractZip(inputStream))
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> emit(tarXzGuidance(fileName))
            else -> emit(
                InstallProgress(
                    stage = "unsupported",
                    fraction = 0f,
                    message = "Unsupported archive '$fileName'. Import a '.zip' imagefs bundle " +
                        "(same layout Winlator uses: usr/, opt/wine/, usr/local/bin/box64).",
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** Deletes the extracted rootfs and the scratch directory. */
    fun clearRuntime() {
        rootFsDir.deleteRecursively()
        tmpDir.deleteRecursively()
    }

    // ---------------------------------------------------------------------
    // Import internals
    // ---------------------------------------------------------------------

    private fun component(name: String, file: File): RuntimeComponentStatus {
        val exists = file.isFile
        val executable = exists && file.canExecute()
        val detail = when {
            !exists -> "missing at ${file.absolutePath}"
            !executable -> "present but not executable — re-import or chmod +x"
            else -> "ready (${file.length()} bytes)"
        }
        return RuntimeComponentStatus(
            name = name,
            installed = exists && executable,
            path = file.absolutePath,
            detail = detail,
        )
    }

    private fun tarXzGuidance(fileName: String): InstallProgress =
        InstallProgress(
            stage = "unsupported",
            fraction = 0f,
            message = buildString {
                appendLine("'$fileName' is a .tar.xz archive.")
                appendLine()
                appendLine("Android cannot stream-decompress xz from this build: the APK ships no")
                appendLine("native liblzma, and a pure-Kotlin decoder would stall for minutes on a")
                appendLine("1-2 GB rootfs. Convert it once on a desktop and import the result:")
                appendLine()
                appendLine("  1. tar -xf $fileName")
                appendLine("  2. cd imagefs            # folder holding usr/, opt/, home/")
                appendLine("  3. zip -r -y ../imagefs.zip .")
                appendLine("  4. copy imagefs.zip to the device and import that.")
                appendLine()
                appendLine("Keep the '-y' flag: it preserves the symlinks Wine needs")
                append("(dosdevices, drive_c/windows/system32/...).")
            },
            isComplete = false,
            error = null,
        )

    private fun extractZip(input: InputStream): Flow<InstallProgress> = flow {
        val root = rootFsDir
        if (!root.isDirectory && !root.mkdirs()) {
            emit(
                InstallProgress(
                    stage = "prepare",
                    fraction = 0f,
                    message = "Cannot create ${root.absolutePath}",
                    isComplete = false,
                    error = "imagefs directory is not writable",
                ),
            )
            return@flow
        }

        val canonicalRoot = root.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator

        emit(InstallProgress("prepare", 0.02f, "Extracting into ${root.absolutePath}"))

        var entryCount = 0
        var fileCount = 0
        var bytesWritten = 0L
        var skipped = 0
        var lastReportedFraction = 0.02f

        val buffer = ByteArray(BUFFER_SIZE)

        ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++

                val target = resolveEntry(canonicalRoot, rootPrefix, entry.name)
                if (target == null) {
                    // Zip-Slip attempt: refuse this entry, keep the rest of the archive.
                    skipped++
                    zip.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                    zip.closeEntry()
                    continue
                }

                target.parentFile?.mkdirs()
                val written = writeEntry(zip, target, buffer)
                if (written < 0) {
                    skipped++
                    zip.closeEntry()
                    continue
                }
                bytesWritten += written
                fileCount++

                // Progress is byte-weighted with a soft 300 MB denominator: the
                // uncompressed size is unknown on a stream, and this keeps the bar
                // monotonic without pinning it at 100% before the tail entries.
                val fraction = (0.05f + 0.9f * (bytesWritten / SOFT_TOTAL_BYTES.toFloat()))
                    .coerceIn(0.05f, 0.95f)
                if (fraction - lastReportedFraction >= 0.01f || entryCount % 250 == 0) {
                    lastReportedFraction = fraction
                    emit(
                        InstallProgress(
                            stage = "extract",
                            fraction = fraction,
                            message = "$fileCount files · ${bytesWritten / (1024 * 1024)} MB" +
                                " · ${entry.name.takeLast(48)}",
                        ),
                    )
                }
                zip.closeEntry()
            }
        }

        emit(InstallProgress("verify", 0.96f, "Marking binaries executable"))

        val executableCount = markExecutables()

        val skippedNote = if (skipped > 0) " · $skipped unsafe entries skipped" else ""
        emit(
            InstallProgress(
                stage = "complete",
                fraction = 1f,
                message = "Imported $fileCount files · $executableCount executables$skippedNote",
                isComplete = true,
            ),
        )
    }

    /**
     * Resolves a zip entry to a target inside [canonicalRoot], or `null` when the
     * entry escapes the root (absolute path, `..` traversal, or a Windows-style
     * separator trick). This is the Zip-Slip guard.
     */
    private fun resolveEntry(canonicalRoot: File, rootPrefix: String, entryName: String): File? {
        val normalised = entryName.replace('\\', '/')
        if (normalised.isBlank()) return null
        if (normalised.startsWith("/")) return null
        val candidate = File(canonicalRoot, normalised)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (canonical.path == canonicalRoot.path || canonical.path.startsWith(rootPrefix)) {
            canonical
        } else {
            null
        }
    }

    /** Copies one entry; returns bytes written, or -1 when the copy failed. */
    private fun writeEntry(zip: ZipInputStream, target: File, buffer: ByteArray): Long {
        var written = 0L
        return try {
            target.outputStream().use { out: OutputStream ->
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    written += read
                    if (written > MAX_ENTRY_BYTES) break
                }
            }
            written
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Android's unzip APIs drop unix mode bits, so every binary path that Wine
     * or proot will exec is chmod'ed after extraction. Returns how many files
     * were touched.
     */
    private fun markExecutables(): Int {
        val candidates = listOf(
            RELATIVE_PROOT,
            RELATIVE_BOX64,
            RELATIVE_WINE,
            "bin",
            "sbin",
            "usr/bin",
            "usr/sbin",
            "usr/local/bin",
            "usr/local/sbin",
            "opt/wine/bin",
            "opt/wine/lib/wine/x86_64-unix",
        )

        var touched = 0
        candidates.forEach { relative ->
            val file = File(rootFsDir, relative)
            if (file.isFile) {
                if (file.setExecutable(true, false)) touched++
                return@forEach
            }
            if (!file.isDirectory) return@forEach
            file.walkTopDown()
                .filter { it.isFile }
                .forEach { child ->
                    if (!child.canExecute() && child.setExecutable(true, false)) touched++
                }
        }
        return touched
    }

    companion object {
        /** Relative path of proot inside the rootfs. */
        const val RELATIVE_PROOT: String = "usr/bin/proot"

        /** Relative path of the Box64 dynarec inside the rootfs. */
        const val RELATIVE_BOX64: String = "usr/local/bin/box64"

        /** Relative path of the Wine loader inside the rootfs. */
        const val RELATIVE_WINE: String = "opt/wine/bin/wine"

        private const val BUFFER_SIZE = 64 * 1024

        /** Soft denominator used for progress reporting (300 MB). */
        private const val SOFT_TOTAL_BYTES = 300L * 1024L * 1024L

        /** Hard per-entry ceiling (4 GB) so a crafted archive cannot fill the disk. */
        private const val MAX_ENTRY_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
