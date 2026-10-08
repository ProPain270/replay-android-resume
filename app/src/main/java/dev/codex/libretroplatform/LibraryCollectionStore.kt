package dev.codex.libretroplatform

import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

interface LibraryCollectionStore {
    fun load(): List<LibraryCollection>
    fun save(collection: LibraryCollection)
    fun delete(id: String)
}

class InMemoryLibraryCollectionStore : LibraryCollectionStore {
    private val values = linkedMapOf<String, LibraryCollection>()

    override fun load(): List<LibraryCollection> = values.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    override fun save(collection: LibraryCollection) {
        values[collection.id] = collection
    }

    override fun delete(id: String) {
        values.remove(id)
    }
}

/** Small atomic file store for user-created local shelves. */
class FileLibraryCollectionStore(private val root: Path) : LibraryCollectionStore {
    init {
        Files.createDirectories(root)
    }

    override fun load(): List<LibraryCollection> = synchronized(this) {
        if (!Files.isDirectory(root)) return@synchronized emptyList()
        Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().endsWith(SUFFIX) }
                .sorted()
                .map { path -> read(path) }
                .filter { it != null }
                .map { it!! }
                .toList()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    override fun save(collection: LibraryCollection) {
        synchronized(this) {
            require(collection.id.matches(ID_PATTERN)) { "collection id is invalid" }
            require(collection.name.isNotBlank() && collection.name.length <= MAX_NAME_LENGTH) { "collection name is invalid" }
            val properties = Properties().apply {
                setProperty(KEY_VERSION, VERSION.toString())
                setProperty(KEY_ID, collection.id)
                setProperty(KEY_NAME, collection.name.trim())
                setProperty(KEY_ITEM_IDS, collection.itemIds.sorted().joinToString("\n"))
                setProperty(KEY_CREATED, collection.createdAtEpochMs.toString())
                setProperty(KEY_UPDATED, collection.updatedAtEpochMs.toString())
            }
            val target = root.resolve("${collection.id}$SUFFIX")
            val temporary = Files.createTempFile(root, ".collection-", ".part")
            try {
                FileOutputStream(temporary.toFile()).use { output ->
                    properties.store(output, null)
                    output.fd.sync()
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    override fun delete(id: String) {
        synchronized(this) {
            require(id.matches(ID_PATTERN)) { "collection id is invalid" }
            Files.deleteIfExists(root.resolve("$id$SUFFIX"))
        }
    }

    private fun read(path: Path): LibraryCollection? = runCatching {
        val properties = Properties()
        Files.newInputStream(path).use { properties.load(it) }
        require(properties.getProperty(KEY_VERSION)?.toIntOrNull() == VERSION)
        val id = properties.getProperty(KEY_ID) ?: return@runCatching null
        require(id.matches(ID_PATTERN))
        val name = properties.getProperty(KEY_NAME) ?: return@runCatching null
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        LibraryCollection(
            id = id,
            name = name,
            itemIds = properties.getProperty(KEY_ITEM_IDS).orEmpty().lineSequence().filter { it.matches(SHA_PATTERN) }.toSet(),
            createdAtEpochMs = properties.getProperty(KEY_CREATED)?.toLongOrNull() ?: return@runCatching null,
            updatedAtEpochMs = properties.getProperty(KEY_UPDATED)?.toLongOrNull() ?: return@runCatching null,
        )
    }.getOrNull()

    companion object {
        private const val VERSION = 1
        private const val SUFFIX = ".properties"
        private const val MAX_NAME_LENGTH = 80
        private const val KEY_VERSION = "collectionVersion"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ITEM_IDS = "itemIds"
        private const val KEY_CREATED = "createdAtEpochMs"
        private const val KEY_UPDATED = "updatedAtEpochMs"
        private val ID_PATTERN = Regex("collection-[0-9a-z-]{1,80}")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
    }
}
