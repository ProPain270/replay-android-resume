package dev.codex.libretroplatform.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTuningTest {
    @Test
    fun batterySaverDisablesVisualEffectsAndRaisesAudioHeadroom() {
        val profile = PerformanceManager.recommend(RuntimePerformancePreset.BatterySaver)

        assertEquals(RenderBackendKind.SoftwareFramebuffer, profile.backend)
        assertEquals(72, profile.audioLatencyHintMs)
        assertFalse(profile.allowVisualEffects)
    }

    @Test
    fun lowLatencyUsesHardwareOnlyWhenItIsActuallyAvailable() {
        assertEquals(
            RenderBackendKind.SoftwareFramebuffer,
            PerformanceManager.recommend(RuntimePerformancePreset.LowLatency, hardwareVideoAvailable = false).backend,
        )
        assertEquals(
            RenderBackendKind.OpenGLES,
            PerformanceManager.recommend(RuntimePerformancePreset.LowLatency, hardwareVideoAvailable = true).backend,
        )
    }

    @Test
    fun thermalPressureTurnsOffOptionalEffectsEvenInBalancedMode() {
        val profile = PerformanceManager.recommend(
            RuntimePerformancePreset.Balanced,
            telemetry = RuntimeTelemetry(thermalStatus = 4),
        )

        assertTrue(profile.targetFramePacingMs > 0)
        assertFalse(profile.allowVisualEffects)
    }
}
