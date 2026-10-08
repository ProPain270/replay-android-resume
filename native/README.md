# Native Libretro boundary

`retro_runtime_core` owns one dedicated worker per session. Libretro entry
points and frontend callbacks are called only from that worker. A caller must
provide a dynamic core path and content path to create a real session; the
host does not bundle a core, ROM, BIOS, or other emulator asset.

## JNI surface

The existing methods remain compatible:

- `nativeSessionCreate()` creates a lifecycle-only placeholder session for old
  callers and preserves the legacy negative result for the unimplemented
  loader.
- `nativeSessionStart/Pause/Resume/Close(handle)` retain their existing
  synchronous result semantics.

New methods are:

- `nativeSessionCreateWithCore(corePath, contentPath, systemDirectory,
  saveDirectory)` returns a positive handle, or the negated result code.
  `systemDirectory` and `saveDirectory` may be null; the core receives them
  through the Libretro environment callback.
- `nativeSessionRunFrame(handle)` performs exactly one `retro_run` on the
  session worker and returns a result code. It is intentionally explicit so
  the current host does not start an unbounded native frame loop before the
  renderer/audio consumers are integrated.

Stable result codes are:

| Code | Name |
| ---: | --- |
| 0 | `ok` |
| 1 | `invalid_handle` |
| 2 | `invalid_state` |
| 3 | `session_closed` |
| 4 | `libretro_loading_not_implemented` (legacy) |
| 5 | `internal_error` |
| 6 | `invalid_argument` |
| 7 | `core_load_failed` |
| 8 | `core_missing_symbol` |
| 9 | `core_api_mismatch` |
| 10 | `content_load_failed` |
| 11 | `core_operation_failed` |

For a core that does not request full paths, the host reads the caller's
content file and passes both `retro_game_info::path` and an in-memory data
buffer. Full-path cores receive the path and no data buffer. Video frames and
audio batches are copied before crossing the C++ callback boundary; input and
environment callbacks execute synchronously while the core is on the worker.

The native harness builds a synthetic shared library at test time. It is not
an emulator core and no copyrighted content is checked into the repository.
