package dev.codex.libretroplatform.runtime.content

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/** ContentSource backed by an Android Storage Access Framework document URI. */
class SafContentSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    private val cancellationSignal: CancellationSignal? = null,
) : ContentSource {
    override val displayName: String? = queryMetadata(OpenableColumns.DISPLAY_NAME) as String?
    override val sizeBytes: Long? = (queryMetadata(OpenableColumns.SIZE) as Long?)?.takeIf { it >= 0L }

    override fun openStream(): InputStream {
        val descriptor = if (cancellationSignal == null) {
            resolver.openFileDescriptor(uri, "r")
        } else {
            resolver.openFileDescriptor(uri, "r", cancellationSignal)
        }

        if (descriptor != null) {
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }

        return resolver.openInputStream(uri)
            ?: throw FileNotFoundException("SAF document could not be opened")
    }

    private fun queryMetadata(column: String): Any? {
        val projection = arrayOf(column)
        val cursor: Cursor = resolver.query(uri, projection, null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(column)
            if (index < 0 || it.isNull(index)) return null
            return when (column) {
                OpenableColumns.DISPLAY_NAME -> it.getString(index)
                OpenableColumns.SIZE -> it.getLong(index)
                else -> null
            }
        }
    }

    companion object {
        /** Persists only the URI grant bits actually provided by the picker. */
        fun persistReadPermission(
            resolver: ContentResolver,
            uri: Uri,
            grantedFlags: Int,
        ) {
            val persistableFlags = grantedFlags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(persistableFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                "a read grant is required for content import"
            }
            resolver.takePersistableUriPermission(uri, persistableFlags)
        }
    }
}
