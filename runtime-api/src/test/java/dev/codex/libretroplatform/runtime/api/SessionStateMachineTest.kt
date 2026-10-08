package dev.codex.libretroplatform.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun startsInCreated() {
        assertEquals(SessionState.Created, SessionStateMachine().state)
    }

    @Test
    fun startMovesCreatedToRunning() {
        val machine = SessionStateMachine()

        assertAccepted(machine.command(SessionCommand.Start))
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun pauseMovesRunningToPaused() {
        val machine = runningMachine()

        assertAccepted(machine.command(SessionCommand.Pause))
        assertEquals(SessionState.Paused, machine.state)
    }

    @Test
    fun resumeMovesPausedToRunning() {
        val machine = runningMachine()
        assertAccepted(machine.command(SessionCommand.Pause))

        assertAccepted(machine.command(SessionCommand.Resume))
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun closeCanBeRequestedFromCreatedRunningPausedAndFailed() {
        val created = SessionStateMachine()
        assertAccepted(created.requestClose())
        assertEquals(SessionState.Closing, created.state)

        val running = runningMachine()
        assertAccepted(running.requestClose())
        assertEquals(SessionState.Closing, running.state)

        val paused = runningMachine()
        assertAccepted(paused.command(SessionCommand.Pause))
        assertAccepted(paused.requestClose())
        assertEquals(SessionState.Closing, paused.state)

        val failed = runningMachine()
        assertAccepted(failed.fail("native failure"))
        assertAccepted(failed.requestClose())
        assertEquals(SessionState.Closing, failed.state)
    }

    @Test
    fun completeCloseMovesClosingToClosed() {
        val machine = SessionStateMachine()
        assertAccepted(machine.requestClose())

        assertAccepted(machine.completeClose())
        assertEquals(SessionState.Closed, machine.state)
    }

    @Test
    fun failureMovesCreatedRunningPausedAndClosingToFailed() {
        val created = SessionStateMachine()
        assertAccepted(created.fail("created failure"))
        assertEquals(SessionState.Failed, created.state)

        val running = runningMachine()
        assertAccepted(running.fail("running failure"))
        assertEquals(SessionState.Failed, running.state)

        val paused = runningMachine()
        assertAccepted(paused.command(SessionCommand.Pause))
        assertAccepted(paused.fail("paused failure"))
        assertEquals(SessionState.Failed, paused.state)

        val closing = SessionStateMachine()
        assertAccepted(closing.requestClose())
        assertAccepted(closing.fail("close failure"))
        assertEquals(SessionState.Failed, closing.state)
    }

    @Test
    fun nativeSaveIsAcceptedInRunningAndPausedWhenBatterySaveIsSupported() {
        val running = runningMachine(Capability.BatterySave)
        assertAccepted(running.command(SessionCommand.SaveNative))
        assertEquals(SessionState.Running, running.state)

        val paused = runningMachine(Capability.BatterySave)
        assertAccepted(paused.command(SessionCommand.Pause))
        assertAccepted(paused.command(SessionCommand.SaveNative))
        assertEquals(SessionState.Paused, paused.state)
    }

    @Test
    fun inputIsAcceptedInRunningWhenRetropadIsSupported() {
        val machine = runningMachine(Capability.RetropadInput)

        assertAccepted(
            machine.command(
                SessionCommand.Input(
                    port = 0,
                    input = LogicalInput(setOf(Button.A, Button.Start)),
                ),
            ),
        )
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun startIsRejectedFromEveryStateExceptCreated() {
        val states = listOf(
            SessionState.Running,
            SessionState.Paused,
            SessionState.Closing,
            SessionState.Closed,
            SessionState.Failed,
        )

        states.forEach { state ->
            val machine = machineIn(state)
            assertRejected(machine.command(SessionCommand.Start))
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun pauseIsRejectedFromEveryStateExceptRunning() {
        val states = listOf(
            SessionState.Created,
            SessionState.Paused,
            SessionState.Closing,
            SessionState.Closed,
            SessionState.Failed,
        )

        states.forEach { state ->
            val machine = machineIn(state)
            assertRejected(machine.command(SessionCommand.Pause))
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun resumeIsRejectedFromEveryStateExceptPaused() {
        val states = listOf(
            SessionState.Created,
            SessionState.Running,
            SessionState.Closing,
            SessionState.Closed,
            SessionState.Failed,
        )

        states.forEach { state ->
            val machine = machineIn(state)
            assertRejected(machine.command(SessionCommand.Resume))
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun nativeSaveIsRejectedFromEveryStateExceptRunningOrPaused() {
        val states = listOf(
            SessionState.Created,
            SessionState.Closing,
            SessionState.Closed,
            SessionState.Failed,
        )

        states.forEach { state ->
            val machine = machineIn(state, Capability.BatterySave)
            assertRejected(machine.command(SessionCommand.SaveNative))
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun nativeSaveReturnsUnsupportedWithoutBatterySaveAndDoesNotChangeState() {
        val machine = runningMachine()

        assertUnsupported(
            result = machine.command(SessionCommand.SaveNative),
            capability = Capability.BatterySave,
        )
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun inputIsRejectedFromEveryStateExceptRunning() {
        val states = listOf(
            SessionState.Created,
            SessionState.Paused,
            SessionState.Closing,
            SessionState.Closed,
            SessionState.Failed,
        )

        states.forEach { state ->
            val machine = machineIn(state, Capability.RetropadInput)
            assertRejected(machine.command(validInput()))
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun inputReturnsUnsupportedWithoutRetropadAndDoesNotChangeState() {
        val machine = runningMachine()

        assertUnsupported(
            result = machine.command(validInput()),
            capability = Capability.RetropadInput,
        )
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun negativeInputPortIsRejectedBeforeCapabilityCheck() {
        val machine = runningMachine()

        val result = machine.command(
            SessionCommand.Input(
                port = -1,
                input = LogicalInput(),
            ),
        )

        assertRejected(result)
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun closeRequestIsRejectedFromClosingAndClosed() {
        val closing = SessionStateMachine()
        assertAccepted(closing.requestClose())
        assertRejected(closing.requestClose())
        assertEquals(SessionState.Closing, closing.state)

        assertAccepted(closing.completeClose())
        assertRejected(closing.requestClose())
        assertEquals(SessionState.Closed, closing.state)
    }

    @Test
    fun completeCloseIsRejectedFromEveryStateExceptClosing() {
        val states = listOf(
            SessionState.Created,
            SessionState.Running,
            SessionState.Paused,
            SessionState.Failed,
            SessionState.Closed,
        )

        states.forEach { state ->
            val machine = machineIn(state)
            assertRejected(machine.completeClose())
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun failureWithBlankReasonIsRejectedWithoutChangingState() {
        val machine = runningMachine()

        assertRejected(machine.fail("   "))
        assertEquals(SessionState.Running, machine.state)
    }

    @Test
    fun failureIsRejectedFromFailedAndClosed() {
        val failed = SessionStateMachine()
        assertAccepted(failed.fail("failure"))
        assertRejected(failed.fail("second failure"))
        assertEquals(SessionState.Failed, failed.state)

        val closed = SessionStateMachine()
        assertAccepted(closed.requestClose())
        assertAccepted(closed.completeClose())
        assertRejected(closed.fail("late failure"))
        assertEquals(SessionState.Closed, closed.state)
    }

    @Test
    fun capabilityInputIsDefensivelyCopied() {
        val mutableCapabilities = mutableSetOf(Capability.RetropadInput)
        val machine = runningMachine(mutableCapabilities)
        mutableCapabilities.clear()

        assertAccepted(machine.command(validInput()))
    }

    @Test
    fun initialStateCanBeSelectedForDeterministicRecoveryTests() {
        val machine = SessionStateMachine(
            capabilities = setOf(Capability.BatterySave),
            initialState = SessionState.Paused,
        )

        assertEquals(SessionState.Paused, machine.state)
        assertAccepted(machine.command(SessionCommand.SaveNative))
    }

    private fun runningMachine(vararg capabilities: Capability): SessionStateMachine =
        runningMachine(capabilities.toSet())

    private fun runningMachine(capabilities: Set<Capability>): SessionStateMachine {
        val machine = SessionStateMachine(capabilities)
        assertAccepted(machine.command(SessionCommand.Start))
        return machine
    }

    private fun machineIn(
        state: SessionState,
        vararg capabilities: Capability,
    ): SessionStateMachine = machineIn(state, capabilities.toSet())

    private fun machineIn(
        state: SessionState,
        capabilities: Set<Capability>,
    ): SessionStateMachine = SessionStateMachine(capabilities, state)

    private fun validInput(): SessionCommand = SessionCommand.Input(
        port = 0,
        input = LogicalInput(setOf(Button.A)),
    )

    private fun assertAccepted(result: CommandResult) {
        assertEquals(CommandResult.Accepted, result)
    }

    private fun assertRejected(result: CommandResult) {
        assertTrue("Expected Rejected but was $result", result is CommandResult.Rejected)
    }

    private fun assertUnsupported(result: CommandResult, capability: Capability) {
        assertEquals(CommandResult.Unsupported(capability), result)
    }
}
