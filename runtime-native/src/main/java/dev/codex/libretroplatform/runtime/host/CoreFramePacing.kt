package dev.codex.libretroplatform.runtime.host

import kotlin.math.roundToLong

/** One retro_run per reported core interval; no frame skipping or display-refresh assumptions. */
internal class CoreFramePacing(reportedFps: Double) {
    val reportedFramesPerSecond: Double? = reportedFps.takeIf { it.isFinite() && it in 1.0..1000.0 }
    val intervalNanos: Long = (1_000_000_000.0 / (reportedFramesPerSecond ?: 60.0)).roundToLong()
}
