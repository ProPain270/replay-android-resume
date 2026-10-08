package dev.codex.libretroplatform

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionStoreTest {
    private val roots = mutableListOf<Path>()

    @After
    fun tearDown() {
        roots.forEach { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    @Test
    fun collectionRoundTripsAndSortsByName() {
        val root = Files.createTempDirectory("collection-store-test").also(roots::add)
        val store = FileLibraryCollectionStore(root)
        val first = LibraryCollection(
            id = "collection-aaa",
            name = "Travel",
            itemIds = setOf("a".repeat(64)),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
        val second = LibraryCollection(
            id = "collection-bbb",
            name = "Finish next",
            createdAtEpochMs = 3L,
            updatedAtEpochMs = 4L,
        )
        store.save(first)
        store.save(second)

        val reloaded = FileLibraryCollectionStore(root).load()

        assertEquals(listOf("Finish next", "Travel"), reloaded.map { it.name })
        assertEquals(first.itemIds, reloaded.last().itemIds)
        assertTrue(reloaded.last().updatedAtEpochMs == 2L)
    }

    @Test
    fun deletingCollectionDoesNotTouchSiblingCollection() {
        val root = Files.createTempDirectory("collection-delete-test").also(roots::add)
        val store = FileLibraryCollectionStore(root)
        store.save(LibraryCollection("collection-one", "One", createdAtEpochMs = 1L, updatedAtEpochMs = 1L))
        store.save(LibraryCollection("collection-two", "Two", createdAtEpochMs = 1L, updatedAtEpochMs = 1L))

        store.delete("collection-one")

        assertEquals(listOf("Two"), FileLibraryCollectionStore(root).load().map { it.name })
    }
}
