package dev.codex.libretroplatform

import android.view.KeyEvent
import dev.codex.libretroplatform.runtime.api.Button
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupStoreTest {
    private val roots = mutableListOf<Path>()

    @After
    fun tearDown() {
        roots.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    @Test
    fun roundTripIncludesUserDataButNeverGameBinaries() {
        val sourceRoot = newRoot()
        Files.createDirectories(sourceRoot.resolve("library/records"))
        Files.createDirectories(sourceRoot.resolve("library/collections"))
        Files.createDirectories(sourceRoot.resolve("content/sha256"))
        Files.createDirectories(sourceRoot.resolve("saves/native"))
        Files.createDirectories(sourceRoot.resolve("automatic-checkpoints/history"))
        Files.createDirectories(sourceRoot.resolve("artwork"))
        Files.write(sourceRoot.resolve("library/records/record.properties"), "record".toByteArray())
        Files.write(sourceRoot.resolve("library/collections/collection.properties"), "collection".toByteArray())
        Files.write(sourceRoot.resolve("saves/native/save.bin"), "battery".toByteArray())
        Files.write(sourceRoot.resolve("automatic-checkpoints/history/checkpoint.bin"), "recovery".toByteArray())
        Files.write(sourceRoot.resolve("artwork/cover.img"), "cover".toByteArray())
        Files.write(sourceRoot.resolve("content/sha256/game.gb"), "rom".toByteArray())
        val preferences = UserPreferences(
            favorites = setOf("content-id"),
            displayPreset = DisplayPreset.Night,
            playerDisplaySize = PlayerDisplaySize.Maximum,
            pixelScalingMode = PixelScalingMode.Integer,
            touchReachMode = TouchReachMode.OneHandedRight,
            performancePreset = PerformancePreset.LowLatency,
            touchLayoutSize = TouchLayoutSize.Compact,
            leftHanded = true,
            gamepadMapping = GamepadMapping.default().copy(a = KeyEvent.KEYCODE_BUTTON_X),
            gamepadProfiles = mapOf("controller-descriptor" to GamepadMapping.default()),
            hotkeys = mapOf(HotkeyAction.ExitPlayer to HotkeyBinding(Button.Select, Button.B)),
        )

        val bytes = ByteArrayOutputStream()
        val export = PortableBackupStore(sourceRoot).export(bytes, preferences)

        assertEquals(1, export.libraryRecords)
        assertEquals(1, export.collections)
        assertEquals(1, export.nativeSaves)
        assertEquals(1, export.automaticCheckpoints)
        assertEquals(1, export.artwork)
        assertFalse(bytes.toByteArray().decodeToString().contains("content/sha256/game.gb"))

        val destinationRoot = newRoot()
        val restore = PortableBackupStore(destinationRoot).restore(ByteArrayInputStream(bytes.toByteArray()))

        assertEquals(preferences, restore.preferences)
        assertEquals("record", Files.readAllBytes(destinationRoot.resolve("library/records/record.properties")).decodeToString())
        assertEquals("collection", Files.readAllBytes(destinationRoot.resolve("library/collections/collection.properties")).decodeToString())
        assertEquals("battery", Files.readAllBytes(destinationRoot.resolve("saves/native/save.bin")).decodeToString())
        assertEquals(1, restore.automaticCheckpoints)
        assertEquals("recovery", Files.readAllBytes(destinationRoot.resolve("automatic-checkpoints/history/checkpoint.bin")).decodeToString())
        assertEquals("cover", Files.readAllBytes(destinationRoot.resolve("artwork/cover.img")).decodeToString())
        assertFalse(Files.exists(destinationRoot.resolve("content")))
    }

    @Test
    fun malformedTraversalEntryIsRejectedBeforeWritingOutsideAppRoot() {
        val root = newRoot()
        val outside = root.parent.resolve("backup-escape-marker")
        Files.deleteIfExists(outside)
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../backup-escape-marker"))
                zip.write("must not land".toByteArray())
                zip.closeEntry()
            }
        }

        val failure = runCatching {
            PortableBackupStore(root).restore(ByteArrayInputStream(bytes.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(Files.exists(outside))
    }

    @Test
    fun gameContentEntryIsRejectedEvenBeforeManifestValidation() {
        val root = newRoot()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("content/game.gb"))
                zip.write("rom".toByteArray())
                zip.closeEntry()
            }
        }

        val failure = runCatching {
            PortableBackupStore(root).restore(ByteArrayInputStream(bytes.toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(Files.exists(root.resolve("content/game.gb")))
    }

    private fun newRoot(): Path = Files.createTempDirectory("portable-backup-test").also(roots::add)
}
