package dev.codex.libretroplatform.runtime.host

/** Call under the session monitor. Lifecycle/final snapshots bypass the 1 Hz limit. */
internal class DiagnosticsCadence {
    private var lastPublishedNanos: Long? = null

    fun shouldPublish(nowNanos: Long, force: Boolean = false): Boolean {
        val last = lastPublishedNanos
        if (!force && last != null && nowNanos - last < 1_000_000_000L) return false
        lastPublishedNanos = nowNanos
        return true
    }
}
