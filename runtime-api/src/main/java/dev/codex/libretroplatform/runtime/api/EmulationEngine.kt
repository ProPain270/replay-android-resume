package dev.codex.libretroplatform.runtime.api

/** Stable product/runtime boundary. Core-specific implementation stays below this API. */
interface EmulationEngine {
    suspend fun createSession(request: SessionRequest): Result<SessionHandle>
    fun observe(session: SessionHandle, listener: SessionEventListener): SessionEventSubscription?
    /** Returns the capability snapshot for a live session, if the host can expose one. */
    fun capabilities(session: SessionHandle): Set<Capability> = emptySet()
    /** Updates the active session mix without changing the core's audio stream. */
    suspend fun setAudioVolume(session: SessionHandle, volume: Float): CommandResult =
        CommandResult.Unsupported(Capability.Audio)
    suspend fun command(session: SessionHandle, command: SessionCommand): CommandResult
    suspend fun closeSession(session: SessionHandle): CommandResult
}

data class SessionDiagnostics(
    val coreId: String,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioSampleRateHz: Int? = null,
    /** Attempted native run_frame calls; invalid lifecycle commands are excluded. */
    val frameCount: Long? = null,
    /** Execution only: includes synchronous native video/audio callbacks, not pacing/queue/Java audio. */
    val frameDurationMeanMs: Double? = null,
    /** Histogram upper bound: 0.25 ms bins through 256 ms; overflow uses observed maximum. */
    val frameDurationP95UpperBoundMs: Double? = null,
    val frameDurationMaxMs: Double? = null,
    val framesExecuted: Long? = null,
    val frameFailures: Long? = null,
    val videoCallbacks: Long? = null,
    /** Successful ANativeWindow posts, not confirmed display scans or GPU completion. */
    val framesRendered: Long? = null,
    /** Nonduplicate callbacks that could not be posted, including missing surfaces. Not deadline misses. */
    val framesDropped: Long? = null,
    /** Libretro asks to reuse the previous image; these are not dropped frames. */
    val duplicateFrames: Long? = null,
    /** Android AudioTrack's measured underrun count; null if no working track/counter is available. */
    val audioUnderrunCount: Long? = null,
    /** Accepted native Start to successful core load completion; excludes app/content import. */
    val startupDurationMs: Double? = null,
    /** Accepted native Start to first successful run_frame completion. */
    val firstFrameDurationMs: Double? = null,
    /** Accepted native Start to first successful video post; null until a post succeeds. */
    val firstVideoFrameDurationMs: Double? = null,
    /** Monotonic elapsed time from native Start to snapshot/close, including pauses. */
    val sessionDurationMs: Double? = null,
    /** Loaded core's av_info.timing.fps, not measured throughput; null when unavailable. */
    val reportedFramesPerSecond: Double? = null,
    /** Actual scheduler target, including the explicit 60 Hz fallback if metadata is invalid. */
    val scheduledFrameIntervalMs: Double? = null,
)

/** Runtime-owned event listener; intentionally avoids platform Flow/desugaring ABI differences. */
fun interface SessionEventListener {
    fun onEvent(event: SessionEvent)
}

/** Cancels a live session event stream. Implementations must make this idempotent. */
fun interface SessionEventSubscription {
    fun cancel()
}

@JvmInline
value class SessionHandle(val value: Long)

data class SessionRequest(
    val contentPath: String,
    val coreId: String,
    /** App-private native staging path; the repository commits it only after verification. */
    val nativeSaveStagingPath: String? = null,
    /** Existing verified native save staged here before core startup, when present. */
    val nativeSaveRestorePath: String? = null,
    /** App-private save-state staging path; the repository commits it transactionally. */
    val saveStateStagingPath: String? = null,
)

sealed interface SessionCommand {
    data object Start : SessionCommand
    data object Pause : SessionCommand
    data object Resume : SessionCommand
    data object SaveNative : SessionCommand
    data object LoadNative : SessionCommand
    data object SaveState : SessionCommand
    data object LoadState : SessionCommand
    data class Input(val port: Int, val input: LogicalInput) : SessionCommand
}

data class LogicalInput(
    val buttons: Set<Button> = emptySet(),
    /** Normalized -1..1 values for the two standard Libretro analog sticks. */
    val analog: Map<AnalogAxis, Float> = emptyMap(),
)

enum class Button { Up, Down, Left, Right, A, B, X, Y, L, R, L2, R2, L3, R3, Start, Select }

enum class AnalogAxis { LeftX, LeftY, RightX, RightY }

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Unsupported(val capability: Capability) : CommandResult
    data class Rejected(val reason: String) : CommandResult
}

sealed interface SessionEvent {
    data class StateChanged(val state: SessionState) : SessionEvent
    data class CapabilitySnapshot(val capabilities: Set<Capability>) : SessionEvent
    data class DiagnosticsSnapshot(val diagnostics: SessionDiagnostics) : SessionEvent
    data class Failure(val message: String) : SessionEvent
}

enum class SessionState { Created, Running, Paused, Closing, Closed, Failed }

enum class Capability {
    SoftwareVideo,
    HardwareVideo,
    Audio,
    RetropadInput,
    BatterySave,
    SaveState,
    MemoryAccess,
    CoreOptions,
    Vfs,
}
