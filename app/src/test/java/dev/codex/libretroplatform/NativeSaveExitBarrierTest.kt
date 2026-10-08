package dev.codex.libretroplatform

import org.junit.Assert.*
import org.junit.Test

class NativeSaveExitBarrierTest {
    @Test fun exitWaitsForAFreshPostPauseSnapshotNotTheOlderSuccessfulSave() {
        val saves = CoalescingSaves()
        saves.memory = 10
        saves.save {}
        // Gameplay advances while the earlier capture is being committed.
        saves.memory = 20
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(saves.busy, saves::save, { true }, results::add)
        assertEquals(listOf(10), saves.captures)

        saves.finish(true)
        assertEquals("A second, post-pause capture must actually start", listOf(10, 20), saves.captures)
        assertTrue("The old successful write cannot authorize exit", results.isEmpty())
        assertTrue(saves.busy)

        saves.finish(true)
        assertEquals(listOf(true), results)
        assertEquals(20, saves.committed)
        assertFalse(saves.busy)
    }

    @Test fun freshFailureKeepsExitFailedEvenWhenTheOlderWriteSucceededAndAllowsRetry() {
        val saves = CoalescingSaves()
        saves.save {}
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(true, saves::save, { true }, results::add)
        saves.finish(true)
        saves.finish(false)
        assertEquals(listOf(false), results)
        assertFalse(saves.busy)
        assertEquals(2, saves.captures.size)

        saveFreshNativeForExit(false, saves::save, { true }, results::add)
        saves.finish(true)
        assertEquals(listOf(false, true), results)
        assertEquals(3, saves.captures.size)
    }

    @Test fun anOlderFailureDoesNotPreventOneFreshAttempt() {
        val saves = CoalescingSaves()
        saves.save {}
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(true, saves::save, { true }, results::add)
        saves.finish(false)
        assertEquals(2, saves.captures.size)
        assertTrue(results.isEmpty())
        saves.finish(true)
        assertEquals(listOf(true), results)
    }

    @Test fun noPendingTransferNeedsOnlyOneCapture() {
        val saves = CoalescingSaves()
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(false, saves::save, { true }, results::add)
        assertEquals(1, saves.captures.size)
        saves.finish(true)
        assertEquals(listOf(true), results)
        assertEquals(1, saves.captures.size)
    }

    @Test fun earlierWaiterStartingANewCaptureDoesNotCauseAThirdWriteOrAStaleExit() {
        val saves = CoalescingSaves()
        saves.memory = 10
        saves.save { saves.save {} }
        saves.memory = 20
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(true, saves::save, { true }, results::add)
        saves.finish(true)
        assertEquals(listOf(10, 20), saves.captures)
        assertTrue(results.isEmpty())
        saves.finish(true)
        assertEquals(listOf(true), results)
        assertEquals("Join the already fresh capture, do not recapture again", 2, saves.captures.size)
    }

    @Test fun sessionInvalidationWhileWaitingCannotSaveOrExitTheReplacementSession() {
        val saves = CoalescingSaves()
        saves.save {}
        var current = true
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(true, saves::save, { current }, results::add)
        current = false
        saves.finish(true)
        assertEquals(1, saves.captures.size)
        assertTrue(results.isEmpty())
    }

    @Test fun sessionInvalidationDuringFreshCaptureSuppressesExitCompletion() {
        val saves = CoalescingSaves()
        var current = true
        val results = mutableListOf<Boolean>()
        saveFreshNativeForExit(false, saves::save, { current }, results::add)
        current = false
        saves.finish(true)
        assertTrue(results.isEmpty())
    }

    @Test fun synchronousUnsupportedNoOpCompletesOnceWithoutRecaptureLoop() {
        var calls = 0
        val results = mutableListOf<Boolean>()
        // The controller maps typed BatterySave unsupported to success/no-op.
        // Even synchronous/duplicate completion must not recurse indefinitely.
        saveFreshNativeForExit(true, save = { callback ->
            calls++
            callback(true)
            callback(true)
        }, isSessionCurrent = { true }, onComplete = results::add)
        assertEquals(2, calls)
        assertEquals(listOf(true), results)
    }

    @Test fun invalidSessionDoesNotStartAnyTransfer() {
        saveFreshNativeForExit(false, save = { error("must not start") },
            isSessionCurrent = { false }, onComplete = { error("must not complete") })
    }

    /** Mirrors saveNative: capture once; release in-flight before notifying waiters. */
    private class CoalescingSaves {
        var memory = 0
        var committed: Int? = null
        val captures = mutableListOf<Int>()
        var busy = false
            private set
        private var snapshot = 0
        private val waiters = mutableListOf<(Boolean) -> Unit>()

        fun save(onComplete: (Boolean) -> Unit) {
            waiters += onComplete
            if (busy) return
            busy = true
            snapshot = memory
            captures += snapshot
        }

        fun finish(success: Boolean) {
            check(busy)
            if (success) committed = snapshot
            busy = false
            val callbacks = waiters.toList()
            waiters.clear()
            callbacks.forEach { it(success) }
        }
    }
}
