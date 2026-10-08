package dev.codex.libretroplatform

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codex.libretroplatform.runtime.api.Button

/** Rendering, hit targets and editor preview all consume the same control nodes. */
@Composable
internal fun ControlDeck(
    preferences: UserPreferences,
    inputFamily: InputFamily,
    systemId: String?,
    profile: ControlProfile,
    modifier: Modifier = Modifier,
    onInput: ((Button, Boolean) -> Unit)? = null,
    selected: ControlId? = null,
    onSelect: ((ControlId) -> Unit)? = null,
    onMove: ((ControlId, Float, Float) -> Unit)? = null,
    onMoveStart: (() -> Unit)? = null,
) {
    val skin = playerSkin(preferences.displayPreset)
    val density = LocalDensity.current.density
    val latestInput by rememberUpdatedState(onInput)
    val latestMove by rememberUpdatedState(onMove)
    val latestStart by rememberUpdatedState(onMoveStart)
    val latestHaptics by rememberUpdatedState(preferences.hapticsEnabled)
    BoxWithConstraints(modifier) {
        val nodes = controlNodes(maxWidth.value, maxHeight.value, inputFamily, systemId, preferences, profile)
        nodes.forEach { node ->
            val r = node.bounds
            val id = node.id
            val editing = onSelect != null
            var pressed by remember(id) { mutableStateOf(false) }
            val view = LocalView.current
            val shape = RoundedCornerShape(if (id == ControlId.Dpad) 12.dp else if (id in listOf(ControlId.Start, ControlId.Select, ControlId.L, ControlId.R)) 12.dp else 50.dp)
            val surface = if (preferences.playerPresentation == PlayerPresentation.Classic) skin.controlFill else Color(0xFF252B32)
            var nodeModifier = Modifier.absoluteOffset(r.x.dp, r.y.dp).size(r.width.dp, r.height.dp)
                .testTag("control-${id.name}")
                .semantics {
                    contentDescription = if (editing) "Edit ${id.name}" else id.name
                    role = Role.Button
                    if (editing) {
                        this.selected = selected == id
                        onClick { onSelect?.invoke(id); true }
                    } else if (id != ControlId.Dpad && onInput != null) {
                        onClick { val button = Button.valueOf(id.name); latestInput?.invoke(button, true); latestInput?.invoke(button, false); true }
                    }
                }
            if (editing) {
                nodeModifier = nodeModifier.pointerInput(id, profile) {
                    detectTapGestures(onTap = { onSelect?.invoke(id) })
                }.pointerInput(id) {
                    detectDragGestures(
                        onDragStart = { onSelect?.invoke(id); latestStart?.invoke() },
                        onDrag = { change, delta ->
                            change.consume()
                            latestMove?.invoke(id, delta.x / density, delta.y / density)
                        },
                    )
                }
            } else if (onInput != null) {
                nodeModifier = nodeModifier.pointerInput(id, profile) {
                    if (id == ControlId.Dpad) {
                        var held = emptySet<Button>()
                        fun update(position: Offset?) {
                            val targetWidth = size.width
                            val targetHeight = size.height
                            val next = if (position == null || position.x !in 0f..targetWidth.toFloat() ||
                                position.y !in 0f..targetHeight.toFloat()) emptySet() else buildSet {
                                val nx = position.x / targetWidth
                                val ny = position.y / targetHeight
                                if (nx < .34f) add(Button.Left)
                                if (nx > .66f) add(Button.Right)
                                if (ny < .34f) add(Button.Up)
                                if (ny > .66f) add(Button.Down)
                            }
                            (held - next).forEach { latestInput?.invoke(it, false) }
                            (next - held).forEach { latestInput?.invoke(it, true) }
                            held = next
                            pressed = next.isNotEmpty()
                        }
                        try {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                update(down.position)
                                do {
                                    val event = awaitPointerEvent()
                                    val pointer = event.changes.firstOrNull { it.id == down.id }
                                    if (pointer == null || !pointer.pressed) break
                                    pointer.consume()
                                    update(pointer.position)
                                } while (true)
                                update(null)
                            }
                        } finally { update(null) }
                    } else {
                        detectTapGestures(onPress = {
                            val button = Button.valueOf(id.name)
                            pressed = true
                            if (latestHaptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            latestInput?.invoke(button, true)
                            try { tryAwaitRelease() } finally { pressed = false; latestInput?.invoke(button, false) }
                        })
                    }
                }
            }
            Box(
                nodeModifier
                    .alpha(if (editing) 1f else preferences.controlsOpacity.coerceIn(.2f, 1f))
                    .clip(shape)
                    .then(if (id != ControlId.Dpad) Modifier.background(if (pressed) Amber.copy(alpha = .72f) else surface) else Modifier)
                    .then(
                        if (id != ControlId.Dpad || selected == id) Modifier.border(
                            if (selected == id) 2.dp else 1.dp,
                            if (selected == id) Amber else skin.controlBorder,
                            shape,
                        ) else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (id == ControlId.Dpad) {
                    Canvas(Modifier.fillMaxSize()) {
                        val a = size.minDimension / 3f
                        val c = if (pressed) Amber else skin.dpadFill
                        val corner = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                        drawRoundRect(skin.dpadShadow, Offset(a, 3.dp.toPx()), Size(a, size.height), corner)
                        drawRoundRect(skin.dpadShadow, Offset(0f, a + 3.dp.toPx()), Size(size.width, a), corner)
                        drawRoundRect(c, Offset(a, 0f), Size(a, size.height), corner)
                        drawRoundRect(c, Offset(0f, a), Size(size.width, a), corner)
                        drawLine(skin.dpadHighlight.copy(alpha = .45f), Offset(a + 3.dp.toPx(), 5.dp.toPx()), Offset(a + 3.dp.toPx(), size.height - 5.dp.toPx()), 1.dp.toPx())
                        drawLine(skin.dpadHighlight.copy(alpha = .35f), Offset(5.dp.toPx(), a + 3.dp.toPx()), Offset(size.width - 5.dp.toPx(), a + 3.dp.toPx()), 1.dp.toPx())
                        drawCircle(skin.dpadCenter, radius = a * .43f, center = center)
                        drawCircle(skin.dpadCenterBorder, radius = a * .43f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                    if (!editing && onInput != null) {
                        // Separate screen-reader actions preserve directional access to the cross.
                        listOf(Button.Up, Button.Left, Button.Down, Button.Right).forEach { direction ->
                            val alignment = when (direction) {
                                Button.Up -> Alignment.TopCenter
                                Button.Down -> Alignment.BottomCenter
                                Button.Left -> Alignment.CenterStart
                                else -> Alignment.CenterEnd
                            }
                            Box(Modifier.align(alignment).size((r.width / 3f).dp).semantics {
                                contentDescription = direction.name
                                role = Role.Button
                                onClick { latestInput?.invoke(direction, true); latestInput?.invoke(direction, false); true }
                            })
                        }
                    }
                } else if (preferences.showButtonLabels || editing) {
                    val fontSize = if (id in listOf(ControlId.Start, ControlId.Select)) 9.sp else 14.sp
                    Text(id.name.uppercase(), color = if (pressed) Ink else Color(0xFFF0EDE5), fontSize = fontSize,
                        lineHeight = fontSize * 1.15f, maxLines = 1, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun TouchLayoutPreview(
    preferences: UserPreferences, inputFamily: InputFamily, systemId: String?, modifier: Modifier = Modifier,
) {
    ControlDeck(preferences, inputFamily, systemId, ControlProfile.Portrait, modifier.fillMaxWidth().height(160.dp))
}
