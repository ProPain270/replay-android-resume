package dev.codex.libretroplatform.runtime.host

import android.view.Surface
import dev.codex.libretroplatform.runtime.api.CommandResult
import dev.codex.libretroplatform.runtime.api.SessionHandle

/** Optional Android rendering seam; the public runtime API remains core-independent. */
interface SurfaceBindable {
    suspend fun attachSurface(session: SessionHandle, surface: Surface): CommandResult
    suspend fun detachSurface(session: SessionHandle): CommandResult
}
