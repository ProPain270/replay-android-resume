package dev.codex.libretroplatform.runtime.api

/**
 * Deterministic, core-independent lifecycle model for one emulation session.
 *
 * This class has no Android, JNI, storage, or threading concerns. A caller owns
 * it from the session's command-serialization context and is responsible for
 * performing the corresponding native work after an accepted transition.
 * Rejected and unsupported commands never change [state].
 */
class SessionStateMachine(
    capabilities: Set<Capability> = emptySet(),
    initialState: SessionState = SessionState.Created,
) {
    /** A defensive snapshot prevents external mutation from changing decisions. */
    val capabilities: Set<Capability> = capabilities.toSet()

    var state: SessionState = initialState
        private set

    /**
     * Applies a product command to the lifecycle model.
     *
     * `Start` intentionally has no video capability requirement: a headless
     * CoreLab session is a valid runtime consumer. Video, audio, input, and
     * persistence are independently reported optional capabilities.
     */
    fun command(command: SessionCommand): CommandResult = when (command) {
        SessionCommand.Start -> start()
        SessionCommand.Pause -> pause()
        SessionCommand.Resume -> resume()
        SessionCommand.SaveNative -> saveNative()
        SessionCommand.LoadNative -> loadNative()
        SessionCommand.SaveState -> saveState()
        SessionCommand.LoadState -> loadState()
        is SessionCommand.Input -> input(command)
    }

    /** Requests teardown. Native cleanup must complete before [completeClose]. */
    fun requestClose(): CommandResult = when (state) {
        SessionState.Created,
        SessionState.Running,
        SessionState.Paused,
        SessionState.Failed,
        -> moveTo(SessionState.Closing)

        SessionState.Closing -> rejected("Close is already in progress")
        SessionState.Closed -> rejected("Closed sessions cannot be closed again")
    }

    /** Completes teardown after the runtime has released its core resources. */
    fun completeClose(): CommandResult = when (state) {
        SessionState.Closing -> moveTo(SessionState.Closed)
        else -> rejected("Close can only complete from Closing")
    }

    /**
     * Records a terminal runtime failure. A failed session can still be closed
     * so native resources can be released deterministically.
     */
    fun fail(reason: String): CommandResult {
        if (reason.isBlank()) {
            return rejected("Failure reason must not be blank")
        }

        return when (state) {
            SessionState.Created,
            SessionState.Running,
            SessionState.Paused,
            SessionState.Closing,
            -> moveTo(SessionState.Failed)

            SessionState.Failed -> rejected("Failed sessions cannot fail again")
            SessionState.Closed -> rejected("Closed sessions cannot fail")
        }
    }

    private fun start(): CommandResult = if (state == SessionState.Created) {
        moveTo(SessionState.Running)
    } else {
        rejected("Start is only valid from Created")
    }

    private fun pause(): CommandResult = if (state == SessionState.Running) {
        moveTo(SessionState.Paused)
    } else {
        rejected("Pause is only valid from Running")
    }

    private fun resume(): CommandResult = if (state == SessionState.Paused) {
        moveTo(SessionState.Running)
    } else {
        rejected("Resume is only valid from Paused")
    }

    private fun saveNative(): CommandResult = when (state) {
        SessionState.Running,
        SessionState.Paused,
        -> if (Capability.BatterySave in capabilities) {
            CommandResult.Accepted
        } else {
            CommandResult.Unsupported(Capability.BatterySave)
        }

        else -> rejected("SaveNative is only valid from Running or Paused")
    }

    private fun saveState(): CommandResult = when (state) {
        SessionState.Running,
        SessionState.Paused,
        -> if (Capability.SaveState in capabilities) {
            CommandResult.Accepted
        } else {
            CommandResult.Unsupported(Capability.SaveState)
        }

        else -> rejected("SaveState is only valid from Running or Paused")
    }

    private fun loadNative(): CommandResult = when (state) {
        SessionState.Running,
        SessionState.Paused,
        -> if (Capability.BatterySave in capabilities) {
            CommandResult.Accepted
        } else {
            CommandResult.Unsupported(Capability.BatterySave)
        }

        else -> rejected("LoadNative is only valid from Running or Paused")
    }

    private fun loadState(): CommandResult = when (state) {
        SessionState.Running,
        SessionState.Paused,
        -> if (Capability.SaveState in capabilities) {
            CommandResult.Accepted
        } else {
            CommandResult.Unsupported(Capability.SaveState)
        }

        else -> rejected("LoadState is only valid from Running or Paused")
    }

    private fun input(command: SessionCommand.Input): CommandResult {
        if (command.port < 0) {
            return rejected("Input port must be non-negative")
        }
        if (state != SessionState.Running) {
            return rejected("Input is only valid from Running")
        }
        return if (Capability.RetropadInput in capabilities) {
            CommandResult.Accepted
        } else {
            CommandResult.Unsupported(Capability.RetropadInput)
        }
    }

    private fun moveTo(next: SessionState): CommandResult {
        state = next
        return CommandResult.Accepted
    }

    private fun rejected(reason: String): CommandResult = CommandResult.Rejected(reason)
}
