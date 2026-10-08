package dev.codex.libretroplatform.runtime.content

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves provider/display-name-derived paths without allowing an escape from an
 * app-private root. It deliberately rejects both Unix and Windows separators.
 */
object ContentPathPolicy {
    fun resolveInside(root: Path, relativePath: String): Path {
        require(relativePath.isNotEmpty()) { "relative path must not be empty" }
        require(!relativePath.contains('\u0000')) { "path contains NUL" }
        require(!relativePath.contains('\\')) { "backslash paths are not supported" }

        val candidatePath = Paths.get(relativePath)
        require(!candidatePath.isAbsolute) { "absolute path is not allowed" }

        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = normalizedRoot.resolve(candidatePath).normalize()
        require(normalizedCandidate.startsWith(normalizedRoot)) { "path escapes root" }
        return normalizedCandidate
    }

    fun safeFileName(displayName: String?): String {
        val candidate = displayName
            ?.takeIf { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?: "content.bin"

        require(candidate != "." && candidate != "..") { "invalid file name" }
        require(!candidate.contains('\u0000')) { "file name contains NUL" }
        require(candidate.isNotBlank()) { "file name must not be blank" }
        return candidate.take(240)
    }
}
