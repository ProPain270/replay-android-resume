package dev.codex.libretroplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCatalogTest {
    @Test
    fun recognizesBundledAndFutureConsoleFileTypes() {
        assertEquals("Game Boy", ConsoleCatalog.forFileName("Tetris.GB")?.displayName)
        assertEquals("Game Boy Advance", ConsoleCatalog.forFileName("metroid.gba")?.displayName)
        assertEquals("Super Nintendo", ConsoleCatalog.forFileName("zelda.sfc")?.displayName)
        assertEquals("Super Nintendo", ConsoleCatalog.forFileName("satellaview.bs")?.displayName)
        assertEquals("Nintendo 64", ConsoleCatalog.forFileName("mario.z64")?.displayName)
        assertEquals("Nintendo GameCube", ConsoleCatalog.forFileName("wind-waker.rvz")?.displayName)
    }

    @Test
    fun futureSystemsAreRecognizedButNotClaimedPlayable() {
        val n64 = ConsoleCatalog.forSystem("Nintendo 64") ?: error("N64 profile missing")
        assertFalse(n64.bundled)
        assertEquals(SupportStatus.NeedsCore, n64.status)
        assertTrue(n64.requiresHardwareVideo)
    }

    @Test
    fun snesIsPlayableThroughTheBundledSoftwareLane() {
        val snes = ConsoleCatalog.forSystem("snes") ?: error("SNES profile missing")
        assertEquals("snes9x", snes.coreId)
        assertEquals(SupportStatus.Playable, snes.status)
        assertTrue(snes.bundled)
        assertFalse(snes.requiresHardwareVideo)
    }

    @Test
    fun displayRatiosRemainNativePerConsoleFamily() {
        assertEquals(10f / 9f, ConsoleCatalog.forSystem("Game Boy")?.aspectRatio)
        assertEquals(3f / 2f, ConsoleCatalog.forSystem("Game Boy Advance")?.aspectRatio)
        assertEquals(4f / 3f, ConsoleCatalog.forSystem("Super Nintendo")?.aspectRatio)
    }
}
