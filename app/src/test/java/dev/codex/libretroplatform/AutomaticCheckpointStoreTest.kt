package dev.codex.libretroplatform

import dev.codex.libretroplatform.runtime.saves.SaveStateIdentity
import dev.codex.libretroplatform.runtime.saves.SaveStateStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutomaticCheckpointStoreTest {
    private lateinit var root: Path
    private lateinit var store: AutomaticCheckpointStore
    private val identity = SaveStateIdentity(
        contentSha256 = "a".repeat(64),
        coreId = "sameboy",
        coreVersion = "test-core",
        stateFormatVersion = 1,
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("automatic-checkpoint-test")
        store = AutomaticCheckpointStore(root)
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun rotatesThreeEntriesAndKeepsNewestFirst() {
        val first = write(1, "one")
        val second = write(2, "two")
        val third = write(3, "three")
        val fourth = write(4, "four")

        val history = store.list(identity)
        assertEquals(AutomaticCheckpointStore.HISTORY_SIZE, history.size)
        assertEquals(listOf(fourth.id, third.id, second.id), history.map { it.id })
        assertTrue(first.id !in history.map { it.id })
        assertEquals("four", readPayload(store.read(identity)?.second?.payloadPath))
    }

    @Test
    fun identityScopedReadAndThumbnailMarkerAreVerified() {
        val checkpoint = write(7, "payload", thumbnail = byteArrayOf(1, 2, 3))
        assertNotNull(checkpoint.thumbnailPath)
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(Path.of(checkpoint.thumbnailPath!!)))
        assertEquals(checkpoint, store.read(identity, checkpoint.id)?.first)

        val otherIdentity = identity.copy(contentSha256 = "b".repeat(64))
        assertNull(store.read(otherIdentity))
    }

    @Test
    fun corruptNewestGenerationFallsBackToPreviousVerifiedGeneration() {
        write(10, "older")
        val newest = write(11, "newer")
        val saveStateStore = SaveStateStore(root)
        Files.write(saveStateStore.payloadPath("automatic-${newest.slot}", identity), "corrupt".toByteArray())

        val recovered = store.read(identity)
        assertNotNull(recovered)
        assertEquals("older", readPayload(recovered!!.second.payloadPath))
    }

    private fun write(
        timestamp: Long,
        payload: String,
        thumbnail: ByteArray? = null,
    ): AutomaticCheckpoint = store.write(
        identity = identity,
        appVersion = "test",
        payload = ByteArrayInputStream(payload.toByteArray()),
        createdAtEpochMs = timestamp,
        thumbnailPng = thumbnail,
    )

    private fun readPayload(path: Path?): String? = path?.let { Files.readAllBytes(it).decodeToString() }
}
