package dev.codex.libretroplatform.runtime.saves

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * App-private canonical native-save store.
 *
 * Each save version is a complete directory containing data and metadata. A
 * prepared transaction is verified before rotation. Recovery prefers the
 * highest verified generation among current, previous, and recovery copies.
 */
class NativeSaveStore(
    private val root: Path,
) {
    private val transactionLock = Any()

    init {
        Files.createDirectories(root)
    }

    fun canonicalPath(key: SaveKey): Path = recordDirectory(key).resolve("current/data.bin")

    fun previousPath(key: SaveKey): Path = recordDirectory(key).resolve("previous/data.bin")

    fun recoveryPath(key: SaveKey): Path = recordDirectory(key).resolve("recovery/data.bin")

    fun write(
        key: SaveKey,
        payload: InputStream,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): NativeSaveRecord {
        synchronized(transactionLock) {
            val base = recordDirectory(key)
            val transactions = base.resolve(".transactions")
            Files.createDirectories(transactions)
            val transaction = transactions.resolve(UUID.randomUUID().toString())
            Files.createDirectories(transaction)

            try {
                val payloadPath = transaction.resolve(DATA_FILE)
                val digestAndSize = payload.use { copyAndHash(it, payloadPath) }
                val generation = nextGeneration(key, nowEpochMs)
                writeMetadata(
                    transaction.resolve(METADATA_FILE),
                    key,
                    digestAndSize.sha256,
                    digestAndSize.sizeBytes,
                    generation,
                )

                val verified = readRecord(transaction, key)
                    ?: throw SaveTransactionException("prepared native save failed verification")

                rotate(base)
                moveDirectory(transaction, base.resolve(CURRENT_DIR))
                val committed = readRecord(base.resolve(CURRENT_DIR), key)
                    ?: throw SaveTransactionException("committed native save failed verification")
                return committed
            } catch (failure: SaveTransactionException) {
                throw failure
            } catch (failure: IOException) {
                throw SaveTransactionException("native save transaction failed", failure)
            } finally {
                deleteRecursively(transaction)
            }
        }
    }

    /**
     * Validates the canonical record and restores the newest verified rotated
     * copy when current is absent or corrupt.
     */
    fun recover(key: SaveKey): NativeRecoveryResult {
        synchronized(transactionLock) {
            val base = recordDirectory(key)
            cleanupTransactions(base)

            val current = readRecord(base.resolve(CURRENT_DIR), key)
            if (current != null) return NativeRecoveryResult.Available(current, false)

            quarantineInvalidCurrent(base)
            val candidates = listOf(
                base.resolve(PREVIOUS_DIR),
                base.resolve(RECOVERY_DIR),
            ).mapNotNull { directory ->
                readRecord(directory, key)?.let { record -> directory to record }
            }.sortedByDescending { it.second.generation }

            val candidate = candidates.firstOrNull() ?: return NativeRecoveryResult.MissingOrCorrupt
            moveDirectory(candidate.first, base.resolve(CURRENT_DIR))
            val restored = readRecord(base.resolve(CURRENT_DIR), key)
                ?: return NativeRecoveryResult.MissingOrCorrupt
            return NativeRecoveryResult.Available(restored, true)
        }
    }

    fun currentRecord(key: SaveKey): NativeSaveRecord? = when (val result = recover(key)) {
        is NativeRecoveryResult.Available -> result.record
        NativeRecoveryResult.MissingOrCorrupt -> null
    }

    fun openCanonicalInputStream(key: SaveKey): InputStream {
        val record = currentRecord(key) ?: throw IOException("no verified native save is available")
        return Files.newInputStream(record.path)
    }

    /** Removes all app-owned generations for a content/core identity. */
    fun delete(key: SaveKey) {
        synchronized(transactionLock) {
            deleteRecursively(recordDirectory(key))
        }
    }

    /** Removes every app-owned core save associated with one content identity. */
    fun deleteForContent(contentSha256: String) {
        require(contentSha256.matches(SHA_256)) { "contentSha256 must be a lowercase SHA-256 digest" }
        synchronized(transactionLock) {
            deleteRecursively(root.resolve("native").resolve(contentSha256))
        }
    }

    /**
     * Exports a verified copy. The target is never canonical and cannot replace
     * or invalidate the app-private save if it fails verification.
     */
    fun exportVerified(key: SaveKey, target: ExternalSaveTarget): ExportedSave {
        synchronized(transactionLock) {
            val record = currentRecord(key)
                ?: throw SaveExportVerificationException("no verified native save is available")
            try {
                target.openOutputStream().use { output ->
                    Files.newInputStream(record.path).use { input -> input.copyTo(output) }
                    output.flush()
                }

                val external = hashStream(target.openInputStream())
                if (external.sha256 != record.payloadSha256 || external.sizeBytes != record.payloadSizeBytes) {
                    throw SaveExportVerificationException("external save verification failed")
                }
                return ExportedSave(external.sha256, external.sizeBytes)
            } catch (failure: SaveExportVerificationException) {
                throw failure
            } catch (failure: IOException) {
                throw SaveExportVerificationException("external save export failed")
            }
        }
    }

    private fun recordDirectory(key: SaveKey): Path {
        val relative = Paths.get("native", key.contentSha256, key.coreId).toString()
        return SavePathPolicy.resolveInside(root, relative)
    }

    private fun nextGeneration(key: SaveKey, requested: Long): Long {
        val current = readRecord(recordDirectory(key).resolve(CURRENT_DIR), key)?.generation ?: -1L
        return maxOf(requested, current + 1L)
    }

    private fun rotate(base: Path) {
        val recovery = base.resolve(RECOVERY_DIR)
        val previous = base.resolve(PREVIOUS_DIR)
        val current = base.resolve(CURRENT_DIR)

        deleteRecursively(recovery)
        if (Files.exists(previous)) moveDirectory(previous, recovery)
        if (Files.exists(current)) moveDirectory(current, previous)
    }

    private fun readRecord(directory: Path, key: SaveKey): NativeSaveRecord? {
        val data = directory.resolve(DATA_FILE)
        val metadata = directory.resolve(METADATA_FILE)
        if (!Files.isRegularFile(data) || !Files.isRegularFile(metadata)) return null

        return try {
            val properties = Properties()
            Files.newInputStream(metadata).use { properties.load(it) }
            val storedContent = properties.getProperty(CONTENT_SHA)
            val storedCore = properties.getProperty(CORE_ID)
            val storedDigest = properties.getProperty(PAYLOAD_SHA)
            val storedSize = properties.getProperty(PAYLOAD_SIZE)?.toLongOrNull()
            val generation = properties.getProperty(GENERATION)?.toLongOrNull()
            if (storedContent != key.contentSha256 || storedCore != key.coreId ||
                storedDigest == null || storedSize == null || generation == null ||
                storedSize < 0 || generation < 0 || !SHA_256.matches(storedDigest)
            ) {
                return null
            }

            val actual = hashFile(data)
            if (actual.sha256 != storedDigest || actual.sizeBytes != storedSize) return null
            NativeSaveRecord(key, actual.sha256, actual.sizeBytes, generation, data)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetadata(
        path: Path,
        key: SaveKey,
        payloadSha256: String,
        payloadSizeBytes: Long,
        generation: Long,
    ) {
        val properties = Properties()
        properties.setProperty(CONTENT_SHA, key.contentSha256)
        properties.setProperty(CORE_ID, key.coreId)
        properties.setProperty(PAYLOAD_SHA, payloadSha256)
        properties.setProperty(PAYLOAD_SIZE, payloadSizeBytes.toString())
        properties.setProperty(GENERATION, generation.toString())
        FileOutputStream(path.toFile()).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
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

    private fun hashFile(path: Path): DigestAndSize = Files.newInputStream(path).use(::hashStream)

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

    private fun cleanupTransactions(base: Path) {
        val transactions = base.resolve(".transactions")
        if (!Files.isDirectory(transactions)) return
        Files.newDirectoryStream(transactions).use { entries ->
            entries.forEach { deleteRecursively(it) }
        }
        Files.deleteIfExists(transactions)
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

    private fun quarantineInvalidCurrent(base: Path) {
        val current = base.resolve(CURRENT_DIR)
        if (!Files.exists(current)) return
        val quarantine = base.resolve(".quarantine")
        Files.createDirectories(quarantine)
        moveDirectory(current, quarantine.resolve("corrupt-${System.nanoTime()}"))
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
        private const val RECOVERY_DIR = "recovery"
        private const val DATA_FILE = "data.bin"
        private const val METADATA_FILE = "metadata.properties"
        private const val CONTENT_SHA = "contentSha256"
        private const val CORE_ID = "coreId"
        private const val PAYLOAD_SHA = "payloadSha256"
        private const val PAYLOAD_SIZE = "payloadSizeBytes"
        private const val GENERATION = "generation"
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
