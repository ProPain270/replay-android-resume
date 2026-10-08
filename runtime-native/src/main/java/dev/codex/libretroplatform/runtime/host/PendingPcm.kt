package dev.codex.libretroplatform.runtime.host

/**
 * Single-session scratch queue. Caller holds its session monitor. Nonblocking
 * writes may consume fewer samples (including zero); retain the exact suffix
 * and drain it before reading more PCM from native. No per-pump allocation.
 */
internal class PendingPcm(
    private val readFrames: (ShortArray) -> Int,
    private val writeSamples: (ShortArray, Int, Int) -> Int,
) {
    private val samples = ShortArray(4096)
    private var offset = 0
    private var limit = 0

    fun pump(): Int {
        if (offset < limit) {
            val result = flush()
            if (result < 0 || offset < limit) return result
        }
        val frames = readFrames(samples)
        if (frames < 0) return frames
        if (frames > samples.size / 2) return -1
        offset = 0
        limit = frames * 2
        return if (limit == 0) 0 else flush()
    }

    private fun flush(): Int {
        val written = writeSamples(samples, offset, limit - offset)
        if (written < 0) return written
        if (written > limit - offset) return -1
        offset += written
        return 0
    }
}
