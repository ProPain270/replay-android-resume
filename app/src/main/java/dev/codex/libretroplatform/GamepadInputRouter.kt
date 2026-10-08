package dev.codex.libretroplatform

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.codex.libretroplatform.runtime.api.AnalogAxis
import dev.codex.libretroplatform.runtime.api.Button

/**
 * Translates Android-recognized gamepad/joystick events into the common
 * Libretro input model. This intentionally relies on Android input sources,
 * not a controller brand or Bluetooth protocol, so USB and Bluetooth pads use
 * the same path and can be remapped when their labels differ.
 */
class GamepadInputRouter(private val controller: FrontendController) {
    private val activeKeys = mutableMapOf<Pair<Int, Int>, Button>()
    private val activeDigital = mutableSetOf<Pair<Int, Button>>()
    private val activeAnalog = mutableMapOf<Int, Map<AnalogAxis, Float>>()
    private val consumedHotkeyKeys = mutableSetOf<Pair<Int, Int>>()
    private var lastAnalogDeviceId: Int? = null

    fun connectedDevices(): List<GamepadDeviceInfo> = InputDevice.getDeviceIds().toList()
        .mapNotNull { deviceId -> InputDevice.getDevice(deviceId) }
        .filter(::isGamepadDevice)
        .map { device ->
            GamepadDeviceInfo(
                deviceId = device.id,
                name = device.name.ifBlank { "Android gamepad" },
                descriptor = device.descriptor.orEmpty(),
                sourceLabel = sourceLabel(device.sources),
            )
        }
        .distinctBy { it.deviceId }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isGamepadSource(event.source)) return false
        val hotkeyCaptureTarget = controller.uiState.hotkeyCaptureTarget
        if (hotkeyCaptureTarget != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                controller.gamepadMappingFor(event.deviceId)
                    .buttonForKey(event.keyCode)
                    ?.let(controller::captureGamepadHotkeyButton)
            }
            return true
        }
        val captureTarget = controller.uiState.gamepadCaptureTarget
        if (captureTarget != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                controller.captureGamepadKey(event.keyCode, event.deviceId)
            }
            return true
        }
        if (!controller.uiState.preferences.gamepadEnabled) return false

        val button = controller.gamepadMappingFor(event.deviceId).buttonForKey(event.keyCode) ?: return false
        val token = event.deviceId to event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                val activeButtons = activeKeys
                    .filterKeys { it.first == event.deviceId }
                    .values
                    .toSet()
                val hotkey = controller.hotkeyActionFor(activeButtons, button)
                if (hotkey != null && controller.triggerHotkey(hotkey)) {
                    consumedHotkeyKeys += token
                    controller.reportGamepadButton(event.deviceId, button, pressed = true)
                    return true
                }
                activeKeys[event.deviceId to event.keyCode] = button
                controller.sendInput(button, pressed = true)
                controller.reportGamepadButton(event.deviceId, button, pressed = true)
            }
            KeyEvent.ACTION_UP -> {
                if (consumedHotkeyKeys.remove(token)) {
                    controller.reportGamepadButton(event.deviceId, button, pressed = false)
                    return true
                }
                activeKeys.remove(event.deviceId to event.keyCode)?.let { button ->
                    if (activeKeys.values.none { it == button } && activeDigital.none { it.second == button }) {
                        controller.sendInput(button, pressed = false)
                    }
                    controller.reportGamepadButton(
                        event.deviceId,
                        button,
                        pressed = activeKeys.any { it.key.first == event.deviceId && it.value == button } ||
                            activeDigital.any { it.first == event.deviceId && it.second == button },
                    )
                }
            }
        }
        return true
    }

    fun onDeviceRemoved(deviceId: Int) {
        val removedButtons = activeKeys
            .filterKeys { it.first == deviceId }
            .values
            .toSet()
        val removedDigitalButtons = activeDigital
            .filter { it.first == deviceId }
            .map { it.second }
            .toSet()
        activeKeys.keys.removeAll { it.first == deviceId }
        consumedHotkeyKeys.removeAll { it.first == deviceId }
        activeDigital.removeAll { it.first == deviceId }
        (removedButtons + removedDigitalButtons).forEach { button ->
            if (activeKeys.values.none { it == button } && activeDigital.none { it.second == button }) {
                controller.sendInput(button, pressed = false)
            }
        }
        activeAnalog.remove(deviceId)
        if (lastAnalogDeviceId == deviceId) lastAnalogDeviceId = activeAnalog.keys.lastOrNull()
        controller.sendAnalogSnapshot(lastAnalogDeviceId?.let(activeAnalog::get) ?: zeroAnalogSnapshot())
        controller.clearGamepadDiagnostics(deviceId)
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!isGamepadSource(event.source)) return false
        if (controller.uiState.gamepadCaptureTarget != null) return true
        if (!controller.uiState.preferences.gamepadEnabled) return false

        val calibration = controller.analogCalibrationFor(event.deviceId)
        val leftX = axisValue(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X, AnalogAxis.LeftX, calibration)
        val leftY = axisValue(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y, AnalogAxis.LeftY, calibration)
        val rightX = axisValue(event, MotionEvent.AXIS_RX, MotionEvent.AXIS_Z, AnalogAxis.RightX, calibration)
        val rightY = axisValue(event, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ, AnalogAxis.RightY, calibration)
        val analogSnapshot = mapOf(
            AnalogAxis.LeftX to leftX,
            AnalogAxis.LeftY to leftY,
            AnalogAxis.RightX to rightX,
            AnalogAxis.RightY to rightY,
        )
        activeAnalog[event.deviceId] = analogSnapshot
        lastAnalogDeviceId = event.deviceId
        controller.sendAnalogSnapshot(analogSnapshot)
        controller.reportGamepadAnalog(
            event.deviceId,
            analogSnapshot,
        )

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        syncDigital(event.deviceId, Button.Left, hatX < -0.5f || leftX < -0.65f)
        syncDigital(event.deviceId, Button.Right, hatX > 0.5f || leftX > 0.65f)
        syncDigital(event.deviceId, Button.Up, hatY < -0.5f || leftY < -0.65f)
        syncDigital(event.deviceId, Button.Down, hatY > 0.5f || leftY > 0.65f)

        syncDigital(event.deviceId, Button.L2, event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > calibration.triggerThreshold)
        syncDigital(event.deviceId, Button.R2, event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > calibration.triggerThreshold)
        return true
    }

    fun clear() {
        activeKeys.values.toSet().forEach { controller.sendInput(it, pressed = false) }
        activeKeys.clear()
        consumedHotkeyKeys.clear()
        activeDigital.map { it.second }.toSet().forEach { controller.sendInput(it, pressed = false) }
        activeDigital.clear()
        activeAnalog.clear()
        lastAnalogDeviceId = null
        controller.clearInputState()
        controller.clearGamepadDiagnostics()
    }

    private fun syncDigital(deviceId: Int, button: Button, pressed: Boolean) {
        val token = deviceId to button
        if (pressed) {
            if (!activeDigital.add(token)) return
        } else {
            if (!activeDigital.remove(token)) return
        }
        controller.sendInput(button, activeDigital.any { it.second == button })
        controller.reportGamepadButton(deviceId, button, activeDigital.any { it.second == button })
    }

    private fun axisValue(
        event: MotionEvent,
        preferred: Int,
        fallback: Int,
        axis: AnalogAxis,
        calibration: AnalogCalibration,
    ): Float {
        val preferredAvailable = event.device.motionRanges.any { it.axis == preferred }
        val value = if (preferredAvailable) event.getAxisValue(preferred) else event.getAxisValue(fallback)
        return calibration.normalize(axis, value)
    }

    private fun zeroAnalogSnapshot(): Map<AnalogAxis, Float> = mapOf(
        AnalogAxis.LeftX to 0f,
        AnalogAxis.LeftY to 0f,
        AnalogAxis.RightX to 0f,
        AnalogAxis.RightY to 0f,
    )

    private fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

    private fun isGamepadDevice(device: InputDevice): Boolean = isGamepadSource(device.sources)

    private fun sourceLabel(source: Int): String = when {
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD -> "Gamepad"
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK -> "Joystick"
        else -> "D-pad"
    }
}
