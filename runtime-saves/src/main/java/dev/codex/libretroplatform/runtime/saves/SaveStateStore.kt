package dev.codex.libretroplatform.runtime.saves

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Stores core/version/content-bound save states with verifiable sidecar metadata. */
class SaveStateStore(
    private val root: Path,
) {
    private val lock = Any()

    init {
        Files.createDirectories(root)
    }

    fun payloadPath(slot: String, identity: SaveStateIdentity): Path =
        stateDirectory(slot, identity).resolve(CURRENT_DIR).resolve(DATA_FILE)

    fun write(
        slot: String,
        identity: SaveStateIdentity,
        appVersion: String,
        payload: InputStream,
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): SaveStateMetadata {
        SavePathPolicy.requireComponent(slot, "slot")
        synchronized(lock) {
            val base = stateDirectory(slot, identity)
            val transactions = base.resolve(".transactions")
            Files.createDirectories(transactions)
            val transaction = transactions.resolve(UUID.randomUUID().toString())
            Files.createDirectories(transaction)
            try {
                val data = transaction.resolve(DATA_FILE)
                val digestAndSize = payload.use { copyAndHash(it, data) }
                val metadata = SaveStateMetadata(
                    identity = identity,
                    appVersion = appVersion,
                    createdAtEpochMs = createdAtEpochMs,
                    payloadSha256 = digestAndSize.sha256,
                    payloadSizeBytes = digestAndSize.sizeBytes,
                )
                writeMetadata(transaction.resolve(METADATA_FILE), metadata)
                if (readVerified(transaction) == null) {
                    throw SaveStateException("prepared save state failed verification")
                }

                val current = base.resolve(CURRENT_DIR)
                val previous = base.resolve(PREVIOUS_DIR)
                if (Files.exists(previous)) deleteRecursively(previous)
                if (Files.exists(current)) moveDirectory(current, previous)
                moveDirectory(transaction, current)
                if (readVerified(current) == null) {
                    throw SaveStateException("committed save state failed verification")
                }
                return metadata
            } catch (failure: SaveStateException) {
                throw failure
            } catch (failure: IOException) {
                throw SaveStateException("save-state transaction failed", failure)
            } finally {
                deleteRecursively(transaction)
            }
        }
    }

    fun read(slot: String, expected: SaveStateIdentity): SaveStateReadResult {
        SavePathPolicy.requireComponent(slot, "slot")
        synchronized(lock) {
            val base = stateDirectory(slot, expected)
            val current = base.resolve(CURRENT_DIR)
            val verified = readVerified(current)
            if (verified != null) return compareIdentity(verified, expected)

            // Versions before identity-scoped state directories stored one
            // global slot path. Read it only when its metadata matches, then
            // promote it into the collision-safe layout.
            if (!Files.exists(base)) {
                val legacy = legacySlotDirectory(slot)
                val legacyCurrent = legacy.resolve(CURRENT_DIR)
                val legacyState = readVerified(legacyCurrent)
                if (legacyState != null) {
                    val legacyResult = compareIdentity(legacyState, expected)
                    if (legacyResult is SaveStateReadResult.Success) {
                        moveDirectory(legacy, base)
                        return compareIdentity(
                            legacyResult.state.copy(payloadPath = base.resolve(CURRENT_DIR).resolve(DATA_FILE)),
                            expected,
                        )
                    }
                    return legacyResult
                }
            }

            // A corrupt current state can be replaced by a verified previous state.
            val previous = readVerified(base.resolve(PREVIOUS_DIR))
            if (previous != null) {
                moveDirectory(base.resolve(PREVIOUS_DIR), current)
                return compareIdentity(previous.copy(payloadPath = current.resolve(DATA_FILE)), expected)
            }

            return if (!Files.exists(current) && !Files.exists(base.resolve(PREVIOUS_DIR))) {
                SaveStateReadResult.Missing
            } else {
                SaveStateReadResult.Corrupt
            }
        }
    }

    /** Exports only a verified state payload; the app-private state remains canonical. */
    fun exportVerified(slot: String, identity: SaveStateIdentity, target: ExternalSaveTarget): ExportedSave {
        SavePathPolicy.requireComponent(slot, "slot")
        synchronized(lock) {
            val state = read(slot, identity)
            val verified = (state as? SaveStateReadResult.Success)?.state
                ?: throw SaveExportVerificationException("no verified save state is available")
            try {
                target.openOutputStream().use { output ->
                    Files.newInputStream(verified.payloadPath).use { input -> input.copyTo(output) }
                    output.flush()
                }
                val external = hashStream(target.openInputStream())
                if (external.sha256 != verified.metadata.payloadSha256 ||
                    external.sizeBytes != verified.metadata.payloadSizeBytes
                ) {
                    throw SaveExportVerificationException("external save-state verification failed")
                }
                return ExportedSave(external.sha256, external.sizeBytes)
            } catch (failure: SaveExportVerificationException) {
                throw failure
            } catch (_: IOException) {
                throw SaveExportVerificationException("external save-state export failed")
            }
        }
    }

    fun cleanupTemporaryTransactions(): Int = synchronized(lock) {
        var deleted = 0
        if (!Files.exists(root)) return@synchronized deleted
        val transactionRoots = ArrayList<Path>()
        Files.walk(root).use { stream ->
            stream.filter { it.fileName.toString() == ".transactions" && Files.isDirectory(it) }
                .forEach(transactionRoots::add)
        }
        transactionRoots.forEach { transactionRoot ->
            Files.newDirectoryStream(transactionRoot).use { entries ->
                entries.forEach {
                    deleteRecursively(it)
                    deleted++
                }
            }
            Files.deleteIfExists(transactionRoot)
        }
        deleted
    }

    /** Removes all identity-scoped state generations for one content identity. */
    fun deleteForContent(contentSha256: String) {
        require(contentSha256.matches(SHA_256)) { "contentSha256 must be a lowercase SHA-256 digest" }
        synchronized(lock) {
            deleteRecursively(root.resolve(STATES_DIR).resolve(contentSha256))
        }
    }

    private fun compareIdentity(state: VerifiedSaveState, expected: SaveStateIdentity): SaveStateReadResult {
        return if (state.metadata.identity == expected) {
            SaveStateReadResult.Success(state)
        } else {
            SaveStateReadResult.IdentityMismatch(expected, state.metadata.identity)
        }
    }

    private fun stateDirectory(slot: String, identity: SaveStateIdentity): Path {
        val relative = Paths.get(
            STATES_DIR,
            identity.contentSha256,
            SavePathPolicy.requireComponent(identity.coreId, "coreId"),
            SavePathPolicy.requireComponent(slot, "slot"),
        )
        return SavePathPolicy.resolveInside(root, relative.toString())
    }

    private fun legacySlotDirectory(slot: String): Path {
        val relative = Paths.get(STATES_DIR, SavePathPolicy.requireComponent(slot, "slot"))
        return SavePathPolicy.resolveInside(root, relative.toString())
    }

    private fun readVerified(directory: Path): VerifiedSaveState? {
        val data = directory.resolve(DATA_FILE)
        val metadataPath = directory.resolve(METADATA_FILE)
        if (!Files.isRegularFile(data) || !Files.isRegularFile(metadataPath)) return null
        return try {
            val metadata = readMetadata(metadataPath) ?: return null
            val actual = hashFile(data)
            if (actual.sha256 != metadata.payloadSha256 || actual.sizeBytes != metadata.payloadSizeBytes) {
                return null
            }
            VerifiedSaveState(metadata, data)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetadata(path: Path, metadata: SaveStateMetadata) {
        val properties = Properties()
        properties.setProperty("contentSha256", metadata.identity.contentSha256)
        properties.setProperty("coreId", metadata.identity.coreId)
        properties.setProperty("coreVersion", metadata.identity.coreVersion)
        properties.setProperty("stateFormatVersion", metadata.identity.stateFormatVersion.toString())
        properties.setProperty("appVersion", metadata.appVersion)
        properties.setProperty("createdAtEpochMs", metadata.createdAtEpochMs.toString())
        properties.setProperty("payloadSha256", metadata.payloadSha256)
        properties.setProperty("payloadSizeBytes", metadata.payloadSizeBytes.toString())
        FileOutputStream(path.toFile()).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
    }

    private fun readMetadata(path: Path): SaveStateMetadata? {
        val properties = Properties()
        Files.newInputStream(path).use { properties.load(it) }
        val contentSha = properties.getProperty("contentSha256") ?: return null
        val coreId = properties.getProperty("coreId") ?: return null
        val coreVersion = properties.getProperty("coreVersion") ?: return null
        val format = properties.getProperty("stateFormatVersion")?.toIntOrNull() ?: return null
        val appVersion = properties.getProperty("appVersion") ?: return null
        val createdAt = properties.getProperty("createdAtEpochMs")?.toLongOrNull() ?: return null
        val payloadSha = properties.getProperty("payloadSha256") ?: return null
        val payloadSize = properties.getProperty("payloadSizeBytes")?.toLongOrNull() ?: return null
        return SaveStateMetadata(
            identity = SaveStateIdentity(contentSha, coreId, coreVersion, format),
            appVersion = appVersion,
            createdAtEpochMs = createdAt,
            payloadSha256 = payloadSha,
            payloadSizeBytes = payloadSize,
        )
    }

    private fun copyAndHash(input: InputStream, target: Path): DigestAndSize {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileOutputStream(target.toFile()).use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
            }
            output.fd.sync()
        }
        return DigestAndSize(digest.digest().toLowerHex(), size)
    }

    private fun hashFile(path: Path): DigestAndSize = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            size += count
        }
        DigestAndSize(digest.digest().toLowerHex(), size)
    }

    private fun hashStream(input: InputStream): DigestAndSize {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return DigestAndSize(digest.digest().toLowerHex(), size)
    }

    private fun moveDirectory(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        if (Files.exists(target)) deleteRecursively(target)
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
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

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private data class DigestAndSize(val sha256: String, val sizeBytes: Long)

    companion object {
        private const val CURRENT_DIR = "current"
        private const val PREVIOUS_DIR = "previous"
        private const val STATES_DIR = "states"
        private const val DATA_FILE = "data.bin"
        private const val METADATA_FILE = "metadata.properties"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
