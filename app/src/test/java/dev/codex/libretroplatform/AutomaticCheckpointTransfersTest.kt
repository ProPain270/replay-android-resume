package dev.codex.libretroplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCheckpointTransfersTest {
    @Test
    fun manualTransferFinishesBeforeCoalescedAutomaticRequests() {
        val busy = mutableListOf<Boolean>()
        val transfers = AutomaticCheckpointTransfers(busy::add)
        val completions = mutableListOf<Boolean>()
        var automatic: Long? = null
        val manual = transfers.beginManual()
        assertEquals(1L, manual)
        transfers.requestAutomatic(start = { ticket ->
            automatic = ticket
            assertTrue(transfers.isCurrent(ticket))
        }, onComplete = completions::add)
        transfers.requestAutomatic(start = { ticket ->
            assertTrue(transfers.isCurrent(ticket))
        }, onComplete = completions::add)
        assertTrue(transfers.beginManual() == null)
        assertTrue(completions.isEmpty())

        transfers.finish(manual!!, success = true)
        assertTrue(transfers.busy)
        assertTrue(completions.isEmpty())
        assertEquals(2L, automatic)
        assertTrue(transfers.isCurrent(automatic!!))
        transfers.finish(automatic!!, success = true)

        assertEquals(listOf(true, true), completions)
        assertFalse(transfers.busy)
        assertEquals(listOf(true, false, true, false), busy)
    }

    @Test
    fun cancelInvalidatesTheActiveTransferAndDropsCallbacks() {
        val completions = mutableListOf<Boolean>()
        val transfers = AutomaticCheckpointTransfers()
        val ticket = transfers.beginManual()!!
        transfers.requestAutomatic(start = { error("cancelled request must not start") }, onComplete = completions::add)
        transfers.cancel()

        assertFalse(transfers.busy)
        assertTrue(completions.isEmpty())
        transfers.finish(ticket, success = true)
        assertTrue(completions.isEmpty())
    }

    @Test
    fun requestsJoiningAnActiveCaptureDoNotCaptureAgain() {
        val transfers = AutomaticCheckpointTransfers()
        val completions = mutableListOf<Boolean>()
        var captures = 0
        var ticket = 0L
        transfers.requestAutomatic(start = { ticket = it; captures++ }, onComplete = completions::add)
        transfers.requestAutomatic(start = { captures++ }, onComplete = completions::add)
        transfers.requestAutomatic(start = { captures++ }, onComplete = completions::add)

        assertEquals(1, captures)
        transfers.finish(ticket, success = false)
        assertEquals(listOf(false, false, false), completions)
        assertEquals(1, captures)
        assertFalse(transfers.busy)
    }

    @Test
    fun cancellationInFirstCompletionSuppressesRemainingOldSessionCallbacks() {
        val transfers = AutomaticCheckpointTransfers()
        val completions = mutableListOf<String>()
        var first = 0L
        var replacement = 0L
        transfers.requestAutomatic(start = { first = it }, onComplete = {
            completions += "first"
            transfers.cancel()
            transfers.requestAutomatic(start = { replacement = it }, onComplete = { completions += "replacement" })
        })
        transfers.requestAutomatic(start = { error("must coalesce") }, onComplete = { completions += "stale" })

        transfers.finish(first, success = true)
        assertEquals(listOf("first"), completions)
        assertTrue(transfers.isCurrent(replacement))
        transfers.finish(first, success = false)
        assertTrue(transfers.isCurrent(replacement))
        transfers.finish(replacement, success = true)
        assertEquals(listOf("first", "replacement"), completions)
        assertFalse(transfers.busy)
    }

    @Test
    fun startingAnotherTransferInACompletionDoesNotDiscardOtherCompletedWaiters() {
        val transfers = AutomaticCheckpointTransfers()
        val completions = mutableListOf<String>()
        var first = 0L
        var next = 0L
        transfers.requestAutomatic(start = { first = it }, onComplete = {
            completions += "first"
            transfers.requestAutomatic(start = { next = it }, onComplete = { completions += "next" })
        })
        transfers.requestAutomatic(start = { error("must coalesce") }, onComplete = { completions += "joined" })

        transfers.finish(first, success = true)
        assertEquals(listOf("first", "joined"), completions)
        assertTrue(transfers.isCurrent(next))
        transfers.finish(next, success = true)
        assertEquals(listOf("first", "joined", "next"), completions)
    }

    @Test
    fun failedManualTransferStillDrainsQueuedExitBarrier() {
        val transfers = AutomaticCheckpointTransfers()
        val manual = transfers.beginManual()!!
        var barrier = 0L
        val completions = mutableListOf<Boolean>()
        transfers.requestAutomatic(start = { barrier = it }, onComplete = completions::add)
        transfers.finish(manual, success = false)
        assertTrue(transfers.isCurrent(barrier))
        assertTrue(completions.isEmpty())
        transfers.finish(barrier, success = true)
        assertEquals(listOf(true), completions)
    }

    @Test
    fun staleManualFinallyCannotFinishANewSessionTransfer() {
        val transfers = AutomaticCheckpointTransfers()
        val stale = transfers.beginManual()!!
        transfers.cancel()
        val current = transfers.beginManual()!!
        transfers.finish(stale, success = false)
        assertTrue(transfers.busy)
        assertTrue(transfers.isCurrent(current))
        transfers.finish(current, success = true)
        assertFalse(transfers.busy)
    }
}
