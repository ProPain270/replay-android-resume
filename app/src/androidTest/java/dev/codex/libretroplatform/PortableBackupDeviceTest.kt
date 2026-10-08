package dev.codex.libretroplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android filesystem coverage in isolated cache roots, never the user's live backup tree. */
@RunWith(AndroidJUnit4::class)
class PortableBackupDeviceTest {
    private val roots = mutableListOf<Path>()

    @After fun cleanUp() {
        roots.forEach { root ->
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun interruptedSourceReadLeavesEveryExistingDestinationByteUnchanged() {
        val archive = backup()
        val destination = existingDestination()
        val baseline = snapshot(destination)
        val interruption = IOException("injected source interruption")
        val stream = object : InputStream() {
            private var position = 0
            private val limit = archive.size / 2
            override fun read(): Int {
                if (position >= limit) throw interruption
                return archive[position++].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= limit) throw interruption
                val count = minOf(length, limit - position)
                archive.copyInto(bytes, offset, position, position + count)
                position += count
                return count
            }
        }
        val failure = runCatching { PortableBackupStore(destination).restore(stream) }.exceptionOrNull()
        assertSame("Must reach the injected read fault", interruption, failure)
        assertUnchangedAndNoStaging(destination, baseline)
        // This tests interruption during stream staging, not process death or
        // an interruption after destination commit has started.
    }

    @Test fun validZipWithHashMismatchIsRejectedBeforeAnyDestinationOverwrite() {
        val destination = existingDestination()
        val baseline = snapshot(destination)
        val corrupted = rewriteZip(backup()) { name, bytes ->
            if (name == "saves/native/save.bin") bytes.clone().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
            else bytes
        }
        val failure = runCatching { PortableBackupStore(destination).restore(ByteArrayInputStream(corrupted)) }.exceptionOrNull()
        assertNotNull("Repacked ZIP must not bypass the manifest digest", failure)
        assertTrue("Expected manifest hash failure, got $failure", failure?.message?.contains("hash", ignoreCase = true) == true)
        assertUnchangedAndNoStaging(destination, baseline)
    }

    @Test fun traversalBundleCannotEscapeDestinationOrChangeExistingSaves() {
        val parent = newRoot()
        val destination = Files.createDirectory(parent.resolve("destination"))
        write(destination, "saves/native/save.bin", byteArrayOf(1, 2, 3))
        val outside = parent.resolve("escape-marker")
        Files.write(outside, byteArrayOf(9, 8, 7))
        val baseline = snapshot(destination)
        val malformed = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../escape-marker"))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }.toByteArray()
        assertNotNull(runCatching { PortableBackupStore(destination).restore(ByteArrayInputStream(malformed)) }.exceptionOrNull())
        assertArrayEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(outside))
        assertUnchangedAndNoStaging(destination, baseline)
    }

    @Test fun validRestoreRecoversUserDataAndPreferencesButNotGameBinaries() {
        val source = newRoot()
        write(source, "library/records/fixture.properties", "record".toByteArray())
        write(source, "saves/native/save.bin", byteArrayOf(2, 4, 6, 8))
        write(source, "content/sha256/fixture.gb", byteArrayOf(11, 12))
        val preferences = UserPreferences(audioMuted = true, controlsOpacity = .7f)
        val archive = ByteArrayOutputStream()
        PortableBackupStore(source).export(archive, preferences)
        val destination = newRoot()
        val restored = PortableBackupStore(destination).restore(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(preferences, restored.preferences)
        assertEquals(1, restored.libraryRecords)
        assertEquals(1, restored.nativeSaves)
        assertArrayEquals(byteArrayOf(2, 4, 6, 8), Files.readAllBytes(destination.resolve("saves/native/save.bin")))
        assertArrayEquals("record".toByteArray(), Files.readAllBytes(destination.resolve("library/records/fixture.properties")))
        assertFalse("Portable backups exclude ROMs", Files.exists(destination.resolve("content")))
        assertNoStaging(destination)
    }

    private fun backup(): ByteArray {
        val source = newRoot()
        write(source, "library/records/fixture.properties", "new-record".toByteArray())
        // Incompressible deterministic payload ensures a mid-stream fault is not
        // accidentally after ZIP EOF or just a central-directory truncation.
        val bytes = ByteArray(64 * 1024).also { Random(7).nextBytes(it) }
        write(source, "saves/native/save.bin", bytes)
        return ByteArrayOutputStream().also { PortableBackupStore(source).export(it, UserPreferences()) }.toByteArray()
    }

    private fun existingDestination(): Path = newRoot().also {
        write(it, "library/records/fixture.properties", "existing-record".toByteArray())
        write(it, "saves/native/save.bin", "existing-save".toByteArray())
        write(it, "settings.properties", "existing-settings".toByteArray())
    }

    private fun rewriteZip(bytes: ByteArray, transform: (String, ByteArray) -> ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { target ->
                ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                    while (true) {
                        val entry = source.nextEntry ?: break
                        target.putNextEntry(ZipEntry(entry.name))
                        target.write(transform(entry.name, source.readBytes()))
                        target.closeEntry()
                        source.closeEntry()
                    }
                }
            }
        }.toByteArray()

    private fun assertUnchangedAndNoStaging(root: Path, baseline: Map<String, String>) {
        assertEquals("No existing file may change and no new payload may be promoted", baseline, snapshot(root))
        assertNoStaging(root)
    }

    private fun assertNoStaging(root: Path) {
        Files.list(root).use { paths ->
            assertFalse("Temporary restore directories must be removed", paths.anyMatch { it.fileName.toString().startsWith(".backup-restore-") })
        }
    }

    private fun snapshot(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toArray().map { it as Path }.associate {
            root.relativize(it).toString() to DiagnosticGbFixture.sha256(Files.readAllBytes(it))
        }
    }

    private fun write(root: Path, relative: String, bytes: ByteArray) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    private fun newRoot(): Path = Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "backup-integration-",
    ).also(roots::add)
}
