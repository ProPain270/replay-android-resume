package dev.codex.libretroplatform

import org.junit.Assert.*
import org.junit.Test

class PlayerGeometryTest {
    @Test fun viewportPreservesAspectAndFitsEveryWindow() {
        for ((w, h) in listOf(320f to 580f, 412f to 780f, 800f to 330f, 740f to 700f, 280f to 360f)) {
            for (aspect in listOf(10f / 9f, 3f / 2f, 4f / 3f)) {
                val layout = playerGeometry(w, h, aspect)
                assertEquals(aspect, layout.viewport.width / layout.viewport.height, .001f)
                for (rect in listOf(layout.viewport, layout.controls)) {
                    assertTrue(rect.x >= 0 && rect.y >= 0)
                    assertTrue(rect.right <= w + .01f && rect.bottom <= h + .01f)
                }
                if (layout.profile != ControlProfile.Landscape) assertFalse(layout.viewport.overlaps(layout.controls))
            }
        }
    }

    @Test fun tabletopAndBookKeepControlsAndPictureAwayFromHinge() {
        val horizontal = FoldBounds(DeckRect(0f, 340f, 740f, 14f), true, true)
        val tabletop = playerGeometry(740f, 720f, 4f / 3f, horizontal)
        assertEquals(ControlProfile.Tabletop, tabletop.profile)
        assertTrue(tabletop.viewport.bottom < horizontal.bounds.y)
        assertTrue(tabletop.controls.y > horizontal.bounds.bottom)
        val vertical = FoldBounds(DeckRect(360f, 0f, 14f, 700f), false, true)
        val book = playerGeometry(760f, 700f, 10f / 9f, vertical)
        assertEquals(ControlProfile.Book, book.profile)
        assertTrue(book.viewport.right < vertical.bounds.x)
        assertTrue(book.controls.x > vertical.bounds.right)
    }

    @Test fun nonSeparatingFoldDoesNotArtificiallySplitPicture() {
        val feature = FoldBounds(DeckRect(0f, 300f, 700f, 0f), true, false)
        assertEquals(playerGeometry(700f, 700f, 4f / 3f), playerGeometry(700f, 700f, 4f / 3f, feature))
    }

    @Test fun handheldAndSnesUseTheirActualButtons() {
        val handheld = controlNodes(390f, 230f, InputFamily.Handheld, "gb", UserPreferences(), ControlProfile.Portrait)
        assertEquals(setOf(ControlId.Dpad, ControlId.A, ControlId.B, ControlId.Start, ControlId.Select), handheld.map { it.id }.toSet())
        val snes = controlNodes(390f, 230f, InputFamily.Snes, "snes", UserPreferences(), ControlProfile.Portrait)
        assertTrue(snes.map { it.id }.containsAll(listOf(ControlId.X, ControlId.Y, ControlId.L, ControlId.R)))
        assertTrue(snes.first { it.id == ControlId.X }.bounds.y < snes.first { it.id == ControlId.B }.bounds.y)
    }

    @Test fun everyControlRemainsWithinDeckAtExtremeSavedPositions() {
        for (profile in ControlProfile.values()) {
            val preferences = UserPreferences(touchLayout = TouchLayoutAdjustments(controls = ControlId.values().associate { id ->
                controlKey(profile, id) to ControlPlacement(.45f, -.45f, 1.3f)
            }))
            val nodes = controlNodes(320f, 210f, InputFamily.Snes, "snes", preferences, profile)
            nodes.forEach { node ->
                assertTrue(node.bounds.x >= 0 && node.bounds.y >= 0)
                assertTrue(node.bounds.right <= 320.01f && node.bounds.bottom <= 210.01f)
            }
        }
    }

    @Test fun layoutProfilesRemainIndependentAndRejectNonFiniteData() {
        val preferences = UserPreferences(touchLayout = TouchLayoutAdjustments(controls = mapOf(
            controlKey(ControlProfile.Portrait, ControlId.A) to ControlPlacement(-.04f, .03f, .8f),
        )), playerPresentation = PlayerPresentation.Classic)
        val restored = UserPreferencesBackupCodec.decode(UserPreferencesBackupCodec.encode(preferences))
        assertEquals(preferences, restored)
        val landscape = controlNodes(800f, 320f, InputFamily.Handheld, "gb", restored, ControlProfile.Landscape)
        val defaults = controlNodes(800f, 320f, InputFamily.Handheld, "gb", UserPreferences(), ControlProfile.Landscape)
        assertEquals(defaults, landscape)
        assertNull(TouchLayoutAdjustmentsCodec.decode("NaN,0,0,0"))
        assertEquals(0f, TouchLayoutAdjustments(dpadX = Float.NaN).sanitized().dpadX)
        val title = TitlePlayerPreferences(playerPresentation = PlayerPresentation.Classic, touchLayout = preferences.touchLayout)
        assertEquals(title, TitlePlayerPreferencesCodec.decode(TitlePlayerPreferencesCodec.encode(title)))
    }

    @Test fun landscapeControlsDoNotCoverTheGameAtDefaultSize() {
        val layout = playerGeometry(800f, 320f, 10f / 9f)
        val nodes = controlNodes(800f, 320f, InputFamily.Handheld, "gb", UserPreferences(), ControlProfile.Landscape)
        assertTrue(nodes.all { !it.bounds.overlaps(layout.viewport) })
    }

    @Test fun narrowBookPaneStacksReachableControlsAndKeepsSystemLabelsReadable() {
        for (system in listOf("gb", "gba", "snes")) {
            val family = if (system == "snes") InputFamily.Snes else InputFamily.Handheld
            val nodes = controlNodes(190f, 780f, family, system, UserPreferences(), ControlProfile.Book)
            val dpad = nodes.single { it.id == ControlId.Dpad }.bounds
            val faces = nodes.filter { it.id in listOf(ControlId.A, ControlId.B, ControlId.X, ControlId.Y) }
            assertTrue(faces.all { it.bounds.y > dpad.bottom })
            nodes.filter { it.id == ControlId.Start || it.id == ControlId.Select }.forEach {
                assertTrue(it.bounds.width >= 52f && it.bounds.height >= 28f)
            }
            assertTrue(nodes.all { safeControlPlacement(nodes, it.id) })
            assertTrue(nodes.maxOf { it.bounds.bottom } - nodes.minOf { it.bounds.y } < 420f)
        }
    }

    @Test fun oneHandedReachMovesTheWholeDeckWithoutCoveringLandscapePicture() {
        val geometry = playerGeometry(800f, 320f, 10f / 9f)
        for (mode in listOf(TouchReachMode.OneHandedLeft, TouchReachMode.OneHandedRight)) {
            val nodes = controlNodes(800f, 320f, InputFamily.Handheld, "gb",
                UserPreferences(touchReachMode = mode), ControlProfile.Landscape)
            assertTrue(nodes.all { !it.bounds.overlaps(geometry.viewport) })
            assertTrue(nodes.all { safeControlPlacement(nodes, it.id) })
            if (mode == TouchReachMode.OneHandedLeft) assertTrue(nodes.all { it.bounds.right <= 160f })
            else assertTrue(nodes.all { it.bounds.x >= 640f })
        }
    }
}
