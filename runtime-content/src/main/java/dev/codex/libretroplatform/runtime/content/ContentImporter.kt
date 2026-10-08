package dev.codex.libretroplatform.runtime.content

import android.content.ContentResolver
import android.net.Uri
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Streams content into a private immutable cache. A partial or cancelled copy is
 * never promoted to a canonical content path.
 */
class ContentImporter(
    private val contentRoot: Path,
    private val maxImportBytes: Long = DEFAULT_MAX_IMPORT_BYTES,
    private val minimumFreeBytes: Long = DEFAULT_MINIMUM_FREE_BYTES,
) {
    private val promotionLock = Any()

    init {
        require(maxImportBytes > 0L) { "maxImportBytes must be positive" }
        require(minimumFreeBytes >= 0L) { "minimumFreeBytes must not be negative" }
        Files.createDirectories(contentRoot)
    }

    fun importUri(
        resolver: ContentResolver,
        uri: Uri,
        cancellationSignal: android.os.CancellationSignal? = null,
        cancellationToken: ImportCancellationToken = NeverCancel,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportedContent {
        return importContent(
            source = SafContentSource(resolver, uri, cancellationSignal),
            sourceUri = uri,
            cancellationToken = cancellationToken,
            onProgress = onProgress,
        )
    }

    fun importContent(
        source: ContentSource,
        sourceUri: Uri? = null,
        cancellationToken: ImportCancellationToken = NeverCancel,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportedContent {
        val temp = Files.createTempFile(contentRoot, ".import-", ".part")
        var promotedPath: Path? = null

        try {
            source.sizeBytes?.let { expected ->
                if (expected < 0L) throw ContentImportException("source size is invalid")
                if (expected > maxImportBytes) {
                    throw ContentImportException("content exceeds the ${maxImportBytes / BYTES_PER_GIB} GiB import limit")
                }
                ensureFreeSpace(expected)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            copyToTemp(
                source = source,
                temp = temp,
                digest = digest,
                cancellationToken = cancellationToken,
                onBytesCopied = { count ->
                    copied = count
                    onProgress(ImportProgress(copied, source.sizeBytes))
                },
            )

            source.sizeBytes?.let { expected ->
                if (expected != copied) {
                    throw ContentImportException("source size changed during import")
                }
            }

            val identity = ContentIdentity(
                sha256 = digest.digest().toLowerHex(),
                sizeBytes = copied,
            )
            val safeName = ContentPathPolicy.safeFileName(source.displayName)
            val target = ContentPathPolicy.resolveInside(
                contentRoot,
                Paths.get("content", identity.sha256, safeName).toString(),
            )

            synchronized(promotionLock) {
                val existing = findExistingContent(identity, target)
                if (existing != null) {
                    return ImportedContent(identity, existing, sourceUri, source.displayName)
                }

                Files.createDirectories(target.parent)
                moveNewFile(temp, target)
                promotedPath = target

                val verified = hashFile(target)
                if (verified != identity) {
                    Files.deleteIfExists(target)
                    throw ContentImportException("canonical content verification failed")
                }
            }

            return ImportedContent(identity, promotedPath!!, sourceUri, source.displayName)
        } catch (cancelled: ContentImportCancelled) {
            throw cancelled
        } catch (_: android.os.OperationCanceledException) {
            throw ContentImportCancelled()
        } catch (failure: ContentImportException) {
            throw failure
        } catch (failure: IOException) {
            throw ContentImportException("content import failed", failure)
        } finally {
            // This also removes a temp left by a provider failure or process-resume retry.
            Files.deleteIfExists(temp)
        }
    }

    /** Removes abandoned import files without touching canonical content. */
    fun cleanupTemporaryImports(): Int {
        if (!Files.exists(contentRoot)) return 0
        var deleted = 0
        Files.newDirectoryStream(contentRoot, ".import-*.part").use { entries ->
            for (entry in entries) {
                if (Files.deleteIfExists(entry)) deleted++
            }
        }
        return deleted
    }

    private fun copyToTemp(
        source: ContentSource,
        temp: Path,
        digest: MessageDigest,
        cancellationToken: ImportCancellationToken,
        onBytesCopied: (Long) -> Unit,
    ) {
        checkCancelled(cancellationToken)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        source.openStream().use { input ->
            FileOutputStream(temp.toFile()).use { output ->
                while (true) {
                    checkCancelled(cancellationToken)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    ensureImportBudget(copied, count.toLong())
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copied += count
                    onBytesCopied(copied)
                }
                output.fd.sync()
            }
        }
        checkCancelled(cancellationToken)
    }

    private fun checkCancelled(token: ImportCancellationToken) {
        if (token.isCancellationRequested()) throw ContentImportCancelled()
    }

    private fun ensureImportBudget(copied: Long, incoming: Long) {
        if (incoming < 0L || copied > maxImportBytes - incoming) {
            throw ContentImportException("content exceeds the ${maxImportBytes / BYTES_PER_GIB} GiB import limit")
        }
        if (copied == 0L || copied / BYTES_PER_MIB != (copied + incoming) / BYTES_PER_MIB) {
            ensureFreeSpace(incoming)
        }
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        val usableBytes = runCatching { Files.getFileStore(contentRoot).usableSpace }.getOrNull() ?: return
        if (requiredBytes > usableBytes || usableBytes - requiredBytes < minimumFreeBytes) {
            throw ContentImportException("not enough free storage for this import")
        }
    }

    private fun findExistingContent(identity: ContentIdentity, expectedPath: Path): Path? {
        if (!Files.exists(expectedPath)) return null
        return if (hashFile(expectedPath) == identity) expectedPath else null
    }

    private fun hashFile(path: Path): ContentIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return ContentIdentity(digest.digest().toLowerHex(), size)
    }

    private fun moveNewFile(source: Path, target: Path) {
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

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val BYTES_PER_GIB = 1024L * 1024L * 1024L
        private const val DEFAULT_MAX_IMPORT_BYTES = 8L * BYTES_PER_GIB
        private const val DEFAULT_MINIMUM_FREE_BYTES = 64L * BYTES_PER_MIB
    }
}
