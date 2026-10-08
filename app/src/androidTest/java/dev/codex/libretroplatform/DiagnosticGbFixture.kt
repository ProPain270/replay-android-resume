package dev.codex.libretroplatform

import android.content.Context
import java.security.MessageDigest

internal object DiagnosticGbFixture {
    const val AUTHORITY = "dev.codex.libretroplatform.integration.fixtures"
    fun bytes(context: Context, nonce: String? = null): ByteArray = DiagnosticFixtureProvider.bytes(context, nonce)
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
