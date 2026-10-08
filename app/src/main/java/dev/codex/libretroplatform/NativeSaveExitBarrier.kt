package dev.codex.libretroplatform

/**
 * Main-thread-only exit barrier for a save API that coalesces in-flight calls.
 * Call after pausing, with resume blocked until [onComplete]. An older save's
 * result only releases the barrier; it must never satisfy the exit request.
 */
internal fun saveFreshNativeForExit(
    transferInFlight: Boolean,
    save: ((Boolean) -> Unit) -> Unit,
    isSessionCurrent: () -> Boolean,
    onComplete: (Boolean) -> Unit,
) {
    var freshRequested = false
    var completed = false
    fun captureFresh() {
        if (freshRequested || !isSessionCurrent()) return
        freshRequested = true
        // This can join a new capture started by an earlier completion waiter.
        // That capture is also post-pause: the old transfer is already finished.
        save { success ->
            if (!completed && isSessionCurrent()) {
                completed = true
                onComplete(success)
            }
        }
    }

    if (!isSessionCurrent()) return
    if (transferInFlight) {
        // Exactly one wait, then one fresh request. Do not recursively ask for
        // freshness when the coalescing API is re-entered from a completion.
        save { captureFresh() }
    } else {
        captureFresh()
    }
}
