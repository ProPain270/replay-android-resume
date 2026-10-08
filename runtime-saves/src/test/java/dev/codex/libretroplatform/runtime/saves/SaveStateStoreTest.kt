package dev.codex.libretroplatform.runtime.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SaveStateStoreTest {
    private lateinit var root: Path
    private lateinit var store: SaveStateStore
    private val identity = SaveStateIdentity(
        contentSha256 = "b".repeat(64),
        coreId = "test-core",
        coreVersion = "1.0.0+test",
        stateFormatVersion = 1,
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("save-state-test")
        store = SaveStateStore(root)
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
    fun matchingIdentityReturnsVerifiedState() {
        store.write(
            slot = "slot-1",
            identity = identity,
            appVersion = "0.1.0",
            payload = ByteArrayInputStream("state".toByteArray()),
            createdAtEpochMs = 42,
        )

        val result = store.read("slot-1", identity)

        assertTrue(result is SaveStateReadResult.Success)
        val state = (result as SaveStateReadResult.Success).state
        assertEquals(42, state.metadata.createdAtEpochMs)
        assertEquals("state", Files.readAllBytes(state.payloadPath).decodeToString())
    }

    @Test
    fun contentOrCoreMismatchIsExplicitAndDoesNotLoadPayload() {
        store.write(
            slot = "slot-1",
            identity = identity,
            appVersion = "0.1.0",
            payload = ByteArrayInputStream("state".toByteArray()),
        )
        val differentIdentity = identity.copy(coreVersion = "2.0.0")

        val result = store.read("slot-1", differentIdentity)

        assertTrue(result is SaveStateReadResult.IdentityMismatch)
        assertEquals(identity, (result as SaveStateReadResult.IdentityMismatch).actual)
    }

    @Test
    fun corruptCurrentStateFallsBackToVerifiedPreviousState() {
        store.write("slot-1", identity, "0.1.0", ByteArrayInputStream("first".toByteArray()), 1)
        store.write("slot-1", identity, "0.1.0", ByteArrayInputStream("second".toByteArray()), 2)
        Files.write(store.payloadPath("slot-1", identity), "corrupt".toByteArray())

        val result = store.read("slot-1", identity)

        assertTrue(result is SaveStateReadResult.Success)
        assertEquals("first", Files.readAllBytes((result as SaveStateReadResult.Success).state.payloadPath).decodeToString())
    }

    @Test
    fun missingAndTraversalSlotsAreHandledSafely() {
        assertTrue(store.read("missing", identity) is SaveStateReadResult.Missing)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.read("../escape", identity)
        }
    }

    @Test
    fun sameSlotKeepsDifferentContentAndCoreVersionsIsolated() {
        val other = identity.copy(
            contentSha256 = "c".repeat(64),
            coreId = "other-core",
            coreVersion = "2.0.0+other",
        )
        store.write("slot-1", identity, "0.1.0", ByteArrayInputStream("first".toByteArray()))
        store.write("slot-1", other, "0.1.0", ByteArrayInputStream("second".toByteArray()))

        val first = store.read("slot-1", identity)
        val second = store.read("slot-1", other)

        assertEquals("first", Files.readAllBytes((first as SaveStateReadResult.Success).state.payloadPath).decodeToString())
        assertEquals("second", Files.readAllBytes((second as SaveStateReadResult.Success).state.payloadPath).decodeToString())
    }

    @Test
    fun exportsOnlyVerifiedStateAndRehashesProviderOutput() {
        store.write(
            slot = "slot-1",
            identity = identity,
            appVersion = "0.1.0",
            payload = ByteArrayInputStream("portable-state".toByteArray()),
        )
        val target = MemoryTarget()

        val exported = store.exportVerified("slot-1", identity, target)

        assertEquals("portable-state", target.bytes.decodeToString())
        assertEquals(target.bytes.size.toLong(), exported.payloadSizeBytes)
        assertTrue(exported.payloadSha256.isNotBlank())
    }

    private class MemoryTarget : ExternalSaveTarget {
        var bytes: ByteArray = byteArrayOf()

        override fun openOutputStream(): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                bytes = toByteArray()
                super.close()
            }
        }

        override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)
    }
}
