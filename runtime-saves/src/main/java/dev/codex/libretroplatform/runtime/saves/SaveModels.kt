package dev.codex.libretroplatform.runtime.saves

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

private val SHA_256 = Regex("[0-9a-f]{64}")
private val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

data class SaveKey(
    val contentSha256: String,
    val coreId: String,
) {
    init {
        require(SHA_256.matches(contentSha256)) { "contentSha256 must be a lowercase SHA-256 digest" }
        require(SAFE_COMPONENT.matches(coreId)) { "coreId contains unsafe path characters" }
    }
}

data class NativeSaveRecord(
    val key: SaveKey,
    val payloadSha256: String,
    val payloadSizeBytes: Long,
    val generation: Long,
    val path: Path,
)

sealed interface NativeRecoveryResult {
    data class Available(
        val record: NativeSaveRecord,
        val restoredFromRecovery: Boolean,
    ) : NativeRecoveryResult

    data object MissingOrCorrupt : NativeRecoveryResult
}

interface ExternalSaveTarget {
    fun openOutputStream(): OutputStream
    fun openInputStream(): InputStream
}

data class ExportedSave(
    val payloadSha256: String,
    val payloadSizeBytes: Long,
)

class SaveTransactionException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

class SaveExportVerificationException(message: String) : java.io.IOException(message)

data class SaveStateIdentity(
    val contentSha256: String,
    val coreId: String,
    val coreVersion: String,
    val stateFormatVersion: Int,
) {
    init {
        require(SHA_256.matches(contentSha256)) { "contentSha256 must be a lowercase SHA-256 digest" }
        require(SAFE_COMPONENT.matches(coreId)) { "coreId contains unsafe path characters" }
        require(coreVersion.isNotBlank()) { "coreVersion must not be blank" }
        require(stateFormatVersion >= 0) { "stateFormatVersion must not be negative" }
    }
}

data class SaveStateMetadata(
    val identity: SaveStateIdentity,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val payloadSha256: String,
    val payloadSizeBytes: Long,
) {
    init {
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must not be negative" }
        require(SHA_256.matches(payloadSha256)) { "payloadSha256 must be a lowercase SHA-256 digest" }
        require(payloadSizeBytes >= 0) { "payloadSizeBytes must not be negative" }
    }
}

data class VerifiedSaveState(
    val metadata: SaveStateMetadata,
    val payloadPath: Path,
)

sealed interface SaveStateReadResult {
    data class Success(val state: VerifiedSaveState) : SaveStateReadResult
    data class IdentityMismatch(
        val expected: SaveStateIdentity,
        val actual: SaveStateIdentity,
    ) : SaveStateReadResult
    data object Missing : SaveStateReadResult
    data object Corrupt : SaveStateReadResult
}

class SaveStateException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
