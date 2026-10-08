package dev.codex.libretroplatform

import android.content.ContentResolver
import android.net.Uri
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Properties

interface SystemFileStore {
    fun load(): List<SystemFileRecord>
    fun import(resolver: ContentResolver, uri: Uri, displayName: String): SystemFileRecord
    fun delete(id: String)
}

class InMemorySystemFileStore : SystemFileStore {
    private val values = linkedMapOf<String, SystemFileRecord>()
    override fun load(): List<SystemFileRecord> = values.values.toList()
    override fun import(resolver: ContentResolver, uri: Uri, displayName: String): SystemFileRecord = error("in-memory system file store cannot read Android content")
    override fun delete(id: String) { values.remove(id) }
}

/** Imports BIOS/system data into a bounded private directory without guessing proprietary formats. */
class FileSystemFileStore(private val root: Path) : SystemFileStore {
    private val filesRoot = root.resolve("files")
    private val indexRoot = root.resolve("index")

    init {
        Files.createDirectories(filesRoot)
        Files.createDirectories(indexRoot)
    }

    override fun load(): List<SystemFileRecord> = synchronized(this) {
        if (!Files.isDirectory(indexRoot)) return@synchronized emptyList()
        Files.list(indexRoot).use { stream ->
            stream.filter { it.fileName.toString().endsWith(SUFFIX) }
                .sorted()
                .map { path -> read(path) }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
    }

    override fun import(resolver: ContentResolver, uri: Uri, displayName: String): SystemFileRecord = synchronized(this) {
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(MAX_NAME_LENGTH)
            .ifBlank { "system-file.bin" }
        val temporary = Files.createTempFile(filesRoot, ".system-", ".part")
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("the system file could not be read")
            input.use { source ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        require(size <= MAX_BYTES) { "system files are limited to 64 MiB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val id = digest.digest().toHex()
            val target = filesRoot.resolve("$id.bin")
            if (target != temporary) {
                moveFile(temporary, target)
            }
            val record = SystemFileRecord(id, safeName, target.toString(), size, id)
            write(record)
            record
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete(id: String) {
        synchronized(this) {
            require(id.matches(SHA_PATTERN)) { "system file identity is invalid" }
            Files.deleteIfExists(filesRoot.resolve("$id.bin"))
            Files.deleteIfExists(indexRoot.resolve("$id$SUFFIX"))
        }
    }

    private fun read(path: Path): SystemFileRecord? = runCatching {
        val properties = Properties()
        Files.newInputStream(path).use { properties.load(it) }
        require(properties.getProperty(KEY_VERSION)?.toIntOrNull() == VERSION)
        val id = properties.getProperty(KEY_ID) ?: return@runCatching null
        require(id.matches(SHA_PATTERN))
        val privatePath = properties.getProperty(KEY_PRIVATE_PATH) ?: return@runCatching null
        val safe = Paths.get(privatePath).toAbsolutePath().normalize()
        require(safe.startsWith(filesRoot.toAbsolutePath().normalize()))
        require(Files.isRegularFile(safe))
        SystemFileRecord(
            id = id,
            displayName = properties.getProperty(KEY_NAME) ?: "system-file.bin",
            privatePath = safe.toString(),
            sizeBytes = properties.getProperty(KEY_SIZE)?.toLongOrNull() ?: return@runCatching null,
            sha256 = properties.getProperty(KEY_HASH) ?: return@runCatching null,
        )
    }.getOrNull()

    private fun write(record: SystemFileRecord) {
        val properties = Properties().apply {
            setProperty(KEY_VERSION, VERSION.toString())
            setProperty(KEY_ID, record.id)
            setProperty(KEY_NAME, record.displayName)
            setProperty(KEY_PRIVATE_PATH, record.privatePath)
            setProperty(KEY_SIZE, record.sizeBytes.toString())
            setProperty(KEY_HASH, record.sha256)
        }
        val target = indexRoot.resolve("${record.id}$SUFFIX")
        val temporary = Files.createTempFile(indexRoot, ".system-index-", ".part")
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val VERSION = 1
        private const val SUFFIX = ".properties"
        private const val MAX_NAME_LENGTH = 120
        private const val MAX_BYTES = 64L * 1024L * 1024L
        private const val KEY_VERSION = "systemFileVersion"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "displayName"
        private const val KEY_PRIVATE_PATH = "privatePath"
        private const val KEY_SIZE = "sizeBytes"
        private const val KEY_HASH = "sha256"
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
    }
}
