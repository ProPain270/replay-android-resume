package dev.codex.libretroplatform

import dev.codex.libretroplatform.runtime.saves.SaveStateIdentity
import dev.codex.libretroplatform.runtime.saves.SaveStateMetadata
import dev.codex.libretroplatform.runtime.saves.SaveStateReadResult
import dev.codex.libretroplatform.runtime.saves.SaveStateStore
import dev.codex.libretroplatform.runtime.saves.VerifiedSaveState
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** A verified automatic entry, not one of the user's nine manual slots. */
data class AutomaticCheckpoint(
    val id: String,
    val slot: Int,
    val createdAtEpochMs: Long,
    val payloadSizeBytes: Long,
    val thumbnailPath: String? = null,
)

/**
 * Three rotating slots in a dedicated root, each with SaveStateStore's bounded
 * transactional recovery generation. No index is needed to recover after a crash.
 * All callers share this instance (the repository also serializes staging I/O).
 */
internal class AutomaticCheckpointStore(private val root: Path) {
    private val states = SaveStateStore(root)
    private val lock = Any()

    init {
        states.cleanupTemporaryTransactions()
    }

    fun list(identity: SaveStateIdentity): List<AutomaticCheckpoint> = synchronized(lock) {
        verified(identity).map { (slot, state) -> entry(slot, state.metadata) }
    }

    fun write(
        identity: SaveStateIdentity,
        appVersion: String,
        payload: InputStream,
        createdAtEpochMs: Long,
        thumbnailPng: ByteArray? = null,
    ): AutomaticCheckpoint = synchronized(lock) {
        val existing = verified(identity)
        val occupied = existing.map { it.first }.toSet()
        val slot = (1..HISTORY_SIZE).firstOrNull { it !in occupied } ?: existing.last().first
        // Strict ordering survives equal or backwards-moving wall clocks, without an index.
        val latestTime = existing.firstOrNull()?.second?.metadata?.createdAtEpochMs
        require(latestTime != Long.MAX_VALUE) { "checkpoint timestamp exhausted" }
        val timestamp = maxOf(createdAtEpochMs.coerceAtLeast(0), latestTime?.plus(1) ?: 0)
        val metadata = states.write(slotName(slot), identity, appVersion, payload, timestamp)
        writeThumbnail(slot, metadata, thumbnailPng)
        entry(slot, metadata)
    }

    /** Re-verifies the payload and full core/content/format identity at restore time. */
    fun read(identity: SaveStateIdentity, id: String? = null): Pair<AutomaticCheckpoint, VerifiedSaveState>? =
        synchronized(lock) {
            verified(identity).firstNotNullOfOrNull { (slot, state) ->
                val checkpoint = entry(slot, state.metadata)
                if (id == null || checkpoint.id == id) checkpoint to state else null
            }
        }

    fun deleteForContent(contentSha256: String) = synchronized(lock) {
        states.deleteForContent(contentSha256)
        deleteTree(root.resolve("thumbnails").resolve(contentSha256))
    }

    private fun verified(identity: SaveStateIdentity): List<Pair<Int, VerifiedSaveState>> =
        (1..HISTORY_SIZE).mapNotNull { slot ->
            (states.read(slotName(slot), identity) as? SaveStateReadResult.Success)
                ?.state?.takeIf { it.metadata.payloadSizeBytes > 0 }?.let { slot to it }
        }.sortedWith(compareByDescending<Pair<Int, VerifiedSaveState>> { it.second.metadata.createdAtEpochMs }
            .thenBy { it.first })

    private fun entry(slot: Int, metadata: SaveStateMetadata): AutomaticCheckpoint {
        val id = entryId(slot, metadata)
        val directory = thumbnailDirectory(slot, metadata.identity)
        val thumbnail = directory.resolve("preview.png")
        // A recovered previous generation must not display the failed generation's preview.
        val matches = runCatching {
            Files.isRegularFile(thumbnail) && String(Files.readAllBytes(directory.resolve("state-id")), Charsets.UTF_8) == id
        }.getOrDefault(false)
        return AutomaticCheckpoint(id, slot, metadata.createdAtEpochMs, metadata.payloadSizeBytes,
            thumbnail.takeIf { matches }?.toString())
    }

    private fun entryId(slot: Int, metadata: SaveStateMetadata): String {
        val identity = metadata.identity
        val identityHash = MessageDigest.getInstance("SHA-256").digest(
            "${identity.contentSha256}\u0000${identity.coreId}\u0000${identity.coreVersion}\u0000${identity.stateFormatVersion}".toByteArray(),
        ).joinToString("") { "%02x".format(it) }
        return "automatic-$slot-$identityHash-${metadata.createdAtEpochMs}-${metadata.payloadSha256}"
    }

    private fun thumbnailDirectory(slot: Int, identity: SaveStateIdentity): Path =
        root.resolve("thumbnails").resolve(identity.contentSha256).resolve(identity.coreId).resolve(slotName(slot))

    private fun writeThumbnail(slot: Int, metadata: SaveStateMetadata, png: ByteArray?) {
        val directory = thumbnailDirectory(slot, metadata.identity)
        // Best effort only. Remove the identity marker first so interrupted writes cannot
        // label a partial or older image as the current checkpoint.
        runCatching {
            Files.deleteIfExists(directory.resolve("state-id"))
            Files.deleteIfExists(directory.resolve("preview.png"))
            if (png == null || png.isEmpty() || png.size > MAX_THUMBNAIL_BYTES) return@runCatching
            Files.createDirectories(directory)
            FileOutputStream(directory.resolve("preview.png").toFile()).use { it.write(png); it.fd.sync() }
            FileOutputStream(directory.resolve("state-id").toFile()).use {
                it.write(entryId(slot, metadata).toByteArray()); it.fd.sync()
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        const val HISTORY_SIZE = 3
        private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
        private fun slotName(slot: Int) = "automatic-$slot"
    }
}
