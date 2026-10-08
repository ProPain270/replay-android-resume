package dev.codex.libretroplatform.runtime.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class NativeSaveStoreTest {
    private lateinit var root: Path
    private lateinit var store: NativeSaveStore
    private val key = SaveKey("a".repeat(64), "test-core")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("native-save-test")
        store = NativeSaveStore(root)
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
    fun transactionsRotateAndRecoverCorruptCurrent() {
        store.write(key, ByteArrayInputStream("first".toByteArray()), nowEpochMs = 10)
        store.write(key, ByteArrayInputStream("second".toByteArray()), nowEpochMs = 20)
        Files.write(store.canonicalPath(key), "corrupt".toByteArray())

        val result = store.recover(key)

        assertTrue(result is NativeRecoveryResult.Available)
        assertTrue((result as NativeRecoveryResult.Available).restoredFromRecovery)
        assertEquals("first", Files.readAllBytes(store.canonicalPath(key)).decodeToString())
        assertEquals("first", store.openCanonicalInputStream(key).use { it.readBytes().decodeToString() })
    }

    @Test
    fun failedTransactionLeavesLastKnownGoodSave() {
        store.write(key, ByteArrayInputStream("stable".toByteArray()), nowEpochMs = 10)
        assertThrows(SaveTransactionException::class.java) {
            store.write(key, FailingInputStream("new-data".toByteArray()), nowEpochMs = 20)
        }

        assertEquals("stable", Files.readAllBytes(store.canonicalPath(key)).decodeToString())
    }

    @Test
    fun externalExportIsVerifiedAndDoesNotChangeCanonicalSave() {
        store.write(key, ByteArrayInputStream("canonical".toByteArray()), nowEpochMs = 10)
        val target = MemoryTarget()

        val exported = store.exportVerified(key, target)

        assertEquals("canonical", target.bytes().decodeToString())
        assertEquals("canonical".toByteArray().size.toLong(), exported.payloadSizeBytes)
        assertEquals("canonical", Files.readAllBytes(store.canonicalPath(key)).decodeToString())
    }

    @Test
    fun failedExternalVerificationCannotReplaceCanonicalSave() {
        store.write(key, ByteArrayInputStream("canonical".toByteArray()), nowEpochMs = 10)

        assertThrows(SaveExportVerificationException::class.java) {
            store.exportVerified(key, object : ExternalSaveTarget {
                override fun openOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun openInputStream(): InputStream = ByteArrayInputStream("wrong".toByteArray())
            })
        }

        assertEquals("canonical", Files.readAllBytes(store.canonicalPath(key)).decodeToString())
    }

    @Test
    fun traversalAndUnsafeCoreIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SaveKey("../" + "a".repeat(62), "core")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SaveKey("a".repeat(64), "../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavePathPolicy.resolveInside(root, "../escape/data.bin")
        }
    }

    private class MemoryTarget : ExternalSaveTarget {
        private val output = ByteArrayOutputStream()
        override fun openOutputStream(): OutputStream = output
        override fun openInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())
        fun bytes(): ByteArray = output.toByteArray()
    }

    private class FailingInputStream(private val bytes: ByteArray) : InputStream() {
        private var position = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position > 0) throw IOException("interrupted source")
            val count = minOf(length, bytes.size)
            bytes.copyInto(buffer, offset, 0, count)
            position = count
            return count
        }

        override fun read(): Int = throw IOException("interrupted source")
    }
}
