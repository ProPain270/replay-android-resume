package dev.codex.libretroplatform.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeQualityTest {
    @Test
    fun coreTimingUsesReportedFpsAndSafeFallback() {
        assertEquals(60.0, CoreFramePacing(60.0).reportedFramesPerSecond)
        assertEquals(16_666_667L, CoreFramePacing(60.0).intervalNanos)
        assertEquals(null, CoreFramePacing(0.0).reportedFramesPerSecond)
        assertEquals(16_666_667L, CoreFramePacing(0.0).intervalNanos)
        assertEquals(null, CoreFramePacing(Double.NaN).reportedFramesPerSecond)
        assertEquals(null, CoreFramePacing(1001.0).reportedFramesPerSecond)
    }

    @Test
    fun diagnosticsCadenceThrottlesPeriodicSnapshotsButAllowsLifecycleFlush() {
        val cadence = DiagnosticsCadence()
        assertTrue(cadence.shouldPublish(1_000_000_000L))
        assertFalse(cadence.shouldPublish(1_500_000_000L))
        assertTrue(cadence.shouldPublish(1_500_000_000L, force = true))
        assertTrue(cadence.shouldPublish(2_500_000_000L))
    }
}
