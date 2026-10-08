package dev.codex.libretroplatform.runtime.content

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ContentImporterTest {
    private lateinit var root: Path
    private lateinit var importer: ContentImporter

    @Before
    fun setUp() {
        root = Files.createTempDirectory("content-import-test")
        importer = ContentImporter(root)
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun importsAndHashesIntoPrivateCanonicalPath() {
        val bytes = "public-domain-fixture".toByteArray()
        val result = importer.importContent(FakeSource(bytes, "game.gb"))

        assertEquals(bytes.size.toLong(), result.identity.sizeBytes)
        assertTrue(Files.exists(result.canonicalPath))
        assertTrue(result.canonicalPath.startsWith(root.toAbsolutePath().normalize()))
        assertEquals(bytes.toList(), Files.readAllBytes(result.canonicalPath).toList())
    }

    @Test
    fun partialProviderFailureDoesNotPromoteOrLeaveTemp() {
        val failure = assertThrows(ContentImportException::class.java) {
            importer.importContent(FailingSource("partial".toByteArray(), failAfter = 3))
        }

        assertTrue(failure.message!!.contains("content import failed"))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun cancellationDeletesPartialTemp() {
        val cancelled = AtomicBoolean(false)
        val token = ImportCancellationToken { cancelled.get() }
        val source = object : ContentSource {
            override val displayName: String? = "cancelled.gb"
            override val sizeBytes: Long? = null
            override fun openStream() = object : ByteArrayInputStream(ByteArray(128 * 1024)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val result = super.read(buffer, offset, minOf(length, 1024))
                    cancelled.set(true)
                    return result
                }
            }
        }

        assertThrows(ContentImportCancelled::class.java) {
            importer.importContent(source, cancellationToken = token)
        }
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun rejectsContentAboveConfiguredImportLimitBeforeCopying() {
        val boundedImporter = ContentImporter(root, maxImportBytes = 16L, minimumFreeBytes = 0L)

        val failure = assertThrows(ContentImportException::class.java) {
            boundedImporter.importContent(FakeSource(ByteArray(17), "too-large.gb"))
        }

        assertTrue(failure.message!!.contains("import limit"))
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun keepsIdentityStableAndRejectsTraversalPath() {
        val bytes = byteArrayOf(1, 2, 3)
        val first = importer.importContent(FakeSource(bytes, "game.gb"))
        val second = importer.importContent(FakeSource(bytes, "game.gb"))

        assertEquals(first.canonicalPath, second.canonicalPath)
        assertEquals(first.identity, second.identity)
        assertThrows(IllegalArgumentException::class.java) {
            ContentPathPolicy.resolveInside(root, "../escape.bin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentPathPolicy.resolveInside(root, "/tmp/escape.bin")
        }
    }

    private class FakeSource(
        private val bytes: ByteArray,
        override val displayName: String?,
    ) : ContentSource {
        override val sizeBytes: Long = bytes.size.toLong()
        override fun openStream() = ByteArrayInputStream(bytes)
    }

    private class FailingSource(
        private val bytes: ByteArray,
        private val failAfter: Int,
    ) : ContentSource {
        override val displayName: String? = "broken.gb"
        override val sizeBytes: Long = bytes.size.toLong()
        override fun openStream() = object : ByteArrayInputStream(bytes) {
            private var readBytes = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (readBytes >= failAfter) throw IOException("provider interrupted")
                val count = super.read(buffer, offset, minOf(length, failAfter - readBytes))
                if (count > 0) readBytes += count
                return count
            }
        }
    }
}
