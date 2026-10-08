package dev.codex.libretroplatform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, app-owned backup boundary.
 *
 * The bundle contains settings, library records, native saves, manual and
 * automatic save states, and optional thumbnails. It intentionally excludes
 * imported game binaries, runtime staging files, and arbitrary filesystem
 * paths. A restore is staged, size/hash validated, and only then merged into
 * the current app-private tree.
 */
class PortableBackupStore(
    private val appPrivateRoot: Path,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    init {
        Files.createDirectories(appPrivateRoot)
    }

    fun export(output: OutputStream, preferences: UserPreferences): BackupExportSummary {
        val files = collectBackupFiles()
        val settings = propertiesBytes(UserPreferencesBackupCodec.encode(preferences))
        val allEntries = listOf(BackupFile(SETTINGS_ENTRY, settings.size.toLong(), sha256(settings), settingsSupplier = { ByteArrayInputStream(settings) })) + files
        val manifest = Properties().apply {
            setProperty(KEY_FORMAT_VERSION, FORMAT_VERSION.toString())
            setProperty(KEY_CREATED_AT, nowEpochMs().toString())
            setProperty(KEY_CONTAINS_GAME_CONTENT, "false")
            setProperty(KEY_ENTRY_COUNT, allEntries.size.toString())
            allEntries.forEachIndexed { index, file ->
                setProperty("entry.$index.path", file.path)
                setProperty("entry.$index.size", file.size.toString())
                setProperty("entry.$index.sha256", file.sha256)
            }
        }

        ZipOutputStream(output.buffered()).use { zip ->
            writeZipBytes(zip, MANIFEST_ENTRY, propertiesBytes(manifest))
            allEntries.forEach { file ->
                zip.putNextEntry(ZipEntry(file.path))
                file.openStream().use { input ->
                    copyExactly(input, zip, file.size)
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        return BackupExportSummary(
            libraryRecords = files.count { it.path.startsWith(LIBRARY_PREFIX) },
            collections = files.count { it.path.startsWith(COLLECTIONS_PREFIX) },
            nativeSaves = files.count { it.path.startsWith(NATIVE_SAVES_PREFIX) },
            saveStates = files.count { it.path.startsWith(SAVE_STATES_PREFIX) },
            automaticCheckpoints = files.count { it.path.startsWith(AUTOMATIC_CHECKPOINTS_PREFIX) },
            thumbnails = files.count { it.path.startsWith(THUMBNAILS_PREFIX) },
            artwork = files.count { it.path.startsWith(ARTWORK_PREFIX) },
            bytes = allEntries.sumOf { it.size },
        )
    }

    fun restore(input: InputStream): BackupRestoreResult {
        val staging = Files.createTempDirectory(appPrivateRoot, ".backup-restore-")
        try {
            val seen = mutableSetOf<String>()
            var totalBytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "backup contains an unsupported directory entry" }
                    val path = safeEntryPath(entry.name)
                    require(seen.add(path)) { "backup contains a duplicate entry: $path" }
                    require(seen.size <= MAX_ENTRY_COUNT) { "backup contains too many entries" }
                    val target = staging.resolve(path).normalize()
                    require(target.startsWith(staging.toAbsolutePath().normalize())) { "backup entry escapes staging" }
                    Files.createDirectories(target.parent)
                    val expectedLimit = if (path == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                    val copied = Files.newOutputStream(target).use { output ->
                        copyBounded(zip, output, expectedLimit) { totalBytes += it }
                    }
                    require(copied <= expectedLimit) { "backup entry is too large: $path" }
                    zip.closeEntry()
                    require(totalBytes <= MAX_TOTAL_BYTES) { "backup is too large" }
                }
            }

            val manifest = readProperties(staging.resolve(MANIFEST_ENTRY))
            require(manifest.getProperty(KEY_FORMAT_VERSION)?.toIntOrNull() == FORMAT_VERSION) {
                "unsupported backup format"
            }
            require(manifest.getProperty(KEY_CONTAINS_GAME_CONTENT) == "false") {
                "backup contains game content and is not accepted by this importer"
            }
            val entryCount = manifest.getProperty(KEY_ENTRY_COUNT)?.toIntOrNull()
                ?: throw IllegalArgumentException("backup manifest is incomplete")
            require(entryCount in 1..MAX_ENTRY_COUNT) { "backup manifest entry count is invalid" }
            val expectedEntries = (0 until entryCount).map { index ->
                val path = manifest.getProperty("entry.$index.path")
                    ?: throw IllegalArgumentException("backup manifest is missing an entry path")
                val size = manifest.getProperty("entry.$index.size")?.toLongOrNull()
                    ?: throw IllegalArgumentException("backup manifest has an invalid entry size")
                val sha256 = manifest.getProperty("entry.$index.sha256")
                    ?: throw IllegalArgumentException("backup manifest is missing an entry hash")
                require(isAllowedEntry(path) && path != MANIFEST_ENTRY) { "backup entry is outside the portable scope" }
                require(size in 0..MAX_ENTRY_BYTES) { "backup manifest entry size is invalid" }
                require(sha256.matches(SHA_256)) { "backup manifest entry hash is invalid" }
                BackupManifestEntry(path, size, sha256)
            }
            require(expectedEntries.map { it.path }.toSet().size == expectedEntries.size) {
                "backup manifest contains duplicate paths"
            }
            val actualEntries = seen - MANIFEST_ENTRY
            require(actualEntries == expectedEntries.map { it.path }.toSet()) {
                "backup contents do not match the manifest"
            }
            expectedEntries.forEach { expected ->
                val staged = staging.resolve(expected.path).normalize()
                require(Files.size(staged) == expected.size) { "backup entry size verification failed: ${expected.path}" }
                require(hashFile(staged) == expected.sha256) { "backup entry hash verification failed: ${expected.path}" }
            }

            val settingsPath = staging.resolve(SETTINGS_ENTRY)
            val preferences = Files.newInputStream(settingsPath).use { input ->
                UserPreferencesBackupCodec.decode(readProperties(input))
            }
            var libraryRecords = 0
            var collections = 0
            var nativeSaves = 0
            var saveStates = 0
            var automaticCheckpoints = 0
            var thumbnails = 0
            var artwork = 0
            expectedEntries.forEach { entry ->
                val target = appPrivateRoot.resolve(entry.path).normalize()
                require(target.startsWith(appPrivateRoot.toAbsolutePath().normalize())) { "restore target escapes app storage" }
                if (Files.isSymbolicLink(target)) throw IllegalArgumentException("restore target is a symbolic link")
                Files.createDirectories(target.parent)
                atomicReplace(staging.resolve(entry.path), target)
                when {
                    entry.path.startsWith(LIBRARY_PREFIX) -> libraryRecords++
                    entry.path.startsWith(COLLECTIONS_PREFIX) -> collections++
                    entry.path.startsWith(NATIVE_SAVES_PREFIX) -> nativeSaves++
                    entry.path.startsWith(SAVE_STATES_PREFIX) -> saveStates++
                    entry.path.startsWith(AUTOMATIC_CHECKPOINTS_PREFIX) -> automaticCheckpoints++
                    entry.path.startsWith(THUMBNAILS_PREFIX) -> thumbnails++
                    entry.path.startsWith(ARTWORK_PREFIX) -> artwork++
                }
            }
            return BackupRestoreResult(
                preferences = preferences,
                libraryRecords = libraryRecords,
                collections = collections,
                nativeSaves = nativeSaves,
                saveStates = saveStates,
                automaticCheckpoints = automaticCheckpoints,
                thumbnails = thumbnails,
                artwork = artwork,
            )
        } finally {
            deleteRecursively(staging)
        }
    }

    private fun collectBackupFiles(): List<BackupFile> = BACKUP_PREFIXES.flatMap { prefix ->
        val root = appPrivateRoot.resolve(prefix.removeSuffix("/"))
        if (!Files.isDirectory(root)) return@flatMap emptyList()
        Files.walk(root).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path) }
                .map { path ->
                    val relative = appPrivateRoot.relativize(path).toString().replace('\u005c', '/')
                    val size = Files.size(path)
                    require(size <= MAX_ENTRY_BYTES) { "backup source file is too large: $relative" }
                    BackupFile(relative, size, hashFile(path), settingsSupplier = { Files.newInputStream(path) })
                }
                .sorted(Comparator.comparing { it.path })
                .toList()
        }
    }

    private fun safeEntryPath(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 240) { "backup entry path is invalid" }
        require(!raw.startsWith('/') && !raw.contains('\\') && !raw.contains('\u0000')) { "backup entry path is invalid" }
        val normalized = Paths.get(raw).normalize()
        require(!normalized.isAbsolute && normalized.toString() == raw && !raw.split('/').any { it == ".." || it.isBlank() }) {
            "backup entry path escapes portable scope"
        }
        require(isAllowedEntry(raw)) { "backup entry is outside the portable scope" }
        return raw
    }

    private fun isAllowedEntry(path: String): Boolean =
        path == MANIFEST_ENTRY || path == SETTINGS_ENTRY || BACKUP_PREFIXES.any { path.startsWith(it) }

    private fun propertiesBytes(properties: Properties): ByteArray = ByteArrayOutputStream().use { output ->
        properties.store(output, "Libretro Platform portable backup")
        output.toByteArray()
    }

    private fun readProperties(path: Path): Properties = Files.newInputStream(path).use(::readProperties)

    private fun readProperties(input: InputStream): Properties = Properties().also { properties ->
        input.buffered().use { properties.load(it) }
    }

    private fun writeZipBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun copyExactly(input: InputStream, output: OutputStream, expectedSize: Long) {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            copied += count
        }
        require(copied == expectedSize) { "backup source changed while it was being exported" }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, onBytes: (Long) -> Unit): Long {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(copied <= limit - count) { "backup entry exceeds its size limit" }
            output.write(buffer, 0, count)
            copied += count
            onBytes(count.toLong())
        }
        return copied
    }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            this@toHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    private fun atomicReplace(source: Path, target: Path) {
        val temporary = Files.createTempFile(target.parent, ".restore-", ".part")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
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

    private data class BackupFile(
        val path: String,
        val size: Long,
        val sha256: String,
        val settingsSupplier: () -> InputStream,
    ) {
        fun openStream(): InputStream = settingsSupplier()
    }

    private data class BackupManifestEntry(val path: String, val size: Long, val sha256: String)

    data class BackupExportSummary(
        val libraryRecords: Int,
        val collections: Int,
        val nativeSaves: Int,
        val saveStates: Int,
        val automaticCheckpoints: Int,
        val thumbnails: Int,
        val artwork: Int,
        val bytes: Long,
    )

    data class BackupRestoreResult(
        val preferences: UserPreferences,
        val libraryRecords: Int,
        val collections: Int,
        val nativeSaves: Int,
        val saveStates: Int,
        val automaticCheckpoints: Int,
        val thumbnails: Int,
        val artwork: Int,
    )

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.properties"
        private const val SETTINGS_ENTRY = "settings.properties"
        private const val LIBRARY_PREFIX = "library/records/"
        private const val COLLECTIONS_PREFIX = "library/collections/"
        private const val NATIVE_SAVES_PREFIX = "saves/"
        private const val SAVE_STATES_PREFIX = "save-states/"
        private const val AUTOMATIC_CHECKPOINTS_PREFIX = "automatic-checkpoints/"
        private const val THUMBNAILS_PREFIX = "save-state-thumbnails/"
        private const val ARTWORK_PREFIX = "artwork/"
        private const val KEY_FORMAT_VERSION = "formatVersion"
        private const val KEY_CREATED_AT = "createdAtEpochMs"
        private const val KEY_CONTAINS_GAME_CONTENT = "containsGameContent"
        private const val KEY_ENTRY_COUNT = "entryCount"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val MAX_ENTRY_COUNT = 10_000
        private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val BACKUP_PREFIXES = listOf(LIBRARY_PREFIX, COLLECTIONS_PREFIX, NATIVE_SAVES_PREFIX, SAVE_STATES_PREFIX, AUTOMATIC_CHECKPOINTS_PREFIX, THUMBNAILS_PREFIX, ARTWORK_PREFIX)
    }
}
