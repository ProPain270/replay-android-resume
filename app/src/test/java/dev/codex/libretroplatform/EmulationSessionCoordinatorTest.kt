package dev.codex.libretroplatform

import dev.codex.libretroplatform.runtime.api.CommandResult
import dev.codex.libretroplatform.runtime.api.Capability
import dev.codex.libretroplatform.runtime.api.EmulationEngine
import dev.codex.libretroplatform.runtime.api.SessionCommand
import dev.codex.libretroplatform.runtime.api.SessionEvent
import dev.codex.libretroplatform.runtime.api.SessionEventListener
import dev.codex.libretroplatform.runtime.api.SessionEventSubscription
import dev.codex.libretroplatform.runtime.api.SessionHandle
import dev.codex.libretroplatform.runtime.api.SessionRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulationSessionCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun forwardsRuntimeEventsAndClosesTheActiveSession() {
        val engine = FakeEngine()
        val coordinator = EmulationSessionCoordinator(
            engine = engine,
            scope = scope,
            mainDispatcher = Dispatchers.Default,
        )
        val connected = CountDownLatch(1)
        val failure = CountDownLatch(1)
        val events = CopyOnWriteArrayList<SessionEvent>()

        coordinator.launch(
            item = testItem(),
            onEvent = { event ->
                events += event
                if (event is SessionEvent.Failure) failure.countDown()
            },
        ) {
            if (it is LaunchResult.Connected) connected.countDown()
        }

        assertTrue("runtime session did not connect", connected.await(5, TimeUnit.SECONDS))
        engine.publish(SessionEvent.Failure("frame loop stopped"))
        assertTrue("runtime failure was not forwarded", failure.await(5, TimeUnit.SECONDS))

        coordinator.clearSession()

        assertTrue("active session was not closed", engine.closed.await(5, TimeUnit.SECONDS))
        assertTrue(
            "capability snapshot was not forwarded",
            events.any {
                it == SessionEvent.CapabilitySnapshot(setOf(Capability.SoftwareVideo, Capability.RetropadInput))
            },
        )
        assertTrue(events.any { it == SessionEvent.Failure("frame loop stopped") })
    }

    @Test
    fun unsupportedBatteryMemoryRemainsDistinctFromARejectedSaveWrite() {
        val engine = FakeEngine()
        val coordinator = EmulationSessionCoordinator(engine, scope, mainDispatcher = Dispatchers.Default)
        val connected = CountDownLatch(1)
        coordinator.launch(testItem()) { if (it is LaunchResult.Connected) connected.countDown() }
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        fun save(result: CommandResult): RuntimeCommandPreview {
            engine.saveResult = result
            val complete = CountDownLatch(1)
            var preview: RuntimeCommandPreview? = null
            coordinator.command(SessionCommand.SaveNative) { preview = it; complete.countDown() }
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            return requireNotNull(preview)
        }
        val unsupported = save(CommandResult.Unsupported(Capability.BatterySave))
        assertFalse(unsupported.accepted)
        assertEquals(Capability.BatterySave, unsupported.unsupportedCapability)
        val failed = save(CommandResult.Rejected("disk write failed"))
        assertFalse(failed.accepted)
        assertNull(failed.unsupportedCapability)
        assertEquals("disk write failed", failed.message)
        coordinator.clearSession()
    }

    private fun testItem() = LibraryItem(
        id = "test-id",
        contentId = "test-content",
        contentSizeBytes = 1L,
        title = "Test title",
        system = "Game Boy",
        sourceUri = null,
        sourceDisplayName = "test.gb",
        privateContentRef = "/data/user/0/test/content/test.gb",
        coreId = "sameboy",
        metadataStatus = MetadataStatus.Verified,
        supportStatus = SupportStatus.Playable,
        importedAt = 1L,
    )

    private class FakeEngine : EmulationEngine {
        var saveResult: CommandResult = CommandResult.Accepted
        val closed = CountDownLatch(1)
        private val handle = SessionHandle(7L)
        private val listeners = CopyOnWriteArrayList<SessionEventListener>()

        override suspend fun createSession(request: SessionRequest): Result<SessionHandle> = Result.success(handle)

        override fun observe(session: SessionHandle, listener: SessionEventListener): SessionEventSubscription {
            listeners += listener
            return SessionEventSubscription { listeners.remove(listener) }
        }

        override fun capabilities(session: SessionHandle): Set<Capability> =
            setOf(Capability.SoftwareVideo, Capability.RetropadInput)

        override suspend fun command(session: SessionHandle, command: SessionCommand): CommandResult {
            if (command == SessionCommand.Start) publish(SessionEvent.StateChanged(dev.codex.libretroplatform.runtime.api.SessionState.Running))
            if (command == SessionCommand.SaveNative) return saveResult
            return CommandResult.Accepted
        }

        fun publish(event: SessionEvent) {
            listeners.forEach { it.onEvent(event) }
        }

        override suspend fun closeSession(session: SessionHandle): CommandResult {
            closed.countDown()
            listeners.clear()
            return CommandResult.Accepted
        }
    }
}
