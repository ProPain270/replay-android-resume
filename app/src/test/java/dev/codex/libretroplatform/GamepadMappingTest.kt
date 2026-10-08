package dev.codex.libretroplatform

import android.view.KeyEvent
import dev.codex.libretroplatform.runtime.api.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadMappingTest {
    @Test
    fun defaultLayoutUsesAndroidStandardGamepadKeys() {
        val mapping = GamepadMapping.default()
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, mapping.keyFor(Button.A))
        assertEquals(Button.Start, mapping.buttonForKey(KeyEvent.KEYCODE_BUTTON_START))
    }

    @Test
    fun mappingCanCaptureANonStandardKey() {
        val mapping = GamepadMapping.default().withKey(Button.A, KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, mapping.keyFor(Button.A))
        assertEquals(Button.A, mapping.buttonForKey(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, mapping.keyFor(Button.X))
        assertNull(mapping.buttonForKey(999))
    }

    @Test
    fun persistedKeyCodesRoundTripWithoutChangingTheMapping() {
        val mapping = GamepadMapping.default()
            .withKey(Button.A, KeyEvent.KEYCODE_BUTTON_X)
            .withKey(Button.Start, KeyEvent.KEYCODE_BUTTON_Y)

        val restored = GamepadMapping.fromPersistedKeyCodes(mapping.persistedKeyCodes())

        assertEquals(mapping, restored)
        assertNull(GamepadMapping.fromPersistedKeyCodes(listOf(1, 2, 3)))
    }
}
