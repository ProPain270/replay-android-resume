package dev.codex.libretroplatform.runtime.content

import java.io.InputStream

private val SHA_256 = Regex("[0-9a-f]{64}")

/** Identity of verified content. The digest is calculated from the imported bytes. */
data class ContentIdentity(
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(SHA_256.matches(sha256)) { "sha256 must be a lowercase SHA-256 digest" }
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
    }
}

/** App-private immutable content that is safe to pass to a native runtime. */
data class ImportedContent(
    val identity: ContentIdentity,
    val canonicalPath: java.nio.file.Path,
    val sourceUri: android.net.Uri?,
    val displayName: String?,
)

fun interface ImportCancellationToken {
    fun isCancellationRequested(): Boolean
}

object NeverCancel : ImportCancellationToken {
    override fun isCancellationRequested(): Boolean = false
}

data class ImportProgress(
    val bytesCopied: Long,
    val totalBytes: Long?,
)

interface ContentSource {
    val displayName: String?
    val sizeBytes: Long?

    /** Opens a fresh stream. The caller owns and closes the returned stream. */
    fun openStream(): InputStream
}

class ContentImportCancelled : java.io.IOException("content import cancelled")

class ContentImportException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
