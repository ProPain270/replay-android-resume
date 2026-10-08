package dev.codex.libretroplatform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import dev.codex.libretroplatform.runtime.content.ContentImporter
import dev.codex.libretroplatform.runtime.content.ContentIdentity
import dev.codex.libretroplatform.runtime.content.ContentSource
import dev.codex.libretroplatform.runtime.content.ImportCancellationToken
import dev.codex.libretroplatform.runtime.content.ImportProgress
import dev.codex.libretroplatform.runtime.content.ImportedContent
import dev.codex.libretroplatform.runtime.content.SafContentSource
import dev.codex.libretroplatform.runtime.saves.NativeRecoveryResult
import dev.codex.libretroplatform.runtime.saves.NativeSaveRecord
import dev.codex.libretroplatform.runtime.saves.NativeSaveStore
import dev.codex.libretroplatform.runtime.saves.SaveKey
import dev.codex.libretroplatform.runtime.saves.SaveStateIdentity
import dev.codex.libretroplatform.runtime.saves.SaveStateReadResult
import dev.codex.libretroplatform.runtime.saves.SaveStateStore
import dev.codex.libretroplatform.runtime.saves.SaveStateMetadata
import dev.codex.libretroplatform.runtime.saves.SavePathPolicy
import dev.codex.libretroplatform.runtime.saves.ExternalSaveTarget
import dev.codex.libretroplatform.runtime.saves.ExportedSave
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Properties

/**
 * Durable product boundary for imported content and its persistence identities.
 *
 * The library index is app-private metadata. Content bytes and save payloads are
 * owned by their specialized stores, so a URI never becomes a native path and a
 * failed write cannot replace a verified record.
 */
class LibraryRepository private constructor(
    private val appPrivateRoot: Path,
    private val recordsRoot: Path,
    private val contentRoot: Path,
    private val contentImporter: ContentImporter,
    private val nativeSaveStore: NativeSaveStore,
    private val saveStateStore: SaveStateStore,
    private val saveStateThumbnailRoot: Path,
    private val artworkRoot: Path,
    private val nativeSaveStagingRoot: Path,
    private val saveStateStagingRoot: Path,
    private val appVersion: String,
    private val nowEpochMs: () -> Long,
) {
    private val lock = Any()
    private val automaticCheckpointStore = AutomaticCheckpointStore(appPrivateRoot.resolve("automatic-checkpoints"))

    constructor(
        appPrivateRoot: Path,
        appVersion: String = "0.1.0-foundation",
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ) : this(
        appPrivateRoot = appPrivateRoot.toAbsolutePath().normalize(),
        recordsRoot = appPrivateRoot.toAbsolutePath().normalize().resolve(LIBRARY_RECORDS_DIR),
        contentRoot = appPrivateRoot.toAbsolutePath().normalize().resolve(CONTENT_DIR),
        contentImporter = ContentImporter(appPrivateRoot.toAbsolutePath().normalize().resolve(CONTENT_DIR)),
        nativeSaveStore = NativeSaveStore(appPrivateRoot.toAbsolutePath().normalize().resolve(NATIVE_SAVES_DIR)),
        saveStateStore = SaveStateStore(appPrivateRoot.toAbsolutePath().normalize().resolve(SAVE_STATES_DIR)),
        saveStateThumbnailRoot = appPrivateRoot.toAbsolutePath().normalize().resolve(SAVE_STATE_THUMBNAILS_DIR),
        artworkRoot = appPrivateRoot.toAbsolutePath().normalize().resolve(ARTWORK_DIR),
        nativeSaveStagingRoot = appPrivateRoot.toAbsolutePath().normalize().resolve(NATIVE_SAVE_STAGING_DIR),
        saveStateStagingRoot = appPrivateRoot.toAbsolutePath().normalize().resolve(SAVE_STATE_STAGING_DIR),
        appVersion = appVersion,
        nowEpochMs = nowEpochMs,
    )

    init {
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        Files.createDirectories(appPrivateRoot)
        Files.createDirectories(recordsRoot)
        Files.createDirectories(saveStateThumbnailRoot)
        Files.createDirectories(artworkRoot)
        Files.createDirectories(nativeSaveStagingRoot)
        Files.createDirectories(saveStateStagingRoot)
        contentImporter.cleanupTemporaryImports()
        saveStateStore.cleanupTemporaryTransactions()
    }

    /** Loads only verified, structurally valid records and refreshes save recovery status. */
    fun load(): List<LibraryItem> = synchronized(lock) {
        if (!Files.isDirectory(recordsRoot)) return@synchronized emptyList()
        val records = mutableListOf<LibraryItem>()
        Files.list(recordsRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(RECORD_SUFFIX) }
                .sorted()
                    .forEach { path ->
                    readRecord(path)?.let { item ->
                        val refreshed = refreshSaveStatus(item).withDetectedCore()
                        if (refreshed != item) writeRecord(refreshed)
                        records += refreshed
                    }
                }
        }
        records.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortTitleKey })
    }

    /** Updates recency without changing content identity or private content bytes. */
    fun markPlayed(itemId: String, playedAtEpochMs: Long = nowEpochMs()) = synchronized(lock) {
        val item = requireItem(itemId)
        writeRecord(item.copy(lastPlayedAt = playedAtEpochMs))
    }

    /** Updates presentation metadata only; original source/title and content identity remain intact. */
    fun updateMetadata(itemId: String, titleAlias: String?, sortTitle: String?): LibraryItem = synchronized(lock) {
        val item = requireItem(itemId)
        val updated = item.copy(
            titleAlias = titleAlias?.trim()?.takeIf { it.isNotEmpty() },
            sortTitle = sortTitle?.trim()?.takeIf { it.isNotEmpty() },
        )
        writeRecord(updated)
        updated
    }

    fun updateTitlePreferences(itemId: String, preferences: TitlePlayerPreferences?): LibraryItem = synchronized(lock) {
        val item = requireItem(itemId)
        val updated = item.copy(titlePreferences = preferences)
        writeRecord(updated)
        updated
    }

    /** Copies user-selected artwork into app-private storage after basic image validation. */
    fun importArtwork(resolver: ContentResolver, itemId: String, uri: Uri): LibraryItem = synchronized(lock) {
        val item = requireItem(itemId)
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= MAX_ARTWORK_BYTES) { "artwork is larger than the 4 MiB limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("the artwork source could not be read")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("the selected file is not a readable image")
        require(decoded.width <= MAX_ARTWORK_DIMENSION && decoded.height <= MAX_ARTWORK_DIMENSION) {
            "artwork dimensions exceed the 4096 px limit"
        }
        decoded.recycle()
        val target = artworkRoot.resolve("${item.contentId}.img")
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(artworkRoot, ".artwork-", ".part")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveFile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        val updated = item.copy(artworkPath = target.toString())
        writeRecord(updated)
        updated
    }

    fun removeArtwork(itemId: String): LibraryItem = synchronized(lock) {
        val item = requireItem(itemId)
        item.artworkPath?.let { path ->
            val safe = Paths.get(path).toAbsolutePath().normalize()
            require(safe.startsWith(artworkRoot.toAbsolutePath().normalize())) { "artwork path escapes app storage" }
            Files.deleteIfExists(safe)
        }
        val updated = item.copy(artworkPath = null)
        writeRecord(updated)
        updated
    }

    /**
     * Re-hashes a private copy before native launch. Import-time verification
     * proves the copy was correct when created; this catches later disk
     * corruption without hashing every large title during library rendering.
     */
    fun verifyPrivateContent(itemId: String): Boolean = synchronized(lock) {
        val item = requireItem(itemId)
        val path = item.privateContentRef?.let { runCatching { safePrivatePath(it) }.getOrNull() }
            ?: return@synchronized false
        if (!Files.isRegularFile(path)) return@synchronized false
        runCatching { hashFile(path) == ContentIdentity(item.contentId, item.contentSizeBytes) }
            .getOrDefault(false)
    }

    /**
     * Re-reads the retained SAF source without writing anything. A changed
     * source is intentionally reported instead of being imported over the
     * trusted private copy.
     */
    fun verifySource(resolver: ContentResolver, itemId: String): SourceVerification = synchronized(lock) {
        val item = requireItem(itemId)
        val uri = item.sourceUri ?: return@synchronized SourceVerification(SourceVerificationStatus.Unavailable)
        val actual = runCatching {
            resolver.openInputStream(uri)?.use(::hashStream)
                ?: throw IOException("the source provider returned no readable stream")
        }.getOrNull() ?: return@synchronized SourceVerification(SourceVerificationStatus.Unavailable)
        val expected = ContentIdentity(item.contentId, item.contentSizeBytes)
        SourceVerification(
            status = if (actual == expected) SourceVerificationStatus.Matches else SourceVerificationStatus.Changed,
            expected = expected,
            actual = actual,
        )
    }

    /** Testable source boundary for the same changed-source policy. */
    fun verifySource(itemId: String, source: ContentSource): SourceVerification = synchronized(lock) {
        val item = requireItem(itemId)
        val actual = runCatching { source.openStream().use(::hashStream) }.getOrNull()
            ?: return@synchronized SourceVerification(SourceVerificationStatus.Unavailable)
        val expected = ContentIdentity(item.contentId, item.contentSizeBytes)
        SourceVerification(
            status = if (actual == expected) SourceVerificationStatus.Matches else SourceVerificationStatus.Changed,
            expected = expected,
            actual = actual,
        )
    }

    /** Imports a picker-selected document through the SAF-aware ContentImporter. */
    fun importSaf(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        cancellationSignal: CancellationSignal? = null,
        cancellationToken: ImportCancellationToken = dev.codex.libretroplatform.runtime.content.NeverCancel,
        onProgress: (ImportProgress) -> Unit = {},
    ): LibraryImportResult {
        val imported = contentImporter.importUri(
            resolver = resolver,
            uri = uri,
            cancellationSignal = cancellationSignal,
            cancellationToken = cancellationToken,
            onProgress = onProgress,
        )

        // The private copy is already durable. Retain the source grant only after
        // that verified promotion succeeds; providers may legitimately reject it.
        try {
            SafContentSource.persistReadPermission(
                resolver = resolver,
                uri = uri,
                grantedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The imported private copy remains the source of truth.
        } catch (_: UnsupportedOperationException) {
            // Some providers expose readable one-shot documents only.
        }

        return upsert(imported, fallbackDisplayName = displayName)
    }

    /** Testable/non-Android source boundary; production SAF calls use [importSaf]. */
    fun importContent(
        source: ContentSource,
        sourceUri: Uri? = null,
        cancellationToken: ImportCancellationToken = dev.codex.libretroplatform.runtime.content.NeverCancel,
        onProgress: (ImportProgress) -> Unit = {},
    ): LibraryImportResult = upsert(
        contentImporter.importContent(
            source = source,
            sourceUri = sourceUri,
            cancellationToken = cancellationToken,
            onProgress = onProgress,
        ),
        fallbackDisplayName = source.displayName ?: "Imported content",
    )

    fun writeNativeSave(
        itemId: String,
        identity: CanonicalSaveIdentity,
        payload: InputStream,
        createdAtEpochMs: Long = nowEpochMs(),
    ): NativeSaveRecord = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "native save content identity does not match the library item"
        }
        val record = nativeSaveStore.write(
            key = identity.nativeKey(),
            payload = payload,
            nowEpochMs = createdAtEpochMs,
        )
        writeRecord(item.copy(coreId = identity.coreId, saveSummary = SaveSummary.SavePresent))
        record
    }

    fun recoverNativeSave(
        itemId: String,
        identity: CanonicalSaveIdentity,
    ): NativeRecoveryResult = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "native save content identity does not match the library item"
        }
        val result = nativeSaveStore.recover(identity.nativeKey())
        if (result is NativeRecoveryResult.Available) {
            writeRecord(
                item.copy(
                    coreId = identity.coreId,
                    saveSummary = if (result.restoredFromRecovery) {
                        SaveSummary.RecoveryAvailable
                    } else {
                        SaveSummary.SavePresent
                    },
                ),
            )
        }
        result
    }

    fun writeSaveState(
        itemId: String,
        identity: CanonicalSaveIdentity,
        slot: String,
        payload: InputStream,
        createdAtEpochMs: Long = nowEpochMs(),
    ): SaveStateMetadata = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "save-state content identity does not match the library item"
        }
        val metadata = saveStateStore.write(
            slot = slot,
            identity = identity.stateIdentity(),
            appVersion = appVersion,
            payload = payload,
            createdAtEpochMs = createdAtEpochMs,
        )
        writeRecord(item.copy(coreId = identity.coreId, saveSummary = SaveSummary.SavePresent))
        metadata
    }

    fun readSaveState(
        itemId: String,
        identity: CanonicalSaveIdentity,
        slot: String,
    ): SaveStateReadResult = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "save-state content identity does not match the library item"
        }
        saveStateStore.read(slot, identity.stateIdentity())
    }

    /** Returns only verified slot metadata for the active title. */
    fun readSaveStateSlots(
        itemId: String,
        identity: CanonicalSaveIdentity,
        slots: IntRange = 1..9,
    ): List<SaveStateSlotStatus> = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "save-state content identity does not match the library item"
        }
        slots.map { slotNumber ->
            val result = saveStateStore.read("slot-$slotNumber", identity.stateIdentity())
            val state = (result as? SaveStateReadResult.Success)?.state
            SaveStateSlotStatus(
                slot = slotNumber,
                availability = when (result) {
                    is SaveStateReadResult.Success -> SaveStateSlotAvailability.Verified
                    SaveStateReadResult.Corrupt -> SaveStateSlotAvailability.Corrupt
                    is SaveStateReadResult.IdentityMismatch -> SaveStateSlotAvailability.IdentityMismatch
                    SaveStateReadResult.Missing -> SaveStateSlotAvailability.Empty
                },
                createdAtEpochMs = state?.metadata?.createdAtEpochMs,
                payloadSizeBytes = state?.metadata?.payloadSizeBytes,
                thumbnailPath = if (result is SaveStateReadResult.Success) {
                    saveStateThumbnailPath(identity, "slot-$slotNumber")
                        .takeIf(Files::isRegularFile)
                        ?.toString()
                } else {
                    null
                },
            )
        }
    }

    /** Exports a verified native save without changing the canonical copy. */
    fun exportNativeSave(
        itemId: String,
        identity: CanonicalSaveIdentity,
        target: ExternalSaveTarget,
    ): ExportedSave = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "native save content identity does not match the library item"
        }
        nativeSaveStore.exportVerified(identity.nativeKey(), target)
    }

    /** Exports a verified state payload without changing the canonical copy. */
    fun exportSaveState(
        itemId: String,
        identity: CanonicalSaveIdentity,
        slot: String,
        target: ExternalSaveTarget,
    ): ExportedSave = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "save-state content identity does not match the library item"
        }
        saveStateStore.exportVerified(slot, identity.stateIdentity(), target)
    }

    /** Returns an app-private file that the native runtime may populate. */
    fun nativeSaveStagingPath(itemId: String, coreId: String): Path = synchronized(lock) {
        val item = requireItem(itemId)
        require(coreId == item.coreId || item.coreId == null) {
            "native save core identity does not match the library item"
        }
        val path = SavePathPolicy.resolveInside(
            nativeSaveStagingRoot,
            Paths.get(item.contentId, SavePathPolicy.requireComponent(coreId, "coreId"), STAGING_FILE).toString(),
        )
        Files.createDirectories(path.parent)
        path
    }

    /** Returns the slot-specific app-private file used for native state transfer. */
    fun saveStateStagingPath(itemId: String, coreId: String, slot: String): Path = synchronized(lock) {
        val item = requireItem(itemId)
        require(coreId == item.coreId || item.coreId == null) {
            "save-state core identity does not match the library item"
        }
        val path = SavePathPolicy.resolveInside(
            saveStateStagingRoot,
            Paths.get(
                item.contentId,
                SavePathPolicy.requireComponent(coreId, "coreId"),
                SavePathPolicy.requireComponent(slot, "slot"),
                STAGING_FILE,
            ).toString(),
        )
        Files.createDirectories(path.parent)
        path
    }

    /** Verifies the native staging payload and promotes it into the canonical transactional store. */
    fun commitNativeSaveFromStaging(
        itemId: String,
        identity: CanonicalSaveIdentity,
        createdAtEpochMs: Long = nowEpochMs(),
    ): NativeSaveRecord = synchronized(lock) {
        val staging = nativeSaveStagingPath(itemId, identity.coreId)
        require(Files.isRegularFile(staging)) { "native runtime did not produce a save payload" }
        val record = Files.newInputStream(staging).use { input ->
            writeNativeSave(itemId, identity, input, createdAtEpochMs)
        }
        Files.deleteIfExists(staging)
        record
    }

    /** Stages a verified canonical native save for the next core session. */
    fun stageNativeSaveForRuntime(
        itemId: String,
        identity: CanonicalSaveIdentity,
    ): Boolean = synchronized(lock) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId) {
            "native save content identity does not match the library item"
        }
        val recovered = nativeSaveStore.recover(identity.nativeKey())
        if (recovered !is NativeRecoveryResult.Available) return@synchronized false
        val target = nativeSaveStagingPath(itemId, identity.coreId)
        stageCopy(recovered.record.path, target)
        true
    }

    /** Verifies the save-state staging payload and promotes it into the canonical store. */
    fun commitSaveStateFromStaging(
        itemId: String,
        identity: CanonicalSaveIdentity,
        slot: String,
        stagingSlot: String = slot,
        thumbnailPng: ByteArray? = null,
        createdAtEpochMs: Long = nowEpochMs(),
    ): SaveStateMetadata = synchronized(lock) {
        val staging = saveStateStagingPath(itemId, identity.coreId, stagingSlot)
        require(Files.isRegularFile(staging)) { "native runtime did not produce a save-state payload" }
        val metadata = Files.newInputStream(staging).use { input ->
            writeSaveState(itemId, identity, slot, input, createdAtEpochMs)
        }
        Files.deleteIfExists(staging)
        writeSaveStateThumbnail(identity, slot, thumbnailPng)
        metadata
    }

    /** Copies a verified canonical state into a fresh runtime staging payload for loading. */
    fun stageSaveStateForRuntime(
        itemId: String,
        identity: CanonicalSaveIdentity,
        slot: String,
        stagingSlot: String = slot,
    ): Boolean = synchronized(lock) {
        val verified = readSaveState(itemId, identity, slot)
        if (verified !is SaveStateReadResult.Success) return@synchronized false
        val target = saveStateStagingPath(itemId, identity.coreId, stagingSlot)
        stageCopy(verified.state.payloadPath, target)
        true
    }

    fun readAutomaticCheckpoints(itemId: String, identity: CanonicalSaveIdentity): List<AutomaticCheckpoint> =
        synchronized(lock) {
            requireCheckpointIdentity(itemId, identity)
            automaticCheckpointStore.list(identity.stateIdentity())
        }

    /** Discards a prior transfer before asking the runtime for fresh serialized bytes. */
    fun prepareSaveStateCapture(itemId: String, identity: CanonicalSaveIdentity, stagingSlot: String) =
        synchronized(lock) {
            requireCheckpointIdentity(itemId, identity)
            Files.deleteIfExists(saveStateStagingPath(itemId, identity.coreId, stagingSlot))
            Unit
        }

    fun commitAutomaticCheckpointFromStaging(
        itemId: String,
        identity: CanonicalSaveIdentity,
        stagingSlot: String,
        thumbnailPng: ByteArray? = null,
        createdAtEpochMs: Long = nowEpochMs(),
    ): AutomaticCheckpoint = synchronized(lock) {
        requireCheckpointIdentity(itemId, identity)
        val staging = saveStateStagingPath(itemId, identity.coreId, stagingSlot)
        require(Files.isRegularFile(staging) && Files.size(staging) > 0) {
            "runtime did not produce a nonempty automatic checkpoint"
        }
        val checkpoint = Files.newInputStream(staging).use { payload ->
            automaticCheckpointStore.write(identity.stateIdentity(), appVersion, payload, createdAtEpochMs, thumbnailPng)
        }
        Files.deleteIfExists(staging)
        checkpoint
    }

    /** Null id selects the newest valid entry, skipping corrupt or incompatible history. */
    fun stageAutomaticCheckpointForRuntime(
        itemId: String,
        identity: CanonicalSaveIdentity,
        stagingSlot: String,
        id: String? = null,
    ): AutomaticCheckpoint? = synchronized(lock) {
        requireCheckpointIdentity(itemId, identity)
        val target = saveStateStagingPath(itemId, identity.coreId, stagingSlot)
        Files.deleteIfExists(target)
        val (checkpoint, verified) = automaticCheckpointStore.read(identity.stateIdentity(), id)
            ?: return@synchronized null
        stageCopy(verified.payloadPath, target)
        checkpoint
    }

    private fun requireCheckpointIdentity(itemId: String, identity: CanonicalSaveIdentity) {
        val item = requireItem(itemId)
        require(identity.contentSha256 == item.contentId && identity.coreId == item.coreId) {
            "checkpoint content or core identity does not match the library item"
        }
    }

    /** Removes only app-owned metadata and content; the original SAF source is untouched. */
    fun remove(itemId: String) = synchronized(lock) {
        val item = requireItem(itemId)
        Files.deleteIfExists(recordPath(item.contentId))
        val privatePath = item.privateContentRef?.let(::safePrivatePath)
        if (privatePath != null && Files.isRegularFile(privatePath)) {
            Files.deleteIfExists(privatePath)
        }
        nativeSaveStore.deleteForContent(item.contentId)
        saveStateStore.deleteForContent(item.contentId)
        automaticCheckpointStore.deleteForContent(item.contentId)
        deleteRecursively(saveStateThumbnailRoot.resolve(item.contentId))
        deleteRecursively(nativeSaveStagingRoot.resolve(item.contentId))
        deleteRecursively(saveStateStagingRoot.resolve(item.contentId))
    }

    private fun upsert(imported: ImportedContent, fallbackDisplayName: String): LibraryImportResult = synchronized(lock) {
        val contentId = imported.identity.sha256
        val existing = readRecord(recordPath(contentId))
        val displayName = imported.displayName ?: fallbackDisplayName
        val existingPath = existing?.privateContentRef
            ?.let { runCatching { safePrivatePath(it) }.getOrNull() }
            ?.takeIf { Files.isRegularFile(it) }
        val canonicalPath = existingPath ?: imported.canonicalPath
        val support = supportFor(displayName)
        val item = existing?.copy(
            sourceUri = imported.sourceUri ?: existing.sourceUri,
            sourceDisplayName = displayName,
            privateContentRef = canonicalPath.toString(),
            contentSizeBytes = imported.identity.sizeBytes,
            system = if (existing.system == "Unknown system") support.system else existing.system,
            coreId = existing.coreId ?: support.coreId,
            supportStatus = if (existing.supportStatus == SupportStatus.NeedsCore) support.status else existing.supportStatus,
        ) ?: LibraryItem(
            id = contentId,
            contentId = contentId,
            contentSizeBytes = imported.identity.sizeBytes,
            title = titleFrom(displayName),
            system = support.system,
            sourceUri = imported.sourceUri,
            sourceDisplayName = displayName,
            privateContentRef = imported.canonicalPath.toString(),
            coreId = support.coreId,
            metadataStatus = MetadataStatus.Partial,
            supportStatus = support.status,
            importedAt = nowEpochMs(),
        )
        writeRecord(item)
        if (existing != null && imported.canonicalPath != canonicalPath) {
            Files.deleteIfExists(imported.canonicalPath)
        }
        LibraryImportResult(item = item, created = existing == null)
    }

    private fun refreshSaveStatus(item: LibraryItem): LibraryItem {
        val coreId = item.coreId ?: return item.withReadableContentStatus()
        val result = nativeSaveStore.recover(SaveKey(item.contentId, coreId))
        val summary = when (result) {
            is NativeRecoveryResult.Available -> if (result.restoredFromRecovery) {
                SaveSummary.RecoveryAvailable
            } else {
                SaveSummary.SavePresent
            }
            // A state-only save, or a previous recovery result, is still a
            // durable repository fact even when no native RAM payload exists.
            NativeRecoveryResult.MissingOrCorrupt -> item.saveSummary
        }
        val readable = item.withReadableContentStatus()
        return readable.copy(saveSummary = if (
            item.saveSummary == SaveSummary.RecoveryAvailable &&
            result is NativeRecoveryResult.Available
        ) {
            SaveSummary.RecoveryAvailable
        } else {
            summary
        })
    }

    private fun LibraryItem.withReadableContentStatus(): LibraryItem {
        val path = privateContentRef?.let { runCatching { safePrivatePath(it) }.getOrNull() }
        return if (path != null && Files.isRegularFile(path)) {
            this
        } else {
            copy(supportStatus = SupportStatus.Unreadable)
        }
    }

    private fun LibraryItem.withDetectedCore(): LibraryItem {
        if (coreId != null || supportStatus != SupportStatus.NeedsCore) return this
        val support = supportFor(sourceDisplayName)
        return copy(
            system = if (system == "Unknown system") support.system else system,
            coreId = support.coreId,
            supportStatus = support.status,
        )
    }

    private data class SupportHint(
        val system: String,
        val coreId: String?,
        val status: SupportStatus,
    )

    private fun supportFor(displayName: String): SupportHint {
        val profile = ConsoleCatalog.forFileName(displayName)
        return if (profile != null) {
            SupportHint(profile.displayName, profile.coreId, profile.status)
        } else {
            SupportHint("Unknown system", null, SupportStatus.NeedsCore)
        }
    }

    private fun requireItem(itemId: String): LibraryItem =
        readRecord(recordPath(itemId)) ?: throw IOException("library item is not available: $itemId")

    private fun hashFile(path: Path): ContentIdentity {
        return Files.newInputStream(path).use(::hashStream)
    }

    private fun hashStream(input: InputStream): ContentIdentity {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            size += count
        }
        return ContentIdentity(sha256 = digest.digest().toLowerHex(), sizeBytes = size)
    }

    private fun ByteArray.toLowerHex(): String {
        val digits = "0123456789abcdef"
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(output)
    }

    private fun recordPath(contentId: String): Path {
        require(contentId.matches(SHA_256)) { "content identity must be a lowercase SHA-256 digest" }
        return recordsRoot.resolve("$contentId$RECORD_SUFFIX")
    }

    private fun safePrivatePath(value: String): Path {
        val candidate = Paths.get(value).toAbsolutePath().normalize()
        val normalizedContentRoot = contentRoot.toAbsolutePath().normalize()
        require(candidate.startsWith(normalizedContentRoot)) { "private content path escapes content root" }
        return candidate
    }

    private fun readRecord(path: Path): LibraryItem? {
        if (!Files.isRegularFile(path)) return null
        return try {
            val properties = Properties()
            Files.newInputStream(path).use { properties.load(it) }
            require(properties.getProperty(KEY_VERSION)?.toIntOrNull() == RECORD_VERSION) {
                "unsupported library record version"
            }
            val contentId = properties.getProperty(KEY_CONTENT_ID) ?: return null
            val item = LibraryItem(
                id = properties.getProperty(KEY_ID) ?: contentId,
                contentId = contentId,
                contentSizeBytes = properties.getProperty(KEY_CONTENT_SIZE)?.toLongOrNull() ?: return null,
                title = properties.getProperty(KEY_TITLE) ?: return null,
                system = properties.getProperty(KEY_SYSTEM) ?: return null,
                sourceUri = properties.getProperty(KEY_SOURCE_URI)?.takeIf { it.isNotEmpty() }?.let(Uri::parse),
                sourceDisplayName = properties.getProperty(KEY_SOURCE_NAME) ?: return null,
                privateContentRef = properties.getProperty(KEY_PRIVATE_CONTENT_REF)?.takeIf { it.isNotEmpty() },
                coreId = properties.getProperty(KEY_CORE_ID)?.takeIf { it.isNotEmpty() },
                metadataStatus = MetadataStatus.valueOf(properties.getProperty(KEY_METADATA_STATUS) ?: return null),
                supportStatus = SupportStatus.valueOf(properties.getProperty(KEY_SUPPORT_STATUS) ?: return null),
                importedAt = properties.getProperty(KEY_IMPORTED_AT)?.toLongOrNull() ?: return null,
                lastPlayedAt = properties.getProperty(KEY_LAST_PLAYED_AT)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
                saveSummary = SaveSummary.valueOf(properties.getProperty(KEY_SAVE_SUMMARY) ?: return null),
                titleAlias = properties.getProperty(KEY_TITLE_ALIAS)?.takeIf { it.isNotEmpty() },
                sortTitle = properties.getProperty(KEY_SORT_TITLE)?.takeIf { it.isNotEmpty() },
                artworkPath = properties.getProperty(KEY_ARTWORK_PATH)?.takeIf { it.isNotEmpty() },
                titlePreferences = TitlePlayerPreferencesCodec.decode(properties.getProperty(KEY_TITLE_PREFERENCES)),
            )
            require(item.id == item.contentId) { "library identity is not canonical" }
            require(item.contentId.matches(SHA_256)) { "library content identity is invalid" }
            require(item.contentSizeBytes >= 0L) { "library content size is invalid" }
            item.privateContentRef?.let(::safePrivatePath)
            item.artworkPath?.let(::safeArtworkPath)
            item
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRecord(item: LibraryItem) {
        require(item.id == item.contentId) { "library item id must equal verified content identity" }
        val properties = Properties()
        properties.setProperty(KEY_VERSION, RECORD_VERSION.toString())
        properties.setProperty(KEY_ID, item.id)
        properties.setProperty(KEY_CONTENT_ID, item.contentId)
        properties.setProperty(KEY_CONTENT_SIZE, item.contentSizeBytes.toString())
        properties.setProperty(KEY_TITLE, item.title)
        properties.setProperty(KEY_SYSTEM, item.system)
        properties.setProperty(KEY_SOURCE_URI, item.sourceUri?.toString().orEmpty())
        properties.setProperty(KEY_SOURCE_NAME, item.sourceDisplayName)
        properties.setProperty(KEY_PRIVATE_CONTENT_REF, item.privateContentRef.orEmpty())
        properties.setProperty(KEY_CORE_ID, item.coreId.orEmpty())
        properties.setProperty(KEY_METADATA_STATUS, item.metadataStatus.name)
        properties.setProperty(KEY_SUPPORT_STATUS, item.supportStatus.name)
        properties.setProperty(KEY_IMPORTED_AT, item.importedAt.toString())
        properties.setProperty(KEY_LAST_PLAYED_AT, item.lastPlayedAt?.toString().orEmpty())
        properties.setProperty(KEY_SAVE_SUMMARY, item.saveSummary.name)
        properties.setProperty(KEY_TITLE_ALIAS, item.titleAlias.orEmpty())
        properties.setProperty(KEY_SORT_TITLE, item.sortTitle.orEmpty())
        properties.setProperty(KEY_ARTWORK_PATH, item.artworkPath.orEmpty())
        properties.setProperty(KEY_TITLE_PREFERENCES, TitlePlayerPreferencesCodec.encode(item.titlePreferences))

        val target = recordPath(item.contentId)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(recordsRoot, ".record-", ".part")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            moveFile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveFile(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun stageCopy(source: Path, target: Path) {
        val temporary = target.resolveSibling(".${target.fileName}.part")
        Files.deleteIfExists(temporary)
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveFile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** Thumbnails are optional UI metadata and never affect save verification. */
    private fun writeSaveStateThumbnail(
        identity: CanonicalSaveIdentity,
        slot: String,
        thumbnailPng: ByteArray?,
    ) {
        val target = saveStateThumbnailPath(identity, slot)
        var temporary: Path? = null
        try {
            if (thumbnailPng == null || thumbnailPng.isEmpty() || thumbnailPng.size > MAX_SAVE_STATE_THUMBNAIL_BYTES) {
                Files.deleteIfExists(target)
                return
            }
            Files.createDirectories(target.parent)
            temporary = Files.createTempFile(target.parent, ".thumbnail-", ".part")
            FileOutputStream(requireNotNull(temporary).toFile()).use { output ->
                output.write(thumbnailPng)
                output.fd.sync()
            }
            moveFile(requireNotNull(temporary), target)
        } catch (_: Exception) {
            // The canonical save has already been verified and promoted. A
            // thumbnail is recoverable presentation metadata, so filesystem
            // failures here must never make the save operation fail.
            runCatching { temporary?.let(Files::deleteIfExists) }
            runCatching { Files.deleteIfExists(target) }
        } finally {
            runCatching { temporary?.let(Files::deleteIfExists) }
        }
    }

    private fun saveStateThumbnailPath(identity: CanonicalSaveIdentity, slot: String): Path =
        SavePathPolicy.resolveInside(
            saveStateThumbnailRoot,
            Paths.get(
                SavePathPolicy.requireComponent(identity.contentSha256, "contentSha256"),
                SavePathPolicy.requireComponent(identity.coreId, "coreId"),
                "${SavePathPolicy.requireComponent(slot, "slot")}.png",
            ).toString(),
        )

    private fun titleFrom(displayName: String): String {
        val fileName = displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "Imported content" }
        return fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
    }

    private fun safeArtworkPath(value: String): Path {
        val candidate = Paths.get(value).toAbsolutePath().normalize()
        require(candidate.startsWith(artworkRoot.toAbsolutePath().normalize())) { "artwork path escapes artwork root" }
        return candidate
    }

    data class LibraryImportResult(
        val item: LibraryItem,
        val created: Boolean,
    )

    data class CanonicalSaveIdentity(
        val contentSha256: String,
        val coreId: String,
        val coreVersion: String,
        val stateFormatVersion: Int,
    ) {
        fun nativeKey(): SaveKey = SaveKey(contentSha256, coreId)

        fun stateIdentity(): SaveStateIdentity = SaveStateIdentity(
            contentSha256 = contentSha256,
            coreId = coreId,
            coreVersion = coreVersion,
            stateFormatVersion = stateFormatVersion,
        )
    }

    companion object {
        private const val RECORD_VERSION = 1
        private const val CONTENT_DIR = "content"
        private const val LIBRARY_RECORDS_DIR = "library/records"
        private const val NATIVE_SAVES_DIR = "saves"
        private const val SAVE_STATES_DIR = "save-states"
        private const val SAVE_STATE_THUMBNAILS_DIR = "save-state-thumbnails"
        private const val ARTWORK_DIR = "artwork"
        private const val NATIVE_SAVE_STAGING_DIR = "runtime/native-save-staging"
        private const val SAVE_STATE_STAGING_DIR = "runtime/save-state-staging"
        private const val MAX_SAVE_STATE_THUMBNAIL_BYTES = 2 * 1024 * 1024
        private const val STAGING_FILE = "payload.bin"
        private const val RECORD_SUFFIX = ".properties"
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private const val KEY_VERSION = "recordVersion"
        private const val KEY_ID = "id"
        private const val KEY_CONTENT_ID = "contentId"
        private const val KEY_CONTENT_SIZE = "contentSizeBytes"
        private const val KEY_TITLE = "title"
        private const val KEY_SYSTEM = "system"
        private const val KEY_SOURCE_URI = "sourceUri"
        private const val KEY_SOURCE_NAME = "sourceDisplayName"
        private const val KEY_PRIVATE_CONTENT_REF = "privateContentRef"
        private const val KEY_CORE_ID = "coreId"
        private const val KEY_METADATA_STATUS = "metadataStatus"
        private const val KEY_SUPPORT_STATUS = "supportStatus"
        private const val KEY_IMPORTED_AT = "importedAt"
        private const val KEY_LAST_PLAYED_AT = "lastPlayedAt"
        private const val KEY_SAVE_SUMMARY = "saveSummary"
        private const val KEY_TITLE_ALIAS = "titleAlias"
        private const val KEY_SORT_TITLE = "sortTitle"
        private const val KEY_ARTWORK_PATH = "artworkPath"
        private const val KEY_TITLE_PREFERENCES = "titlePreferences"
        private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
        private const val MAX_ARTWORK_DIMENSION = 4096

        fun fromContext(
            context: Context,
            appVersion: String = "0.1.0-foundation",
            nowEpochMs: () -> Long = System::currentTimeMillis,
        ): LibraryRepository = LibraryRepository(
            appPrivateRoot = context.filesDir.toPath(),
            appVersion = appVersion,
            nowEpochMs = nowEpochMs,
        )
    }
}
