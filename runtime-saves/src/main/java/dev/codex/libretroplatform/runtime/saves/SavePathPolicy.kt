package dev.codex.libretroplatform.runtime.saves

import java.nio.file.Path
import java.nio.file.Paths

object SavePathPolicy {
    fun resolveInside(root: Path, relativePath: String): Path {
        require(relativePath.isNotEmpty()) { "relative path must not be empty" }
        require(!relativePath.contains('\u0000')) { "path contains NUL" }
        require(!relativePath.contains('\\')) { "backslash paths are not supported" }

        val relative = Paths.get(relativePath)
        require(!relative.isAbsolute) { "absolute path is not allowed" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val candidate = normalizedRoot.resolve(relative).normalize()
        require(candidate.startsWith(normalizedRoot)) { "path escapes root" }
        return candidate
    }

    fun requireComponent(value: String, label: String): String {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+~-]{0,127}"))) {
            "$label contains unsafe path characters"
        }
        return value
    }
}
