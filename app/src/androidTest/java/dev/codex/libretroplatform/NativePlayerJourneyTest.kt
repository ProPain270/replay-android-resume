package dev.codex.libretroplatform

import android.graphics.Bitmap
import android.graphics.Color
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.FoldingFeature as testFold
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import dev.codex.libretroplatform.runtime.api.Button
import dev.codex.libretroplatform.runtime.api.Capability
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real content import + SameBoy + durable saves. No fabricated Ready/Running UI state. */
@RunWith(AndroidJUnit4::class)
class NativePlayerJourneyTest {
    @get:Rule(order = 0) val folding = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixtureName = "integration-${UUID.randomUUID()}.gb"
    private lateinit var fixtureId: String
    private lateinit var originalPreferences: UserPreferences

    private fun controller(): FrontendController =
        MainActivity::class.java.getDeclaredField("controller").let { field ->
            field.isAccessible = true
            field.get(compose.activity) as FrontendController
        }

    private fun <T> onController(block: (FrontendController) -> T): T =
        compose.runOnIdle { block(controller()) }

    @Before fun prepare() {
        val original = DiagnosticGbFixture.bytes(instrumentation.context)
        assertEquals("Fixture must stay byte-identical to the original diagnostic generator",
            "234fe05406647bdca50e433f05c9018faf2bbc7c2618b434d5b25999c24db6fe",
            DiagnosticGbFixture.sha256(original))
        fixtureId = DiagnosticGbFixture.sha256(DiagnosticGbFixture.bytes(instrumentation.context, fixtureName))
        onController { controller ->
            originalPreferences = controller.uiState.preferences
            controller.updatePreferences {
                UserPreferences(audioMuted = true, hapticsEnabled = false, reduceMotion = true,
                    autoSaveOnBackground = false, autoResumeFromLatestState = false)
            }
        }
    }

    @After fun cleanUpOnlyThisFixture() {
        // Stop native access before removing this test's unique content tree.
        try {
            onController { controller ->
                controller.updatePreferences { it.copy(autoSaveOnBackground = false) }
                controller.exitPlayer()
            }
            await("session release") { it.uiState.playerSession == null }
        } finally {
            if (::originalPreferences.isInitialized) onController { it.updatePreferences { originalPreferences } }
            compose.activityRule.scenario.close()
            if (::fixtureId.isInitialized) {
                val repository = LibraryRepository.fromContext(instrumentation.targetContext)
                repository.load().firstOrNull { it.contentId == fixtureId }?.let { repository.remove(it.id) }
            }
            File(instrumentation.context.cacheDir, fixtureName).delete()
        }
    }

    @Test fun importNativeFrameSaveRecreateLoadAndAutomaticRecovery() {
        importAndLaunch()
        assertPlayerGeometryAndScreenshot("native-player")
        val retainedController = onController { it }
        val retainedSession = onController { it.uiState.playerSession }

        compose.onNodeWithTag("player-pause").performClick()
        await("user pause") { it.uiState.playerState is PlayerState.Paused && !it.checkpointBusy }
        compose.onNodeWithTag("manual-save").performScrollTo().performClick()
        await("verified manual slot") { controller ->
            !controller.checkpointBusy && controller.uiState.statusMessage == "Save state verified and committed to slot 1." &&
                controller.uiState.saveStateSlots.any { it.slot == 1 && it.available && (it.payloadSizeBytes ?: 0) > 0 }
        }
        val manualBefore = onController { it.uiState.saveStateSlots.single { slot -> slot.slot == 1 } }

        // This is Activity recreation with the retained ViewModel, NOT process death.
        compose.activityRule.scenario.recreate()
        await("recreated paused player") { it.uiState.playerState is PlayerState.Paused }
        onController {
            assertSame(retainedController, it)
            assertEquals(retainedSession, it.uiState.playerSession)
            assertEquals(fixtureId, it.selectedItem()?.contentId)
        }
        compose.onNodeWithTag("manual-load").performScrollTo().performClick()
        await("native manual load acknowledgement") { it.uiState.statusMessage == "Save state loaded from slot 1." }
        onController { it.resume() }
        await("resumed native session") { it.uiState.playerState == PlayerState.Running }
        awaitDiagnosticFrame()

        onController { it.updatePreferences { preferences -> preferences.copy(autoSaveOnBackground = true) } }
        compose.onNodeWithTag("player-pause").performClick()
        await("pause automatic checkpoint") {
            it.uiState.playerState is PlayerState.Paused && !it.checkpointBusy && it.automaticCheckpoints.isNotEmpty()
        }
        val checkpointId = onController { it.automaticCheckpoints.first().id }
        compose.onNodeWithTag("exit-player").performScrollTo().performClick()
        await("durable exit") { it.uiState.route == AppRoute.Library && it.uiState.playerSession == null && !it.checkpointBusy }
        onController { it.openPlayer(fixtureId) }
        awaitNativeSession()
        await("checkpoint history reloaded") { it.automaticCheckpoints.any { checkpoint -> checkpoint.id == checkpointId } }
        onController {
            assertNotEquals("Exit/reopen must create a new native session", retainedSession, it.uiState.playerSession)
            assertEquals("Automatic checkpoints must not overwrite manual slots", manualBefore,
                it.uiState.saveStateSlots.single { slot -> slot.slot == 1 })
            it.restoreAutomaticCheckpoint(checkpointId)
        }
        await("native automatic restore acknowledgement") {
            it.uiState.statusMessage?.startsWith("Automatic checkpoint restored (") == true
        }
        awaitDiagnosticFrame()
        assertPlayerGeometryAndScreenshot("restored-player")
        onController { it.updatePreferences { p -> p.copy(playerPresentation = PlayerPresentation.Classic, playerDisplaySize = PlayerDisplaySize.Maximum) } }
        assertPlayerGeometryAndScreenshot("classic-player")
    }

    @Test fun removingInjectedControllerReleasesOnlyItsHeldButtons() {
        importAndLaunch()
        onController { controller ->
            // Use the Activity's actual router; fabricated device IDs exercise Android
            // event routing without requiring (or claiming to validate) a physical pad.
            val field = MainActivity::class.java.getDeclaredField("gamepadInput").apply { isAccessible = true }
            val router = field.get(compose.activity) as GamepadInputRouter
            val first = 41001
            val second = 41002
            assertTrue(router.onKeyEvent(gamepadKey(first, KeyEvent.KEYCODE_BUTTON_A)))
            assertTrue(router.onKeyEvent(gamepadKey(first, KeyEvent.KEYCODE_BUTTON_B)))
            assertTrue(router.onKeyEvent(gamepadKey(second, KeyEvent.KEYCODE_BUTTON_B)))
            assertEquals(setOf(Button.A, Button.B), pressedButtons(controller))
            router.onDeviceRemoved(first)
            assertEquals("The other controller still holds B", setOf(Button.B), pressedButtons(controller))
            router.onDeviceRemoved(second)
            assertTrue("No stuck buttons after final disconnect", pressedButtons(controller).isEmpty())
            router.onDeviceRemoved(second) // Removal is idempotent.
            assertTrue(pressedButtons(controller).isEmpty())
            assertEquals(PlayerState.Running, controller.uiState.playerState)
        }
        awaitDiagnosticFrame()
    }

    @Test fun editorUndoAndSavePersistWithoutOverlappingThePreview() {
        importAndLaunch()
        assertPlayerGeometryAndScreenshot("editor-before")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Edit touch layout").performScrollTo().performClick()
        compose.onNode(hasTestTag("player-controls") and hasAnyAncestor(hasTestTag("touch-editor"))).performScrollTo()
        compose.onNodeWithTag("touch-editor").assertIsDisplayed()
        assertStageGeometryAndScreenshot("touch-editor-preview", checkAspect = false, editor = true)
        val original = onController { it.uiState.preferences.touchLayout }
        compose.onNodeWithContentDescription("Move Dpad Right").performScrollTo().performClick()
        assertEquals("Draft edits must not persist before Save", original, onController { it.uiState.preferences.touchLayout })
        compose.onNodeWithTag("layout-undo").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("layout-undo").assertIsNotEnabled()
        compose.onNodeWithTag("layout-save").performClick()
        assertEquals("Undo restores the exact draft", original, onController { it.uiState.preferences.touchLayout })
        compose.onNodeWithContentDescription("Move Dpad Right").performScrollTo().performClick()
        compose.onNodeWithTag("layout-save").performScrollTo().performClick()
        val saved = onController { it.uiState.preferences.touchLayout }
        assertNotEquals("Saving a valid nudge must change the durable layout", original, saved)
        assertEquals(saved, SharedPreferencesUserPreferencesStore(instrumentation.targetContext).load().touchLayout)
        compose.activityRule.scenario.recreate()
        assertEquals(saved, onController { it.uiState.preferences.touchLayout })
        onController { it.closeSettings(); it.resume() }
        await("return to player") { it.uiState.route == AppRoute.Player && it.uiState.playerState == PlayerState.Running }
        assertPlayerGeometryAndScreenshot("editor-saved-player")
    }

    @Test fun foldingUpdatesTheLivePlayerWithoutReplacingItsNativeSession() {
        importAndLaunch()
        val session = onController { it.uiState.playerSession }
        val tabletop = compose.runOnIdle {
            testFold(activity = compose.activity, state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.HORIZONTAL, size = 18)
        }
        folding.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(tabletop)))
        compose.waitUntil(10_000) {
            bounds("player-viewport").bottom < tabletop.bounds.top &&
                bounds("player-controls").top > tabletop.bounds.bottom
        }
        assertPlayerGeometryAndScreenshot("tabletop-player")
        val book = compose.runOnIdle {
            testFold(activity = compose.activity, state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.VERTICAL, size = 18)
        }
        folding.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(book)))
        compose.waitUntil(10_000) {
            bounds("player-viewport").right < book.bounds.left &&
                bounds("player-controls").left > book.bounds.right
        }
        assertPlayerGeometryAndScreenshot("book-player")
        folding.overrideWindowLayoutInfo(WindowLayoutInfo(emptyList()))
        compose.waitUntil(10_000) { bounds("player-viewport").bottom < bounds("player-controls").top }
        onController {
            assertEquals(session, it.uiState.playerSession)
            assertEquals(PlayerState.Running, it.uiState.playerState)
        }
        awaitDiagnosticFrame()
        try {
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                val screen = bounds("player-screen")
                screen.width > screen.height
            }
            assertPlayerGeometryAndScreenshot("landscape-player")
            onController { assertEquals(session, it.uiState.playerSession) }
            awaitDiagnosticFrame()
        } finally {
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun batteryWriteFailureKeepsTheLiveSessionAvailableForRetry() {
        importAndLaunch()
        val session = onController { it.uiState.playerSession }
        val item = onController { requireNotNull(it.selectedItem()) }
        // This path belongs only to this test's unique content identity. A
        // directory at the temporary output path prevents the actual native
        // stream from opening, without touching canonical saves or UI state.
        val staging = LibraryRepository.fromContext(instrumentation.targetContext)
            .nativeSaveStagingPath(item.id, requireNotNull(item.coreId))
        val blockedOutput = staging.resolveSibling("${staging.fileName}.part")
        assertFalse(Files.exists(blockedOutput))
        Files.createDirectory(blockedOutput)
        try {
            onController { it.exitPlayer() }
            await("save failure offered for retry") { it.exitSaveFailure != null && !it.checkpointBusy }
            onController {
                assertEquals(session, it.uiState.playerSession)
                assertEquals(AppRoute.Player, it.uiState.route)
                assertTrue(it.uiState.playerState is PlayerState.Paused)
            }
            compose.onNodeWithTag("retry-exit-save").assertIsDisplayed().assertIsEnabled()
        } finally {
            // Only remove our empty fixture directory; do not mask a failed
            // assertion if native cleanup already removed it.
            Files.deleteIfExists(blockedOutput)
        }
        compose.onNodeWithTag("retry-exit-save").performClick()
        await("retry durably saves and exits") { it.uiState.playerSession == null && it.uiState.route == AppRoute.Library }
        onController { assertNull(it.exitSaveFailure) }
        val saved = LibraryRepository.fromContext(instrumentation.targetContext).load().single { it.id == item.id }
        assertEquals(SaveSummary.SavePresent, saved.saveSummary)
    }

    private fun importAndLaunch() {
        val uri = Uri.parse("content://${DiagnosticGbFixture.AUTHORITY}/$fixtureName")
        onController { it.receiveFiles(listOf(ImportCandidate(uri, fixtureName))); it.startImport() }
        await("verified ContentResolver import") { it.uiState.importState is ImportState.Completed }
        onController {
            val summary = (it.uiState.importState as ImportState.Completed).summary
            assertEquals(summary.toString(), 1, summary.imported)
            assertEquals(summary.toString(), 0, summary.skipped)
            val item = it.uiState.library.single { item -> item.contentId == fixtureId }
            assertEquals(32768L, item.contentSizeBytes)
            assertEquals(SupportStatus.Playable, item.supportStatus)
            assertNotNull(item.coreId)
            it.openPlayer(item.id)
        }
        assertTrue(LibraryRepository.fromContext(instrumentation.targetContext).verifyPrivateContent(fixtureId))
        awaitNativeSession()
        awaitDiagnosticFrame()
    }

    private fun awaitNativeSession() = await("real native session, not preview") {
        val state = it.uiState
        state.route == AppRoute.Player && state.playerState == PlayerState.Running && state.playerSession != null &&
            Capability.SaveState in state.runtimeCapabilities && Capability.SoftwareVideo in state.runtimeCapabilities &&
            state.runtimeDiagnostics?.videoWidth == 160 && state.runtimeDiagnostics?.videoHeight == 144
    }

    private fun awaitDiagnosticFrame() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.runOnIdle {
                findTexture(compose.activity.window.decorView)?.getBitmap(160, 144)?.let { bitmap ->
                    try { hasDiagnosticStripes(bitmap) } finally { bitmap.recycle() }
                } == true
            }
        }
    }

    private fun hasDiagnosticStripes(bitmap: Bitmap): Boolean {
        fun luminance(x: Int, y: Int): Int = bitmap.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) }
        val even = luminance(4, 36)
        val odd = luminance(12, 36)
        if (abs(even - odd) < 45) return false
        // 20 alternating stripes at three distinct scanlines. Rejects a solid
        // fallback, logo, blank TextureView, and arbitrary colorful UI pixels.
        return listOf(36, 72, 108).all { y ->
            (0 until 20).all { tile -> abs(luminance(tile * 8 + 4, y) - if (tile % 2 == 0) even else odd) < 18 }
        }
    }

    private fun findTexture(view: View): TextureView? = when (view) {
        is TextureView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTexture(view.getChildAt(it)) }
        else -> null
    }

    private fun assertPlayerGeometryAndScreenshot(name: String) {
        compose.onNodeWithTag("player-screen").assertIsDisplayed()
        val screen = compose.onNodeWithTag("player-screen").fetchSemanticsNode().boundsInRoot
        val viewport = bounds("player-viewport")
        val controls = bounds("player-controls")
        assertContains(screen, viewport)
        assertContains(screen, controls)
        assertStageGeometryAndScreenshot(name, checkAspect = true)
    }

    private fun assertStageGeometryAndScreenshot(name: String, checkAspect: Boolean, editor: Boolean = false) {
        val viewport = bounds("player-viewport", editor)
        val controls = bounds("player-controls", editor)
        // A landscape deck spans both wings of the game; its container overlaps
        // by design. Check the actual interactive targets, not that container.
        for (id in listOf(ControlId.Dpad, ControlId.A, ControlId.B, ControlId.Select, ControlId.Start)) {
            val control = bounds("control-${id.name}", editor)
            assertTrue("Viewport overlaps $id: $viewport / $control",
                viewport.right <= control.left + 1 || control.right <= viewport.left + 1 ||
                    viewport.bottom <= control.top + 1 || control.bottom <= viewport.top + 1)
        }
        if (checkAspect) assertEquals("GB viewport is undistorted", 10f / 9f, viewport.width / viewport.height, .035f)
        // Compose semantics can still be exercised behind a system ANR dialog.
        // Never accept an obscured screenshot as visual proof of the player.
        compose.waitUntil(timeoutMillis = 10_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                instrumentation.targetContext.packageName
        }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val imageBounds = Rect(0f, 0f, screenshot.width.toFloat(), screenshot.height.toFloat())
            assertContains(imageBounds, viewport)
            assertContains(imageBounds, controls)
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "integration-evidence")
            check(output.mkdirs() || output.isDirectory)
            File(output, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { screenshot.recycle() }
    }

    private fun bounds(tag: String, editor: Boolean = false): Rect = compose.onNode(
        if (editor) hasTestTag(tag) and hasAnyAncestor(hasTestTag("touch-editor")) else hasTestTag(tag),
    ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.also {
        assertTrue("$tag must have positive area", it.width > 1 && it.height > 1)
    }

    private fun assertContains(outer: Rect, inner: Rect) {
        assertTrue("$inner escapes $outer", inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
            inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1)
    }

    private fun await(description: String, predicate: (FrontendController) -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 30_000) { onController(predicate) } }
        catch (failure: AssertionError) {
            throw AssertionError("Timed out awaiting $description; state=${onController { it.uiState }}", failure)
        }
    }

    private fun gamepadKey(deviceId: Int, keyCode: Int): KeyEvent = KeyEvent(
        SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode,
        0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD,
    )

    @Suppress("UNCHECKED_CAST")
    private fun pressedButtons(controller: FrontendController): Set<Button> =
        FrontendController::class.java.getDeclaredField("pressedInputs").let { field ->
            field.isAccessible = true
            (field.get(controller) as Set<Button>).toSet()
        }
}
