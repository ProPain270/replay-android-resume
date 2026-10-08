package dev.codex.libretroplatform.runtime.content

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentPathPolicyTest {
    @Test
    fun normalizesSafePathInsideRoot() {
        val root = Files.createTempDirectory("path-policy")
        assertEquals(
            root.toAbsolutePath().normalize().resolve("content/file.bin"),
            ContentPathPolicy.resolveInside(root, "content/./file.bin"),
        )
    }

    @Test
    fun rejectsWindowsTraversalAndNul() {
        val root = Files.createTempDirectory("path-policy")
        assertThrows(IllegalArgumentException::class.java) {
            ContentPathPolicy.resolveInside(root, "..\\outside.bin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentPathPolicy.resolveInside(root, "content/\u0000.bin")
        }
    }
}
