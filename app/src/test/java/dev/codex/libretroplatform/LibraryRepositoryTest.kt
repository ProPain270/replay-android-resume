package dev.codex.libretroplatform

import dev.codex.libretroplatform.runtime.content.ContentImportCancelled
import dev.codex.libretroplatform.runtime.content.ContentSource
import dev.codex.libretroplatform.runtime.content.ImportCancellationToken
import dev.codex.libretroplatform.runtime.saves.NativeRecoveryResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class LibraryRepositoryTest {
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("library-repository-test")
        repository = LibraryRepository(root, nowEpochMs = { 100L })
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
    fun importUsesVerifiedContentIdentityAndDeduplicatesDifferentSources() {
        val bytes = "same-content".toByteArray()
        val first = repository.importContent(source("first.rom", bytes))
        val second = repository.importContent(source("renamed.rom", bytes))

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.item.id, second.item.id)
        assertEquals(sha256(bytes), first.item.contentId)
        assertEquals(bytes.size.toLong(), first.item.contentSizeBytes)
        assertEquals(1, repository.load().size)
        assertEquals("renamed.rom", repository.load().single().sourceDisplayName)
        assertEquals(first.item.privateContentRef, second.item.privateContentRef)
        assertEquals(bytes.toList(), Files.readAllBytes(Path.of(first.item.privateContentRef!!)).toList())
    }

    @Test
    fun supportedExtensionsResolveToBundledCoreAtImport() {
        val item = repository.importContent(source("test.gb", byteArrayOf(1, 2, 3))).item

        assertEquals("Game Boy", item.system)
        assertEquals("sameboy", item.coreId)
        assertEquals(SupportStatus.Playable, item.supportStatus)
    }

    @Test
    fun markPlayedPersistsRecencyWithoutChangingContentIdentity() {
        val imported = repository.importContent(source("recent.gb", "game".toByteArray())).item

        repository.markPlayed(imported.id, playedAtEpochMs = 500L)

        val reloaded = LibraryRepository(root, nowEpochMs = { 600L }).load().single()
        assertEquals(imported.id, reloaded.id)
        assertEquals(imported.contentId, reloaded.contentId)
        assertEquals(500L, reloaded.lastPlayedAt)
    }

    @Test
    fun metadataAliasAndSortTitlePersistWithoutChangingOriginalIdentity() {
        val imported = repository.importContent(source("original.gba", "game".toByteArray())).item

        val updated = repository.updateMetadata(
            itemId = imported.id,
            titleAlias = "Metroid Zero Mission",
            sortTitle = "Metroid Zero Mission",
        )

        val reloaded = LibraryRepository(root).load().single()
        assertEquals("original", reloaded.title)
        assertEquals("Metroid Zero Mission", updated.displayTitle)
        assertEquals("Metroid Zero Mission", reloaded.displayTitle)
        assertEquals("Metroid Zero Mission", reloaded.sortTitleKey)
        assertEquals(imported.contentId, reloaded.contentId)
        assertEquals(imported.privateContentRef, reloaded.privateContentRef)
    }

    @Test
    fun titlePreferencesPersistWithoutChangingContentIdentity() {
        val imported = repository.importContent(source("per-title.gb", "game".toByteArray())).item
        val override = TitlePlayerPreferences(
            displayPreset = DisplayPreset.Crisp,
            playerDisplaySize = PlayerDisplaySize.Maximum,
            pixelScalingMode = PixelScalingMode.Integer,
            touchReachMode = TouchReachMode.OneHandedRight,
            audioMuted = true,
        )

        val updated = repository.updateTitlePreferences(imported.id, override)
        val reloaded = LibraryRepository(root).load().single()

        assertEquals(override, updated.titlePreferences)
        assertEquals(override, reloaded.titlePreferences)
        assertEquals(imported.contentId, reloaded.contentId)
        assertEquals(imported.privateContentRef, reloaded.privateContentRef)
    }

    @Test
    fun cancellationLeavesNoPartialContentOrLibraryRecord() {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        var cancellationRequested = false

        assertThrows(ContentImportCancelled::class.java) {
            repository.importContent(
                source = source("cancelled.rom", bytes),
                cancellationToken = ImportCancellationToken { cancellationRequested },
                onProgress = { cancellationRequested = true },
            )
        }

        assertTrue(repository.load().isEmpty())
        val contentFiles = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bin") }.count()
        }
        assertEquals(0L, contentFiles)
    }

    @Test
    fun nativeSaveAndSaveStateSurviveRepositoryReloadWithIdentity() {
        val imported = repository.importContent(source("game.rom", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "test-core",
            coreVersion = "1.0.0",
            stateFormatVersion = 1,
        )

        repository.writeNativeSave(
            itemId = imported.item.id,
            identity = identity,
            payload = ByteArrayInputStream("battery".toByteArray()),
            createdAtEpochMs = 10L,
        )
        repository.writeSaveState(
            itemId = imported.item.id,
            identity = identity,
            slot = "slot-1",
            payload = ByteArrayInputStream("state".toByteArray()),
            createdAtEpochMs = 11L,
        )

        val reloaded = LibraryRepository(root, nowEpochMs = { 200L })
        val item = reloaded.load().single()
        assertEquals(imported.item.id, item.id)
        assertEquals("test-core", item.coreId)
        assertEquals(SaveSummary.SavePresent, item.saveSummary)

        val state = reloaded.readSaveState(item.id, identity, "slot-1")
        assertTrue(state is dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success)
        assertEquals(
            "state",
            Files.readAllBytes((state as dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success).state.payloadPath).decodeToString(),
        )
    }

    @Test
    fun reimportRestoresADeletedPrivateCopyWhenTheSourceIdentityIsRetained() {
        val bytes = "recoverable-game".toByteArray()
        val imported = repository.importContent(source("recoverable.gb", bytes)).item
        Files.delete(Path.of(imported.privateContentRef!!))

        val restored = repository.importContent(source("recoverable.gb", bytes)).item

        assertTrue(Files.isRegularFile(Path.of(restored.privateContentRef!!)))
        assertEquals(bytes.toList(), Files.readAllBytes(Path.of(restored.privateContentRef)).toList())
    }

    @Test
    fun privateContentVerificationDetectsPostImportCorruption() {
        val imported = repository.importContent(source("integrity.gb", "trusted-bytes".toByteArray())).item
        val privatePath = Path.of(imported.privateContentRef!!)

        assertTrue(repository.verifyPrivateContent(imported.id))
        Files.write(privatePath, "tampered-bytes".toByteArray())
        assertFalse(repository.verifyPrivateContent(imported.id))
    }

    @Test
    fun changedSourceIsReportedWithoutTouchingTheTrustedPrivateCopy() {
        val original = "trusted-source".toByteArray()
        val imported = repository.importContent(source("mutable.gb", original)).item
        val changed = repository.verifySource(imported.id, source("mutable.gb", "changed-source".toByteArray()))

        assertEquals(SourceVerificationStatus.Changed, changed.status)
        assertEquals(imported.contentId, changed.expected?.sha256)
        assertTrue(repository.verifyPrivateContent(imported.id))
        assertEquals(original.toList(), Files.readAllBytes(Path.of(imported.privateContentRef!!)).toList())
    }

    @Test
    fun matchingSourceIsAcceptedByIdentity() {
        val bytes = "same-source".toByteArray()
        val imported = repository.importContent(source("stable.gb", bytes)).item

        val verification = repository.verifySource(imported.id, source("renamed.gb", bytes))

        assertEquals(SourceVerificationStatus.Matches, verification.status)
        assertEquals(imported.contentId, verification.actual?.sha256)
    }

    @Test
    fun runtimeStagingIsVerifiedBeforeNativeAndStatePromotion() {
        val imported = repository.importContent(source("game.gb", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "sameboy",
            coreVersion = "bundled-v1",
            stateFormatVersion = 1,
        )

        val nativeStaging = repository.nativeSaveStagingPath(imported.item.id, "sameboy")
        Files.write(nativeStaging, "battery-from-runtime".toByteArray())
        repository.commitNativeSaveFromStaging(imported.item.id, identity, createdAtEpochMs = 10L)
        assertFalse(Files.exists(nativeStaging))
        assertEquals(
            "battery-from-runtime",
            repository.recoverNativeSave(imported.item.id, identity).let { result ->
                Files.readAllBytes((result as NativeRecoveryResult.Available).record.path).decodeToString()
            },
        )

        repository.writeSaveState(
            imported.item.id,
            identity,
            "slot-1",
            ByteArrayInputStream("verified-state".toByteArray()),
            createdAtEpochMs = 11L,
        )
        val stateStaging = repository.saveStateStagingPath(imported.item.id, "sameboy", "slot-1")
        assertTrue(repository.stageSaveStateForRuntime(imported.item.id, identity, "slot-1"))
        assertEquals("verified-state", Files.readAllBytes(stateStaging).decodeToString())
    }

    @Test
    fun runtimeStateStagingCanCommitIntoAChosenSlot() {
        val imported = repository.importContent(source("slots.gb", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "sameboy",
            coreVersion = "bundled-v1",
            stateFormatVersion = 1,
        )
        val runtimeStaging = repository.saveStateStagingPath(imported.item.id, "sameboy", "runtime")
        Files.write(runtimeStaging, "slot-three-state".toByteArray())

        repository.commitSaveStateFromStaging(
            itemId = imported.item.id,
            identity = identity,
            slot = "slot-3",
            stagingSlot = "runtime",
            createdAtEpochMs = 12L,
        )

        assertFalse(Files.exists(runtimeStaging))
        val saved = repository.readSaveState(imported.item.id, identity, "slot-3")
        assertTrue(saved is dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success)
        assertEquals(
            "slot-three-state",
            Files.readAllBytes((saved as dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success).state.payloadPath).decodeToString(),
        )
    }

    @Test
    fun readSaveStateSlotsReportsOnlyVerifiedStatesAndTheirRecency() {
        val imported = repository.importContent(source("slot-status.gb", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "sameboy",
            coreVersion = "bundled-v1",
            stateFormatVersion = 1,
        )
        repository.writeSaveState(
            imported.item.id,
            identity,
            "slot-2",
            ByteArrayInputStream("checkpoint".toByteArray()),
            createdAtEpochMs = 44L,
        )

        val slots = repository.readSaveStateSlots(imported.item.id, identity)

        assertEquals(9, slots.size)
        assertFalse(slots.first { it.slot == 1 }.available)
        assertEquals(SaveStateSlotAvailability.Empty, slots.first { it.slot == 1 }.availability)
        assertTrue(slots.first { it.slot == 2 }.available)
        assertEquals(SaveStateSlotAvailability.Verified, slots.first { it.slot == 2 }.availability)
        assertEquals(44L, slots.first { it.slot == 2 }.createdAtEpochMs)
        assertEquals("checkpoint".toByteArray().size.toLong(), slots.first { it.slot == 2 }.payloadSizeBytes)
        assertTrue(slots.drop(2).none { it.available })
    }

    @Test
    fun saveStateThumbnailIsOptionalMetadataBoundToTheVerifiedSlot() {
        val imported = repository.importContent(source("thumbnail.gb", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "sameboy",
            coreVersion = "bundled-v1",
            stateFormatVersion = 1,
        )
        val runtimeStaging = repository.saveStateStagingPath(imported.item.id, "sameboy", "runtime")
        Files.write(runtimeStaging, "checkpoint".toByteArray())
        val thumbnail = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        repository.commitSaveStateFromStaging(
            itemId = imported.item.id,
            identity = identity,
            slot = "slot-4",
            stagingSlot = "runtime",
            thumbnailPng = thumbnail,
            createdAtEpochMs = 55L,
        )

        val slot = repository.readSaveStateSlots(imported.item.id, identity).first { it.slot == 4 }
        assertTrue(slot.available)
        assertNotNull(slot.thumbnailPath)
        assertEquals(thumbnail.toList(), Files.readAllBytes(Path.of(slot.thumbnailPath!!)).toList())
    }

    @Test
    fun thumbnailFilesystemFailureDoesNotInvalidateCanonicalSave() {
        val imported = repository.importContent(source("thumbnail-failure.gb", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "sameboy",
            coreVersion = "bundled-v1",
            stateFormatVersion = 1,
        )
        val runtimeStaging = repository.saveStateStagingPath(imported.item.id, "sameboy", "runtime")
        Files.write(runtimeStaging, "checkpoint".toByteArray())
        val thumbnailRoot = root.resolve("save-state-thumbnails")
        Files.walk(thumbnailRoot).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        Files.write(thumbnailRoot, byteArrayOf(1))

        val saved = repository.commitSaveStateFromStaging(
            itemId = imported.item.id,
            identity = identity,
            slot = "slot-5",
            stagingSlot = "runtime",
            thumbnailPng = byteArrayOf(1, 2, 3),
            createdAtEpochMs = 66L,
        )

        assertEquals(66L, saved.createdAtEpochMs)
        val state = repository.readSaveState(imported.item.id, identity, "slot-5")
        assertTrue(state is dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success)
        assertEquals(
            "checkpoint",
            Files.readAllBytes((state as dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success).state.payloadPath).decodeToString(),
        )
    }

    @Test
    fun removingItemCleansContentSavesStatesAndRuntimeStaging() {
        val imported = repository.importContent(source("cleanup.gb", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "sameboy",
            coreVersion = "bundled-v1",
            stateFormatVersion = 1,
        )

        repository.writeNativeSave(
            imported.item.id,
            identity,
            ByteArrayInputStream("battery".toByteArray()),
            createdAtEpochMs = 10L,
        )
        repository.writeSaveState(
            imported.item.id,
            identity,
            "slot-1",
            ByteArrayInputStream("state".toByteArray()),
            createdAtEpochMs = 11L,
        )
        val nativeStaging = repository.nativeSaveStagingPath(imported.item.id, "sameboy")
        val stateStaging = repository.saveStateStagingPath(imported.item.id, "sameboy", "slot-1")
        Files.write(nativeStaging, "staged-battery".toByteArray())
        Files.write(stateStaging, "staged-state".toByteArray())

        repository.remove(imported.item.id)

        assertTrue(repository.load().isEmpty())
        assertFalse(Files.exists(Path.of(imported.item.privateContentRef!!)))
        assertFalse(Files.exists(root.resolve("saves/native/${imported.item.contentId}")))
        assertFalse(Files.exists(root.resolve("save-states/states/${imported.item.contentId}")))
        assertFalse(Files.exists(root.resolve("runtime/native-save-staging/${imported.item.contentId}")))
        assertFalse(Files.exists(root.resolve("runtime/save-state-staging/${imported.item.contentId}")))
    }

    @Test
    fun saveIdentityMismatchDoesNotLoadOrReplaceCanonicalState() {
        val imported = repository.importContent(source("game.rom", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "test-core",
            coreVersion = "1.0.0",
            stateFormatVersion = 1,
        )
        repository.writeSaveState(
            itemId = imported.item.id,
            identity = identity,
            slot = "slot-1",
            payload = ByteArrayInputStream("state".toByteArray()),
            createdAtEpochMs = 10L,
        )

        val mismatch = identity.copy(coreVersion = "2.0.0")
        val result = repository.readSaveState(imported.item.id, mismatch, "slot-1")

        assertTrue(result is dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.IdentityMismatch)
        val valid = repository.readSaveState(imported.item.id, identity, "slot-1")
        assertTrue(valid is dev.codex.libretroplatform.runtime.saves.SaveStateReadResult.Success)
    }

    @Test
    fun corruptNativeCurrentIsRecoveredAndSurfacedOnReload() {
        val imported = repository.importContent(source("game.rom", "game".toByteArray()))
        val identity = LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = imported.item.contentId,
            coreId = "test-core",
            coreVersion = "1.0.0",
            stateFormatVersion = 1,
        )
        repository.writeNativeSave(
            imported.item.id,
            identity,
            ByteArrayInputStream("first".toByteArray()),
            createdAtEpochMs = 10L,
        )
        repository.writeNativeSave(
            imported.item.id,
            identity,
            ByteArrayInputStream("second".toByteArray()),
            createdAtEpochMs = 20L,
        )

        val nativePath = root.resolve("saves/native/${imported.item.contentId}/test-core/current/data.bin")
        Files.write(nativePath, "corrupt".toByteArray())

        val reloaded = LibraryRepository(root, nowEpochMs = { 300L })
        val recovered = reloaded.load().single()

        assertEquals(SaveSummary.RecoveryAvailable, recovered.saveSummary)
        assertEquals("first", Files.readAllBytes(nativePath).decodeToString())
        assertNotNull(reloaded.load().single().privateContentRef)
    }

    private fun source(name: String, bytes: ByteArray): ContentSource = object : ContentSource {
        override val displayName: String = name
        override val sizeBytes: Long = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
