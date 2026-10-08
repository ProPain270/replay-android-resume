package dev.codex.libretroplatform.runtime.host

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.view.Surface
import dev.codex.libretroplatform.runtime.api.Button
import dev.codex.libretroplatform.runtime.api.AnalogAxis
import dev.codex.libretroplatform.runtime.api.Capability
import dev.codex.libretroplatform.runtime.api.CommandResult
import dev.codex.libretroplatform.runtime.api.EmulationEngine
import dev.codex.libretroplatform.runtime.api.LogicalInput
import dev.codex.libretroplatform.runtime.api.SessionCommand
import dev.codex.libretroplatform.runtime.api.SessionEvent
import dev.codex.libretroplatform.runtime.api.SessionEventListener
import dev.codex.libretroplatform.runtime.api.SessionEventSubscription
import dev.codex.libretroplatform.runtime.api.SessionHandle
import dev.codex.libretroplatform.runtime.api.SessionRequest
import dev.codex.libretroplatform.runtime.api.SessionDiagnostics
import dev.codex.libretroplatform.runtime.api.SessionState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android adapter for the bundled software-core lane. */
class NativeEmulationEngine(
    context: Context,
    private val corePathFor: (String) -> String = { coreId ->
        when (coreId) {
            "sameboy", "default" -> "${context.applicationInfo.nativeLibraryDir}/libsameboy_libretro.so"
            "mgba" -> "${context.applicationInfo.nativeLibraryDir}/libmgba_libretro.so"
            "snes9x" -> "${context.applicationInfo.nativeLibraryDir}/libsnes9x_libretro.so"
            else -> error("unsupported core: $coreId")
        }
    },
    private val runtime: NativeRuntime = NativeRuntime(),
) : EmulationEngine, SurfaceBindable {
    private val runtimeSystemDirectory = context.filesDir.resolve("runtime/system").apply { mkdirs() }
    private val runtimeSaveDirectory = context.filesDir.resolve("runtime/native-saves").apply { mkdirs() }
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val sessions = ConcurrentHashMap<Long, RuntimeSession>()

    override suspend fun createSession(request: SessionRequest): Result<SessionHandle> {
        runtime.ensureLoaded()
        val corePath = runCatching { corePathFor(request.coreId) }.getOrElse { return Result.failure(it) }
        val handleOrError = runtime.nativeSessionCreateWithCore(
            corePath = corePath,
            contentPath = request.contentPath,
            systemDirectory = runtimeSystemDirectory.absolutePath,
            saveDirectory = runtimeSaveDirectory.absolutePath,
        )
        if (handleOrError <= 0L) {
            return Result.failure(IllegalStateException("native core session creation failed: ${-handleOrError}"))
        }
        val capabilities = buildSet {
            add(Capability.SoftwareVideo)
            add(Capability.Audio)
            add(Capability.RetropadInput)
            if (request.nativeSaveStagingPath != null && request.nativeSaveRestorePath != null) {
                add(Capability.BatterySave)
            }
            if (request.saveStateStagingPath != null) {
                add(Capability.SaveState)
            }
        }
        val session = RuntimeSession(
            handle = SessionHandle(handleOrError),
            nativeSaveStagingPath = request.nativeSaveStagingPath,
            nativeSaveRestorePath = request.nativeSaveRestorePath,
            saveStateStagingPath = request.saveStateStagingPath,
            capabilities = capabilities,
            coreId = request.coreId,
        )
        sessions[handleOrError] = session
        session.publish(SessionEvent.StateChanged(SessionState.Created))
        return Result.success(session.handle)
    }

    override fun observe(session: SessionHandle, listener: SessionEventListener): SessionEventSubscription? =
        sessions[session.value]?.observe(listener)

    override fun capabilities(session: SessionHandle): Set<Capability> =
        sessions[session.value]?.capabilities.orEmpty()

    override suspend fun setAudioVolume(session: SessionHandle, volume: Float): CommandResult {
        val runtimeSession = sessions[session.value] ?: return CommandResult.Rejected("Unknown session")
        synchronized(runtimeSession) {
            if (Capability.Audio !in runtimeSession.capabilities) return CommandResult.Unsupported(Capability.Audio)
            runtimeSession.setAudioVolume(volume)
            return CommandResult.Accepted
        }
    }

    override suspend fun command(session: SessionHandle, command: SessionCommand): CommandResult {
        val runtimeSession = sessions[session.value] ?: return CommandResult.Rejected("Unknown session")
        return synchronized(runtimeSession) {
            when (command) {
                SessionCommand.Start -> {
                    if (runtimeSession.state.get() != SessionState.Created) {
                        CommandResult.Rejected("Start is only valid from Created")
                    } else {
                        resultFor(runtime.nativeSessionStart(session.value)) {
                            runtimeSession.configureAudio(runtime.nativeSessionAudioSampleRate(session.value))
                            runtimeSession.configureTiming(runtime.nativeSessionFramesPerSecond(session.value))
                            runtimeSession.state.set(SessionState.Running)
                            runtimeSession.publish(SessionEvent.StateChanged(SessionState.Running))
                            runtimeSession.startFrameLoop()
                        }
                    }
                }
                SessionCommand.Pause -> {
                    if (runtimeSession.state.get() != SessionState.Running) {
                        CommandResult.Rejected("Pause is only valid from Running")
                    } else {
                        resultFor(runtime.nativeSessionPause(session.value)) {
                            runtimeSession.state.set(SessionState.Paused)
                            runtimeSession.stopFrameLoop()
                            runtimeSession.publishDiagnostics(force = true, reason = "pause")
                            runtimeSession.publish(SessionEvent.StateChanged(SessionState.Paused))
                        }
                    }
                }
                SessionCommand.Resume -> {
                    if (runtimeSession.state.get() != SessionState.Paused) {
                        CommandResult.Rejected("Resume is only valid from Paused")
                    } else {
                        resultFor(runtime.nativeSessionResume(session.value)) {
                            runtimeSession.state.set(SessionState.Running)
                            runtimeSession.publish(SessionEvent.StateChanged(SessionState.Running))
                            runtimeSession.startFrameLoop()
                        }
                    }
                }
                SessionCommand.SaveNative -> {
                    val path = runtimeSession.nativeSaveStagingPath
                    if (path == null) {
                        CommandResult.Unsupported(Capability.BatterySave)
                    } else {
                        commandResult(
                            runtime.nativeSessionSaveNative(session.value, path),
                            Capability.BatterySave,
                        )
                    }
                }
                SessionCommand.LoadNative -> {
                    val path = runtimeSession.nativeSaveRestorePath
                    if (path == null) {
                        CommandResult.Unsupported(Capability.BatterySave)
                    } else {
                        commandResult(
                            runtime.nativeSessionLoadNative(session.value, path),
                            Capability.BatterySave,
                        )
                    }
                }
                SessionCommand.SaveState -> {
                    val path = runtimeSession.saveStateStagingPath
                    if (path == null) {
                        CommandResult.Unsupported(Capability.SaveState)
                    } else {
                        commandResult(
                            runtime.nativeSessionSaveState(session.value, path),
                            Capability.SaveState,
                        )
                    }
                }
                SessionCommand.LoadState -> {
                    val path = runtimeSession.saveStateStagingPath
                    if (path == null) {
                        CommandResult.Unsupported(Capability.SaveState)
                    } else {
                        commandResult(
                            runtime.nativeSessionLoadState(session.value, path),
                            Capability.SaveState,
                        )
                    }
                }
                is SessionCommand.Input -> {
                    if (runtimeSession.state.get() != SessionState.Running) {
                        CommandResult.Rejected("Input is only valid from Running")
                    } else {
                        applyInput(session.value, command.input)
                    }
                }
            }
        }
    }

    override suspend fun closeSession(session: SessionHandle): CommandResult {
        val runtimeSession = sessions.remove(session.value) ?: return CommandResult.Rejected("Unknown session")
        synchronized(runtimeSession) {
            runtimeSession.stopFrameLoop()
            runtimeSession.publishDiagnostics(force = true, reason = "close")
            val result = try {
                runtime.nativeSessionClose(session.value)
            } catch (failure: Throwable) {
                runtimeSession.releaseAudio()
                runtimeSession.state.set(SessionState.Closed)
                runtimeSession.closeListeners()
                return CommandResult.Rejected(failure.message ?: "Native session close failed")
            }
            runtimeSession.releaseAudio()
            runtimeSession.state.set(SessionState.Closed)
            runtimeSession.publish(SessionEvent.StateChanged(SessionState.Closed))
            runtimeSession.closeListeners()
            return resultFor(result) {}
        }
    }

    override suspend fun attachSurface(session: SessionHandle, surface: Surface): CommandResult {
        val runtimeSession = sessions[session.value] ?: return CommandResult.Rejected("Unknown session")
        return synchronized(runtimeSession) {
            if (runtimeSession.state.get() == SessionState.Closed) {
                CommandResult.Rejected("Session is already closed")
            } else {
                resultFor(runtime.nativeSessionAttachSurface(session.value, surface)) {}
            }
        }
    }

    override suspend fun detachSurface(session: SessionHandle): CommandResult {
        val runtimeSession = sessions[session.value] ?: return CommandResult.Rejected("Unknown session")
        return synchronized(runtimeSession) {
            if (runtimeSession.state.get() == SessionState.Closed) {
                CommandResult.Rejected("Session is already closed")
            } else {
                resultFor(runtime.nativeSessionDetachSurface(session.value)) {}
            }
        }
    }

    fun shutdown() {
        sessions.entries.toList().forEach { (handle, session) ->
            if (sessions.remove(handle, session)) {
                synchronized(session) {
                    session.stopFrameLoop()
                    session.publishDiagnostics(force = true, reason = "shutdown")
                    runCatching { runtime.nativeSessionClose(handle) }
                    session.releaseAudio()
                    session.state.set(SessionState.Closed)
                    session.closeListeners()
                }
            }
        }
        executor.shutdownNow()
    }

    private fun resultFor(code: Int, onAccepted: () -> Unit): CommandResult = if (code == 0) {
        onAccepted()
        CommandResult.Accepted
    } else {
        CommandResult.Rejected("Native runtime command failed: $code")
    }

    private fun commandResult(code: Int, capability: Capability): CommandResult = when (code) {
        0 -> CommandResult.Accepted
        12 -> CommandResult.Unsupported(capability)
        else -> CommandResult.Rejected("Native runtime command failed: $code")
    }

    private fun inputMask(input: LogicalInput): Int {
        var mask = 0
        input.buttons.forEach { button -> mask = mask or (1 shl button.nativeId) }
        return mask
    }

    private fun applyInput(handle: Long, input: LogicalInput): CommandResult {
        val digitalResult = resultFor(runtime.nativeSessionSetInputMask(handle, inputMask(input))) {}
        if (digitalResult !is CommandResult.Accepted) return digitalResult
        input.analog.forEach { (axis, value) ->
            val result = runtime.nativeSessionSetAnalog(
                handle,
                axis.nativeId,
                (value.coerceIn(-1f, 1f) * 32767f).toInt(),
            )
            if (result != 0) return resultFor(result) {}
        }
        return CommandResult.Accepted
    }

    private val Button.nativeId: Int
        get() = when (this) {
            Button.B -> 0
            Button.Select -> 2
            Button.Start -> 3
            Button.Up -> 4
            Button.Down -> 5
            Button.Left -> 6
            Button.Right -> 7
            Button.A -> 8
            Button.X -> 9
            Button.Y -> 1
            Button.L -> 10
            Button.R -> 11
            Button.L2 -> 12
            Button.R2 -> 13
            Button.L3 -> 14
            Button.R3 -> 15
        }

    private val AnalogAxis.nativeId: Int
        get() = when (this) {
            AnalogAxis.LeftX -> 0
            AnalogAxis.LeftY -> 1
            AnalogAxis.RightX -> 2
            AnalogAxis.RightY -> 3
        }

    private inner class RuntimeSession(
        val handle: SessionHandle,
        val nativeSaveStagingPath: String?,
        val nativeSaveRestorePath: String?,
        val saveStateStagingPath: String?,
        val capabilities: Set<Capability>,
        val coreId: String,
    ) {
        val state = AtomicReference(SessionState.Created)
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<SessionEventListener>()
        private var frameLoop: ScheduledFuture<*>? = null
        private var audioTrack: AudioTrack? = null
        private var audioVolume = 1f
        private val pendingPcm = PendingPcm(
            readFrames = { runtime.nativeSessionReadAudio(handle.value, it) },
            writeSamples = { samples, offset, count ->
                audioTrack?.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING) ?: 0
            },
        )
        private var pacing = CoreFramePacing(0.0)
        private val diagnosticsCadence = DiagnosticsCadence()
        private var latestDiagnostics = SessionDiagnostics(coreId)

        fun observe(listener: SessionEventListener): SessionEventSubscription {
            val initialEvents = synchronized(this) {
                listeners += listener
                listOf(
                    SessionEvent.CapabilitySnapshot(capabilities),
                    SessionEvent.StateChanged(state.get()),
                    SessionEvent.DiagnosticsSnapshot(latestDiagnostics),
                )
            }
            initialEvents.forEach { event -> runCatching { listener.onEvent(event) } }
            return SessionEventSubscription { listeners.remove(listener) }
        }

        fun closeListeners() {
            listeners.clear()
        }

        fun publish(event: SessionEvent) {
            val snapshot = synchronized(this) {
                if (event is SessionEvent.StateChanged) state.set(event.state)
                listeners.toList()
            }
            snapshot.forEach { listener -> runCatching { listener.onEvent(event) } }
        }

        fun startFrameLoop() {
            synchronized(this) {
                if (frameLoop?.isCancelled == false || frameLoop?.isDone == false) return
                audioTrack?.play()
                frameLoop = executor.scheduleAtFixedRate({
                    synchronized(this) frame@{
                        if (state.get() != SessionState.Running) return@frame
                        val code = try {
                            runtime.nativeSessionRunFrame(handle.value)
                        } catch (failure: Throwable) {
                            fail("Native frame execution threw: ${failure.message ?: "unknown error"}")
                            return@frame
                        }
                        if (code != 0) {
                            fail("Native frame execution failed: $code")
                            return@frame
                        }
                        val audioResult = try {
                            if (audioTrack != null) pendingPcm.pump() else 0
                        } catch (failure: Throwable) {
                            fail("Audio transfer threw: ${failure.message ?: "unknown error"}")
                            return@frame
                        }
                        if (audioResult < 0) {
                            fail("Audio transfer failed: $audioResult")
                            return@frame
                        }
                        publishDiagnostics()
                    }
                }, 0L, pacing.intervalNanos, TimeUnit.NANOSECONDS)
            }
        }

        fun configureTiming(reportedFps: Double) {
            pacing = CoreFramePacing(reportedFps)
        }

        fun publishDiagnostics(force: Boolean = false, reason: String = "periodic") {
            synchronized(this) {
                if (!diagnosticsCadence.shouldPublish(System.nanoTime(), force)) return
                val geometry = runCatching { runtime.nativeSessionVideoGeometry(handle.value) }.getOrNull()
                val performance = runCatching { runtime.nativeSessionPerformance(handle.value) }.getOrNull()
                val underruns = audioTrack?.let { track ->
                    runCatching { track.underrunCount.toLong().takeIf { it >= 0L } }.getOrNull()
                }
                latestDiagnostics = SessionDiagnostics(
                    coreId = coreId,
                    videoWidth = geometry?.getOrNull(0)?.takeIf { it > 0 },
                    videoHeight = geometry?.getOrNull(1)?.takeIf { it > 0 },
                    audioSampleRateHz = runCatching { runtime.nativeSessionAudioSampleRate(handle.value) }
                        .getOrNull()?.takeIf { it > 0 },
                    audioUnderrunCount = underruns,
                    reportedFramesPerSecond = pacing.reportedFramesPerSecond,
                    scheduledFrameIntervalMs = pacing.intervalNanos / 1_000_000.0,
                ).withNativePerformance(performance)
                publish(SessionEvent.DiagnosticsSnapshot(latestDiagnostics))
                // One line per snapshot; no content names or paths. A zero is
                // measured; null is unavailable. See work/runtime-measurements.md.
                val d = latestDiagnostics
                Log.i("retro_metrics", "session=${handle.value} core=$coreId reason=$reason " +
                    "state=${state.get()} duration_ms=${d.sessionDurationMs} startup_ms=${d.startupDurationMs} " +
                    "first_frame_ms=${d.firstFrameDurationMs} first_video_ms=${d.firstVideoFrameDurationMs} " +
                    "frame_count=${d.frameCount} executed=${d.framesExecuted} failures=${d.frameFailures} " +
                    "mean_ms=${d.frameDurationMeanMs} p95_upper_ms=${d.frameDurationP95UpperBoundMs} " +
                    "max_ms=${d.frameDurationMaxMs} video_callbacks=${d.videoCallbacks} " +
                    "rendered=${d.framesRendered} dropped=${d.framesDropped} duplicates=${d.duplicateFrames} " +
                    "audio_underruns=${d.audioUnderrunCount} core_fps=${d.reportedFramesPerSecond} " +
                    "interval_ms=${d.scheduledFrameIntervalMs}")
            }
        }

        private fun fail(message: String) {
            state.set(SessionState.Failed)
            stopFrameLoop()
            publishDiagnostics(force = true, reason = "failure")
            publish(SessionEvent.Failure(message))
        }

        fun stopFrameLoop() {
            synchronized(this) {
                frameLoop?.cancel(false)
                frameLoop = null
                audioTrack?.pause()
            }
        }

        fun configureAudio(sampleRate: Int) {
            synchronized(this) {
                if (audioTrack != null) return
                audioTrack = createAudioTrack(sampleRate.coerceIn(8000, 192000))?.also { it.setVolume(audioVolume) }
            }
        }

        fun setAudioVolume(volume: Float) {
            synchronized(this) {
                audioVolume = volume.coerceIn(0f, 1f)
                audioTrack?.setVolume(audioVolume)
            }
        }

        fun releaseAudio() {
            synchronized(this) {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            }
        }

        private fun createAudioTrack(sampleRate: Int): AudioTrack? = runCatching {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }
}
