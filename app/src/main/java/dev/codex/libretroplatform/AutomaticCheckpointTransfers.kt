package dev.codex.libretroplatform

/** Main-thread-confined guard for the runtime's single state staging path. */
internal class AutomaticCheckpointTransfers(private val onBusyChanged: (Boolean) -> Unit = {}) {
    private var generation = 0L
    private var cancellationGeneration = 0L
    private var active: Long? = null
    private var automaticActive = false
    private var pending: ((Long) -> Unit)? = null
    private val waiters = mutableListOf<(Boolean) -> Unit>()
    val busy: Boolean get() = active != null

    fun beginManual(): Long? {
        if (busy || pending != null) return null
        return begin()
    }

    /** Coalesces pause/background/exit requests, but never drops them behind a manual transfer. */
    fun requestAutomatic(start: (Long) -> Unit, onComplete: (Boolean) -> Unit = {}) {
        waiters += onComplete
        // Keep the first queued operation as the capture boundary. Later
        // lifecycle/background requests only join its completion fan-out.
        if (!automaticActive && pending == null) pending = start
        drain()
    }

    fun isCurrent(ticket: Long): Boolean = active == ticket

    fun finish(ticket: Long, success: Boolean) {
        if (!isCurrent(ticket)) return
        val completionGeneration = cancellationGeneration
        val completions = if (automaticActive) waiters.toList() else emptyList()
        if (automaticActive) waiters.clear()
        automaticActive = false
        active = null
        onBusyChanged(false)
        // A completion can close/fail the session and cancel this guard. Do not
        // deliver the remaining old-session waiters after that invalidation.
        // Starting another transfer without cancelling is not invalidation:
        // all callers waiting for this completed capture still need its result.
        completions.forEach {
            if (completionGeneration != cancellationGeneration) return@forEach
            it(success)
        }
        drain()
    }

    /** Invalidates callbacks from a destroyed/failed session. */
    fun cancel() {
        generation++
        cancellationGeneration++
        active = null
        automaticActive = false
        pending = null
        waiters.clear()
        onBusyChanged(false)
    }

    private fun begin(): Long {
        val ticket = ++generation
        active = ticket
        onBusyChanged(true)
        return ticket
    }

    private fun drain() {
        if (busy) return
        val start = pending ?: return
        pending = null
        automaticActive = true
        start(begin())
    }
}
