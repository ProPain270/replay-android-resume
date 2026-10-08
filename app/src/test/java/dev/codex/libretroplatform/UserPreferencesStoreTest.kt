package dev.codex.libretroplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import android.view.KeyEvent
import dev.codex.libretroplatform.runtime.api.Button
import java.util.Properties

class UserPreferencesStoreTest {
    @Test
    fun inMemoryStoreRoundTripsPresentationPreferences() {
        val store = InMemoryUserPreferencesStore()
        val preferences = UserPreferences(
            favorites = setOf("game-1"),
            displayPreset = DisplayPreset.Night,
            playerDisplaySize = PlayerDisplaySize.Maximum,
            pixelScalingMode = PixelScalingMode.Integer,
            touchLayoutSize = TouchLayoutSize.Large,
            touchReachMode = TouchReachMode.OneHandedRight,
            touchLayout = TouchLayoutAdjustments(dpadX = -0.12f, dpadY = 0.04f, faceX = 0.1f, faceY = -0.03f),
            performancePreset = PerformancePreset.LowLatency,
            controlsOpacity = 0.6f,
            hapticsEnabled = false,
            leftHanded = true,
            showButtonLabels = false,
            reduceMotion = true,
            immersivePlayer = true,
            keepScreenOn = false,
            autoSaveOnBackground = false,
            gamepadEnabled = false,
            gamepadMapping = GamepadMapping.default().copy(a = KeyEvent.KEYCODE_BUTTON_X),
            gamepadAnalogCalibration = AnalogCalibration(
                deadZone = 0.2f,
                triggerThreshold = 0.7f,
                leftYInverted = true,
            ),
            hotkeys = defaultHotkeyBindings() + (HotkeyAction.ExitPlayer to HotkeyBinding(Button.Select, Button.Y)),
        )

        store.save(preferences)

        assertEquals(preferences, store.load())
        assertTrue(store.load().favorites.contains("game-1"))
    }

    @Test
    fun titlePreferencesCodecRoundTripsPartialOverrides() {
        val override = TitlePlayerPreferences(
            playerDisplaySize = PlayerDisplaySize.Maximum,
            pixelScalingMode = PixelScalingMode.Integer,
            touchReachMode = TouchReachMode.OneHandedLeft,
            touchLayout = TouchLayoutAdjustments(dpadX = -0.1f, faceX = 0.08f),
            audioVolume = 0.65f,
            showButtonLabels = false,
        )

        assertEquals(override, TitlePlayerPreferencesCodec.decode(TitlePlayerPreferencesCodec.encode(override)))
        assertEquals(null, TitlePlayerPreferencesCodec.decode(TitlePlayerPreferencesCodec.encode(null)))
    }

    @Test
    fun touchLayoutCodecClampsUnsafeOffsets() {
        val decoded = TouchLayoutAdjustmentsCodec.decode("-2,0.2,0.11,-0.4")

        assertEquals(TouchLayoutAdjustments.MIN_OFFSET, decoded?.dpadX)
        assertEquals(TouchLayoutAdjustments.MAX_OFFSET, decoded?.dpadY)
        assertEquals(0.11f, decoded?.faceX)
        assertEquals(TouchLayoutAdjustments.MIN_OFFSET, decoded?.faceY)
    }

    @Test
    fun titleCodecReadsLegacyRecordsWithoutLayoutFields() {
        val legacy = "Classic|Large|Clean|Fit|Comfort|Full|0.8|false|0.7|false|true|true"

        val decoded = TitlePlayerPreferencesCodec.decode(legacy)

        assertEquals(null, decoded?.touchLayout)
        assertEquals(0.8f, decoded?.audioVolume)
        assertEquals(false, decoded?.audioMuted)
    }

    @Test
    fun portablePreferencesCodecKeepsNewPresentationFields() {
        val preferences = UserPreferences(
            pixelScalingMode = PixelScalingMode.Fill,
            touchReachMode = TouchReachMode.OneHandedRight,
            touchLayout = TouchLayoutAdjustments(dpadX = -0.08f, dpadY = 0.03f, faceX = 0.1f, faceY = -0.02f),
            performancePreset = PerformancePreset.BatterySaver,
        )

        val restored = UserPreferencesBackupCodec.decode(Properties().also {
            it.putAll(UserPreferencesBackupCodec.encode(preferences))
        })

        assertEquals(preferences, restored)
    }
}
