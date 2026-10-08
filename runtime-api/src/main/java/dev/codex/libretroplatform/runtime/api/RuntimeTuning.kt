package dev.codex.libretroplatform.runtime.api

/** Renderer names are a stable policy seam; hardware implementations can be added without leaking into the app API. */
enum class RenderBackendKind {
    SoftwareFramebuffer,
    OpenGLES,
    Vulkan,
}

data class RuntimeTelemetry(
    val frameTimeMs: Double? = null,
    val audioUnderruns: Int = 0,
    val memoryBytes: Long? = null,
    val thermalStatus: Int? = null,
)

data class RuntimeProfile(
    val backend: RenderBackendKind = RenderBackendKind.SoftwareFramebuffer,
    val targetFramePacingMs: Double = 16.67,
    val audioLatencyHintMs: Int = 48,
    val allowVisualEffects: Boolean = true,
)

/** Conservative recommendation logic. It reports policy; it never silently changes core accuracy options. */
object PerformanceManager {
    fun recommend(
        preset: RuntimePerformancePreset,
        telemetry: RuntimeTelemetry = RuntimeTelemetry(),
        hardwareVideoAvailable: Boolean = false,
    ): RuntimeProfile {
        val hot = telemetry.thermalStatus?.let { it >= 4 } == true
        return when (preset) {
            RuntimePerformancePreset.Balanced -> RuntimeProfile(
                backend = RenderBackendKind.SoftwareFramebuffer,
                targetFramePacingMs = 16.67,
                audioLatencyHintMs = 48,
                allowVisualEffects = !hot,
            )
            RuntimePerformancePreset.BatterySaver -> RuntimeProfile(
                backend = RenderBackendKind.SoftwareFramebuffer,
                targetFramePacingMs = 16.67,
                audioLatencyHintMs = 72,
                allowVisualEffects = false,
            )
            RuntimePerformancePreset.LowLatency -> RuntimeProfile(
                backend = if (hardwareVideoAvailable) RenderBackendKind.OpenGLES else RenderBackendKind.SoftwareFramebuffer,
                targetFramePacingMs = 16.67,
                audioLatencyHintMs = 32,
                allowVisualEffects = !hot,
            )
        }
    }
}

enum class RuntimePerformancePreset {
    Balanced,
    BatterySaver,
    LowLatency,
}
