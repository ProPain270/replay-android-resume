package dev.codex.libretroplatform

import dev.codex.libretroplatform.runtime.api.AnalogAxis
import dev.codex.libretroplatform.runtime.api.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogCalibrationTest {
    @Test
    fun deadZoneRemovesDriftAndRescalesTheRemainingRange() {
        val calibration = AnalogCalibration(deadZone = 0.2f)

        assertEquals(0f, calibration.normalize(AnalogAxis.LeftX, 0.1f), 0.0001f)
        assertEquals(0.5f, calibration.normalize(AnalogAxis.LeftX, 0.6f), 0.0001f)
        assertEquals(-0.5f, calibration.normalize(AnalogAxis.LeftX, -0.6f), 0.0001f)
    }

    @Test
    fun inversionIsAppliedAfterDeadZoneShaping() {
        val calibration = AnalogCalibration(deadZone = 0.1f, rightYInverted = true)

        assertEquals(-0.5f, calibration.normalize(AnalogAxis.RightY, 0.55f), 0.0001f)
        assertEquals(0.5f, calibration.normalize(AnalogAxis.RightY, -0.55f), 0.0001f)
    }

    @Test
    fun defaultHotkeysAlwaysRequireTwoDifferentButtons() {
        val defaults = defaultHotkeyBindings()

        assertTrue(defaults.values.all { it.modifier != it.trigger })
        assertEquals(HotkeyBinding(Button.Select, Button.Start), defaults[HotkeyAction.TogglePause])
    }
}
