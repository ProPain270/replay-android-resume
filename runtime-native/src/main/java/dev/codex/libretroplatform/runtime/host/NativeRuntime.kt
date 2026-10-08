package dev.codex.libretroplatform.runtime.host

class NativeRuntime {
    @Volatile
    private var loaded = false

    /** Loads JNI only when the first real runtime session is requested. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("retro_runtime")
                loaded = true
            }
        }
    }

    external fun nativeBuildId(): String

    external fun nativeSessionCreate(): Long

    /**
     * Creates a session for a caller-owned dynamic Libretro core and content
     * path. The return value is a positive handle or the negated native result
     * code; paths must already be inside the app's approved content boundary.
     */
    external fun nativeSessionCreateWithCore(
        corePath: String,
        contentPath: String,
        systemDirectory: String? = null,
        saveDirectory: String? = null,
    ): Long

    external fun nativeSessionClose(handle: Long): Int

    external fun nativeSessionStart(handle: Long): Int

    external fun nativeSessionPause(handle: Long): Int

    external fun nativeSessionResume(handle: Long): Int

    /** Returns the active core's reported PCM sample rate after Start. */
    external fun nativeSessionAudioSampleRate(handle: Long): Int

    /** Returns the most recently posted native frame geometry as width/height. */
    external fun nativeSessionVideoGeometry(handle: Long): IntArray?

    /** Versioned performance snapshot, nanosecond durations, -1 for unavailable values. */
    external fun nativeSessionPerformance(handle: Long): LongArray?

    /** Loaded core's av_info.timing.fps, or 0 when unavailable/invalid. */
    external fun nativeSessionFramesPerSecond(handle: Long): Double

    external fun nativeSessionSaveNative(handle: Long, path: String): Int

    external fun nativeSessionLoadNative(handle: Long, path: String): Int

    external fun nativeSessionSaveState(handle: Long, path: String): Int

    external fun nativeSessionLoadState(handle: Long, path: String): Int

    /** Runs exactly one retro_run call on the session's dedicated native thread. */
    external fun nativeSessionRunFrame(handle: Long): Int

    external fun nativeSessionAttachSurface(handle: Long, surface: android.view.Surface): Int

    external fun nativeSessionDetachSurface(handle: Long): Int

    external fun nativeSessionSetInputMask(handle: Long, mask: Int): Int

    /** Sets a normalized Libretro analog axis as a signed 16-bit value. */
    external fun nativeSessionSetAnalog(handle: Long, axisId: Int, value: Int): Int

    /** Reads interleaved stereo PCM frames into a caller-owned short array. */
    external fun nativeSessionReadAudio(handle: Long, destination: ShortArray): Int
}
