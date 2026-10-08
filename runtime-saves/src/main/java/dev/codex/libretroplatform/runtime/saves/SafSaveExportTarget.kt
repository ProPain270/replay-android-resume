package dev.codex.libretroplatform.runtime.saves

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** SAF target used only for verified export; it is never a canonical save location. */
class SafSaveExportTarget(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ExternalSaveTarget {
    override fun openOutputStream(): OutputStream =
        resolver.openOutputStream(uri, "w")
            ?: throw FileNotFoundException("SAF export target could not be opened for writing")

    override fun openInputStream(): InputStream =
        resolver.openInputStream(uri)
            ?: throw FileNotFoundException("SAF export target could not be reopened for verification")
}
