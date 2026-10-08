package dev.codex.libretroplatform.runtime.host

import dev.codex.libretroplatform.runtime.api.SessionDiagnostics

/** Decode only known complete snapshots. Old/mismatched JNI must not invent zero measurements. */
internal fun SessionDiagnostics.withNativePerformance(values: LongArray?): SessionDiagnostics {
    if (values == null || values.size < 15 || values[0] != 1L) return this
    fun count(index: Int): Long? = values[index].takeIf { it >= 0L }
    fun milliseconds(index: Int): Double? = count(index)?.div(1_000_000.0)
    return copy(
        frameCount = count(1),
        framesExecuted = count(2),
        frameFailures = count(3),
        frameDurationMeanMs = milliseconds(4),
        frameDurationP95UpperBoundMs = milliseconds(5),
        frameDurationMaxMs = milliseconds(6),
        startupDurationMs = milliseconds(7),
        firstFrameDurationMs = milliseconds(8),
        sessionDurationMs = milliseconds(9),
        videoCallbacks = count(10),
        framesRendered = count(11),
        framesDropped = count(12),
        duplicateFrames = count(13),
        firstVideoFrameDurationMs = milliseconds(14),
    )
}
