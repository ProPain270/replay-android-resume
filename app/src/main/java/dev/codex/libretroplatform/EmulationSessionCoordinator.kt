package dev.codex.libretroplatform

import dev.codex.libretroplatform.runtime.api.EmulationEngine
import dev.codex.libretroplatform.runtime.api.AnalogAxis
import dev.codex.libretroplatform.runtime.api.LogicalInput
import dev.codex.libretroplatform.runtime.api.SessionCommand
import dev.codex.libretroplatform.runtime.api.SessionEvent
import dev.codex.libretroplatform.runtime.api.SessionEventListener
import dev.codex.libretroplatform.runtime.api.SessionEventSubscription
import dev.codex.libretroplatform.runtime.api.SessionHandle
import dev.codex.libretroplatform.runtime.api.SessionRequest
import android.view.Surface
import dev.codex.libretroplatform.runtime.host.SurfaceBindable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LaunchResult {
    data object PreviewOnly : LaunchResult

    data class Connected(val handle: SessionHandle) : LaunchResult

    data class Failed(val reason: String) : LaunchResult
}

/**
 * Keeps Compose unaware of JNI/core details while preserving a preview-capable API seam for
 * tests and unsupported content. The production ViewModel supplies the native engine.
 */
class EmulationSessionCoordinator(
    private val engine: EmulationEngine?,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val surfaceBindable: SurfaceBindable? = engine as? SurfaceBindable,
    private val nativeSaveStagingPathFor: (LibraryItem) -> String? = { null },
    private val nativeSaveRestorePreparedFor: (LibraryItem) -> Boolean = { false },
    private val saveStateStagingPathFor: (LibraryItem) -> String? = { null },
) {
    private val sessionLock = Any()
    private var activeSession: SessionHandle? = null
    private var pendingSurface: Surface? = null
    private var launchJob: Job? = null
    private var launchGeneration = 0L
    private var eventSubscription: SessionEventSubscription? = null

    fun launch(
        item: LibraryItem,
        onEvent: (SessionEvent) -> Unit = {},
        onResult: (LaunchResult) -> Unit,
    ) {
        val runtime = engine
        val contentPath = item.privateContentRef
        if (runtime == null || contentPath == null) {
            onResult(LaunchResult.PreviewOnly)
            return
        }

        val generation: Long
        val previousSession: SessionHandle?
        synchronized(sessionLock) {
            launchJob?.cancel()
            eventSubscription?.cancel()
            eventSubscription = null
            previousSession = activeSession
            activeSession = null
            generation = ++launchGeneration
        }
        previousSession?.let { stale ->
            scope.launch(Dispatchers.IO) { runtime.closeSession(stale) }
        }
        val job = scope.launch(Dispatchers.IO) {
            val nativeSavePrepared = try {
                nativeSaveRestorePreparedFor(item)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (isCurrentLaunch(generation)) {
                    withContext(mainDispatcher) {
                        onResult(LaunchResult.Failed(failure.message ?: "The save store could not prepare the session."))
                    }
                }
                return@launch
            }
            val result = try {
                val nativeSaveStagingPath = nativeSaveStagingPathFor(item)
                runtime.createSession(
                    SessionRequest(
                        contentPath = contentPath,
                        coreId = item.coreId ?: "default",
                        nativeSaveStagingPath = nativeSaveStagingPath,
                        nativeSaveRestorePath = nativeSaveStagingPath,
                        saveStateStagingPath = saveStateStagingPathFor(item),
                    ),
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            val handle = result.getOrElse {
                if (!isCurrentLaunch(generation)) return@launch
                withContext(mainDispatcher) {
                    onResult(LaunchResult.Failed(it.message ?: "The runtime could not open this title."))
                }
                return@launch
            }
            if (!isCurrentLaunch(generation)) {
                runtime.closeSession(handle)
                return@launch
            }
            observeEvents(runtime, handle, generation, onEvent)
            val capabilities = runtime.capabilities(handle)
            if (capabilities.isNotEmpty()) {
                withContext(mainDispatcher) {
                    if (isCurrentLaunch(generation)) {
                        onEvent(SessionEvent.CapabilitySnapshot(capabilities))
                    }
                }
            }
            val started = runtime.command(handle, SessionCommand.Start)
            if (started is dev.codex.libretroplatform.runtime.api.CommandResult.Accepted) {
                if (!isCurrentLaunch(generation)) {
                    runtime.closeSession(handle)
                    return@launch
                }
                if (nativeSavePrepared) {
                    val restored = runtime.command(handle, SessionCommand.LoadNative)
                    if (restored !is dev.codex.libretroplatform.runtime.api.CommandResult.Accepted) {
                        runtime.closeSession(handle)
                        if (!isCurrentLaunch(generation)) return@launch
                        withContext(mainDispatcher) {
                            onResult(LaunchResult.Failed("The verified native save could not be restored: ${commandMessage(restored)}"))
                        }
                        return@launch
                    }
                }
                val surface = synchronized(sessionLock) {
                    if (generation != launchGeneration) {
                        null
                    } else {
                        activeSession = handle
                        pendingSurface
                    }
                }
                if (surface == null && !isCurrentLaunch(generation)) {
                    runtime.closeSession(handle)
                    return@launch
                }
                surface?.let { pending ->
                    surfaceBindable?.attachSurface(handle, pending)
                }
                withContext(mainDispatcher) {
                    if (isCurrentLaunch(generation)) onResult(LaunchResult.Connected(handle))
                }
            } else {
                runtime.closeSession(handle)
                if (isCurrentLaunch(generation)) {
                    withContext(mainDispatcher) {
                        onResult(LaunchResult.Failed("The runtime created the session but could not start it."))
                    }
                }
            }
        }
        synchronized(sessionLock) {
            if (generation == launchGeneration) launchJob = job
        }
    }

    fun command(command: SessionCommand, onResult: (RuntimeCommandPreview) -> Unit) {
        val runtime = engine
        val handle = synchronized(sessionLock) { activeSession }
        if (runtime == null || handle == null) {
            onResult(
                RuntimeCommandPreview(
                    command = command,
                    accepted = true,
                    message = "Preview only: the runtime command was not sent to a core.",
                ),
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = try {
                runtime.command(handle, command)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                dev.codex.libretroplatform.runtime.api.CommandResult.Rejected(
                    failure.message ?: "The runtime command failed.",
                )
            }
            withContext(mainDispatcher) {
                if (!isActiveSession(handle)) return@withContext
                onResult(
                    RuntimeCommandPreview(
                        command = command,
                        accepted = result is dev.codex.libretroplatform.runtime.api.CommandResult.Accepted,
                        unsupportedCapability = (result as? dev.codex.libretroplatform.runtime.api.CommandResult.Unsupported)?.capability,
                        message = when (result) {
                            dev.codex.libretroplatform.runtime.api.CommandResult.Accepted -> "Runtime accepted the command."
                            is dev.codex.libretroplatform.runtime.api.CommandResult.Unsupported -> "The active core does not support this capability."
                            is dev.codex.libretroplatform.runtime.api.CommandResult.Rejected -> result.reason
                        },
                    ),
                )
            }
        }
    }

    fun input(
        buttons: Set<dev.codex.libretroplatform.runtime.api.Button>,
        analog: Map<AnalogAxis, Float> = emptyMap(),
        onResult: (RuntimeCommandPreview) -> Unit,
    ) {
        command(
            SessionCommand.Input(
                port = 0,
                input = LogicalInput(buttons = buttons, analog = analog),
            ),
            onResult,
        )
    }

    fun setAudioVolume(volume: Float, onResult: (RuntimeCommandPreview) -> Unit = {}) {
        val runtime = engine
        val handle = synchronized(sessionLock) { activeSession }
        if (runtime == null || handle == null) {
            onResult(
                RuntimeCommandPreview(
                    command = SessionCommand.Start,
                    accepted = true,
                    message = "Preview only: the runtime mix was not changed.",
                ),
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = try {
                runtime.setAudioVolume(handle, volume.coerceIn(0f, 1f))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                dev.codex.libretroplatform.runtime.api.CommandResult.Rejected(
                    failure.message ?: "The runtime audio mix failed.",
                )
            }
            withContext(mainDispatcher) {
                if (!isActiveSession(handle)) return@withContext
                onResult(
                    RuntimeCommandPreview(
                        command = SessionCommand.Start,
                        accepted = result !is dev.codex.libretroplatform.runtime.api.CommandResult.Rejected,
                        message = when (result) {
                            dev.codex.libretroplatform.runtime.api.CommandResult.Accepted -> "Runtime audio mix updated."
                            is dev.codex.libretroplatform.runtime.api.CommandResult.Unsupported -> "The active runtime does not expose audio mixing."
                            is dev.codex.libretroplatform.runtime.api.CommandResult.Rejected -> result.reason
                        },
                    ),
                )
            }
        }
    }

    fun clearSession() {
        val handle: SessionHandle?
        synchronized(sessionLock) {
            launchGeneration++
            launchJob?.cancel()
            launchJob = null
            eventSubscription?.cancel()
            eventSubscription = null
            handle = activeSession
            activeSession = null
            pendingSurface = null
        }
        if (handle != null) {
            scope.launch(Dispatchers.IO) { engine?.closeSession(handle) }
        }
    }

    private fun observeEvents(
        runtime: EmulationEngine,
        session: SessionHandle,
        generation: Long,
        onEvent: (SessionEvent) -> Unit,
    ) {
        val subscription = runtime.observe(session, SessionEventListener { event ->
            if (!isCurrentLaunch(generation)) return@SessionEventListener
            scope.launch(mainDispatcher) {
                if (isCurrentLaunch(generation)) onEvent(event)
            }
        }) ?: return
        synchronized(sessionLock) {
            if (generation != launchGeneration) {
                subscription.cancel()
            } else {
                eventSubscription?.cancel()
                eventSubscription = subscription
            }
        }
    }

    fun attachSurface(surface: Surface, onResult: (RuntimeCommandPreview) -> Unit = {}) {
        val handle: SessionHandle?
        val binder = surfaceBindable
        synchronized(sessionLock) {
            pendingSurface = surface
            handle = activeSession
        }
        if (handle == null || binder == null) {
            // TextureView can be ready before the asynchronous core session.
            // Keep the surface and bind it when launch completes.
            if (binder == null) {
                onResult(RuntimeCommandPreview(SessionCommand.Start, false, "No runtime surface is available."))
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = try {
                binder.attachSurface(handle, surface)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                dev.codex.libretroplatform.runtime.api.CommandResult.Rejected(
                    failure.message ?: "Video surface attachment failed.",
                )
            }
            withContext(mainDispatcher) {
                if (!isActiveSession(handle)) return@withContext
                onResult(
                    RuntimeCommandPreview(
                        SessionCommand.Start,
                        result is dev.codex.libretroplatform.runtime.api.CommandResult.Accepted,
                        if (result is dev.codex.libretroplatform.runtime.api.CommandResult.Accepted) "Video surface attached." else "Video surface could not attach.",
                    ),
                )
            }
        }
    }

    fun detachSurface() {
        val handle: SessionHandle?
        synchronized(sessionLock) {
            pendingSurface = null
            handle = activeSession
        }
        if (handle == null) return
        val binder = surfaceBindable ?: return
        scope.launch(Dispatchers.IO) { binder.detachSurface(handle) }
    }

    private fun isCurrentLaunch(generation: Long): Boolean = synchronized(sessionLock) {
        generation == launchGeneration
    }

    private fun isActiveSession(handle: SessionHandle): Boolean = synchronized(sessionLock) {
        activeSession == handle
    }

    private fun commandMessage(result: dev.codex.libretroplatform.runtime.api.CommandResult): String = when (result) {
        dev.codex.libretroplatform.runtime.api.CommandResult.Accepted -> "accepted"
        is dev.codex.libretroplatform.runtime.api.CommandResult.Unsupported -> "capability unsupported"
        is dev.codex.libretroplatform.runtime.api.CommandResult.Rejected -> result.reason
    }

}
