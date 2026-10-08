package dev.codex.libretroplatform

import android.view.InputDevice
import android.view.KeyEvent
import dev.codex.libretroplatform.runtime.api.Button

/** A remappable physical-key layout shared by Bluetooth and USB controllers. */
data class GamepadMapping(
    val up: Int = KeyEvent.KEYCODE_DPAD_UP,
    val down: Int = KeyEvent.KEYCODE_DPAD_DOWN,
    val left: Int = KeyEvent.KEYCODE_DPAD_LEFT,
    val right: Int = KeyEvent.KEYCODE_DPAD_RIGHT,
    val a: Int = KeyEvent.KEYCODE_BUTTON_A,
    val b: Int = KeyEvent.KEYCODE_BUTTON_B,
    val x: Int = KeyEvent.KEYCODE_BUTTON_X,
    val y: Int = KeyEvent.KEYCODE_BUTTON_Y,
    val l: Int = KeyEvent.KEYCODE_BUTTON_L1,
    val r: Int = KeyEvent.KEYCODE_BUTTON_R1,
    val l2: Int = KeyEvent.KEYCODE_BUTTON_L2,
    val r2: Int = KeyEvent.KEYCODE_BUTTON_R2,
    val l3: Int = KeyEvent.KEYCODE_BUTTON_THUMBL,
    val r3: Int = KeyEvent.KEYCODE_BUTTON_THUMBR,
    val start: Int = KeyEvent.KEYCODE_BUTTON_START,
    val select: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
) {
    fun keyFor(button: Button): Int = when (button) {
        Button.Up -> up
        Button.Down -> down
        Button.Left -> left
        Button.Right -> right
        Button.A -> a
        Button.B -> b
        Button.X -> x
        Button.Y -> y
        Button.L -> l
        Button.R -> r
        Button.L2 -> l2
        Button.R2 -> r2
        Button.L3 -> l3
        Button.R3 -> r3
        Button.Start -> start
        Button.Select -> select
    }

    fun withKey(button: Button, keyCode: Int): GamepadMapping {
        val withoutDuplicate = copy(
            up = if (button != Button.Up && up == keyCode) KeyEvent.KEYCODE_UNKNOWN else up,
            down = if (button != Button.Down && down == keyCode) KeyEvent.KEYCODE_UNKNOWN else down,
            left = if (button != Button.Left && left == keyCode) KeyEvent.KEYCODE_UNKNOWN else left,
            right = if (button != Button.Right && right == keyCode) KeyEvent.KEYCODE_UNKNOWN else right,
            a = if (button != Button.A && a == keyCode) KeyEvent.KEYCODE_UNKNOWN else a,
            b = if (button != Button.B && b == keyCode) KeyEvent.KEYCODE_UNKNOWN else b,
            x = if (button != Button.X && x == keyCode) KeyEvent.KEYCODE_UNKNOWN else x,
            y = if (button != Button.Y && y == keyCode) KeyEvent.KEYCODE_UNKNOWN else y,
            l = if (button != Button.L && l == keyCode) KeyEvent.KEYCODE_UNKNOWN else l,
            r = if (button != Button.R && r == keyCode) KeyEvent.KEYCODE_UNKNOWN else r,
            l2 = if (button != Button.L2 && l2 == keyCode) KeyEvent.KEYCODE_UNKNOWN else l2,
            r2 = if (button != Button.R2 && r2 == keyCode) KeyEvent.KEYCODE_UNKNOWN else r2,
            l3 = if (button != Button.L3 && l3 == keyCode) KeyEvent.KEYCODE_UNKNOWN else l3,
            r3 = if (button != Button.R3 && r3 == keyCode) KeyEvent.KEYCODE_UNKNOWN else r3,
            start = if (button != Button.Start && start == keyCode) KeyEvent.KEYCODE_UNKNOWN else start,
            select = if (button != Button.Select && select == keyCode) KeyEvent.KEYCODE_UNKNOWN else select,
        )
        return when (button) {
            Button.Up -> withoutDuplicate.copy(up = keyCode)
            Button.Down -> withoutDuplicate.copy(down = keyCode)
            Button.Left -> withoutDuplicate.copy(left = keyCode)
            Button.Right -> withoutDuplicate.copy(right = keyCode)
            Button.A -> withoutDuplicate.copy(a = keyCode)
            Button.B -> withoutDuplicate.copy(b = keyCode)
            Button.X -> withoutDuplicate.copy(x = keyCode)
            Button.Y -> withoutDuplicate.copy(y = keyCode)
            Button.L -> withoutDuplicate.copy(l = keyCode)
            Button.R -> withoutDuplicate.copy(r = keyCode)
            Button.L2 -> withoutDuplicate.copy(l2 = keyCode)
            Button.R2 -> withoutDuplicate.copy(r2 = keyCode)
            Button.L3 -> withoutDuplicate.copy(l3 = keyCode)
            Button.R3 -> withoutDuplicate.copy(r3 = keyCode)
            Button.Start -> withoutDuplicate.copy(start = keyCode)
            Button.Select -> withoutDuplicate.copy(select = keyCode)
        }
    }

    fun buttonForKey(keyCode: Int): Button? = Button.values().firstOrNull { keyFor(it) == keyCode && keyCode != KeyEvent.KEYCODE_UNKNOWN }

    fun persistedKeyCodes(): List<Int> = listOf(
        up, down, left, right, a, b, x, y, l, r, l2, r2, l3, r3, start, select,
    )

    companion object {
        fun default(): GamepadMapping = GamepadMapping()

        /** Android's standardized gamepad keycodes are the safest cross-brand baseline. */
        fun recommended(): GamepadMapping = default()

        fun fromPersistedKeyCodes(values: List<Int>): GamepadMapping? {
            if (values.size != 16) return null
            return GamepadMapping(
                up = values[0], down = values[1], left = values[2], right = values[3],
                a = values[4], b = values[5], x = values[6], y = values[7],
                l = values[8], r = values[9], l2 = values[10], r2 = values[11],
                l3 = values[12], r3 = values[13], start = values[14], select = values[15],
            )
        }
    }
}

fun gamepadButtonLabel(button: Button): String = when (button) {
    Button.Up -> "D-pad up"
    Button.Down -> "D-pad down"
    Button.Left -> "D-pad left"
    Button.Right -> "D-pad right"
    Button.A, Button.B, Button.X, Button.Y, Button.L, Button.R, Button.L2, Button.R2, Button.L3, Button.R3, Button.Start, Button.Select -> button.name
}

fun gamepadKeyLabel(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode)
    .removePrefix("KEYCODE_")
    .replace('_', ' ')
    .lowercase()
    .replaceFirstChar { it.titlecase() }

fun gamepadProfileKey(device: InputDevice): String =
    device.descriptor?.takeIf { it.isNotBlank() } ?: "name:${device.name}"
