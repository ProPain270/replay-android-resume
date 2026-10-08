package dev.codex.libretroplatform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import android.view.TextureView
import dev.codex.libretroplatform.runtime.api.Button as LogicalButton
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun GamepadSettings(controller: FrontendController) {
    val state = controller.uiState
    val mapping = controller.selectedGamepadMapping()
    SettingToggle(
        title = "Bluetooth & USB gamepads",
        body = "Use any Android-recognized controller while touch controls remain available.",
        checked = state.preferences.gamepadEnabled,
        onCheckedChange = { checked -> controller.updatePreferences { it.copy(gamepadEnabled = checked) } },
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0x661A1E28),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Connected now", fontWeight = FontWeight.Bold)
                    Text(
                        "Pair a Bluetooth controller in Android settings, connect it, then return here to see it.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("${state.connectedGamepads.size}", color = Mint, fontWeight = FontWeight.Black)
            }
            if (state.connectedGamepads.isEmpty()) {
                Text("No controller detected", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            } else {
                state.connectedGamepads.forEach { device ->
                    Surface(
                        onClick = { controller.selectGamepadProfile(device.profileKey) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (state.selectedGamepadProfileKey == device.profileKey) Color(0x333D6A59) else Color(0x331F493A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (state.selectedGamepadProfileKey == device.profileKey) Mint else Color.Transparent),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(device.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(device.sourceLabel, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(if (state.selectedGamepadProfileKey == device.profileKey) "ACTIVE" else "USE", color = Mint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
    GamepadDiagnosticsCard(controller)
    Text("CUSTOM MAPPING", color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    Text("Select a connected controller, then tap a row and press the physical control you want to use. Profiles are kept separate by Android device descriptor and fall back to the standard map.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    LogicalButton.values().forEach { button ->
        Surface(
            onClick = { controller.beginGamepadMapping(button) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (state.gamepadCaptureTarget == button) Color(0x333D6A59) else Color(0x661A1E28),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (state.gamepadCaptureTarget == button) Mint else SurfaceStroke),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(gamepadButtonLabel(button), fontWeight = FontWeight.SemiBold)
                Text(gamepadKeyLabel(mapping.keyFor(button)), color = if (state.gamepadCaptureTarget == button) Mint else TextMuted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
    OutlinedButton(onClick = controller::resetGamepadMapping, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Text("Reset controller mapping")
    }
    OutlinedButton(onClick = controller::applyRecommendedGamepadMapping, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Text("Apply recommended HID layout")
    }
    val calibration = controller.selectedAnalogCalibration()
    Text("ANALOG CALIBRATION", color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    Text(
        "Tune stick response for the selected controller. A dead zone removes drift, while the trigger threshold controls when L2/R2 become digital presses.",
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Stick dead zone  ${((calibration.deadZone * 100).toInt())}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Slider(
        value = calibration.deadZone,
        onValueChange = { value -> controller.updateSelectedAnalogCalibration { it.copy(deadZone = value) } },
        valueRange = 0f..0.35f,
        steps = 6,
    )
    Text("Trigger threshold  ${((calibration.triggerThreshold * 100).toInt())}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Slider(
        value = calibration.triggerThreshold,
        onValueChange = { value -> controller.updateSelectedAnalogCalibration { it.copy(triggerThreshold = value) } },
        valueRange = 0.2f..0.9f,
        steps = 6,
    )
    Text("Invert axes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = calibration.leftXInverted,
            onClick = { controller.updateSelectedAnalogCalibration { it.copy(leftXInverted = !it.leftXInverted) } },
            label = { Text("Left X") },
        )
        FilterChip(
            selected = calibration.leftYInverted,
            onClick = { controller.updateSelectedAnalogCalibration { it.copy(leftYInverted = !it.leftYInverted) } },
            label = { Text("Left Y") },
        )
        FilterChip(
            selected = calibration.rightXInverted,
            onClick = { controller.updateSelectedAnalogCalibration { it.copy(rightXInverted = !it.rightXInverted) } },
            label = { Text("Right X") },
        )
        FilterChip(
            selected = calibration.rightYInverted,
            onClick = { controller.updateSelectedAnalogCalibration { it.copy(rightYInverted = !it.rightYInverted) } },
            label = { Text("Right Y") },
        )
    }
    OutlinedButton(onClick = controller::resetSelectedAnalogCalibration, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Text("Reset analog calibration")
    }
    Text("RUNTIME HOTKEYS", color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    Text(
        "Every hotkey is a two-button chord, so one accidental press can never pause, save, load, or exit the player. Tap a row, then press the modifier and trigger on the selected controller.",
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    HotkeyAction.values().forEach { action ->
        val binding = controller.hotkeyBinding(action)
        Surface(
            onClick = { controller.beginHotkeyMapping(action) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (state.hotkeyCaptureTarget == action) Color(0x333D6A59) else Color(0x661A1E28),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (state.hotkeyCaptureTarget == action) Mint else SurfaceStroke),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(action.label, fontWeight = FontWeight.SemiBold)
                    Text(action.description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(binding.label, color = if (state.hotkeyCaptureTarget == action) Mint else TextMuted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
    OutlinedButton(onClick = controller::resetHotkeys, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Text("Reset runtime hotkeys")
    }
    state.gamepadCaptureTarget?.let { target ->
        AlertDialog(
            onDismissRequest = controller::cancelGamepadMapping,
            title = { Text("Map ${gamepadButtonLabel(target)}") },
            text = { Text("Press the physical button or D-pad direction now. The next controller key will be assigned to this action.") },
            confirmButton = { TextButton(onClick = controller::cancelGamepadMapping) { Text("Cancel") } },
        )
    }
    state.hotkeyCaptureTarget?.let { action ->
        AlertDialog(
            onDismissRequest = controller::cancelHotkeyMapping,
            title = { Text("Map ${action.label}") },
            text = {
                Text(
                    if (state.hotkeyCapturePart == HotkeyCapturePart.Modifier) {
                        "Press the modifier button now. Use a button you can hold comfortably, such as Select."
                    } else {
                        "Modifier ${state.hotkeyCaptureModifier?.name.orEmpty()} captured. Press a different trigger button now."
                    },
                )
            },
            confirmButton = { TextButton(onClick = controller::cancelHotkeyMapping) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun GamepadDiagnosticsCard(controller: FrontendController) {
    val state = controller.uiState
    val diagnostics = state.gamepadDiagnostics
    val deviceName = diagnostics?.deviceId?.let { id -> state.connectedGamepads.firstOrNull { it.deviceId == id }?.name }
        ?: "No controller selected"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0x661A1E28),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("LIVE INPUT TEST", fontWeight = FontWeight.Bold)
                    Text(deviceName, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (diagnostics == null) TextMuted else Mint))
            }
            if (diagnostics == null) {
                Text(
                    "Move a stick or press a button while this screen is open to see the selected controller's live signal. Nothing on this diagnostic surface is sent to a game.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val leftX = diagnostics.analog[dev.codex.libretroplatform.runtime.api.AnalogAxis.LeftX] ?: 0f
                val leftY = diagnostics.analog[dev.codex.libretroplatform.runtime.api.AnalogAxis.LeftY] ?: 0f
                val rightX = diagnostics.analog[dev.codex.libretroplatform.runtime.api.AnalogAxis.RightX] ?: 0f
                val rightY = diagnostics.analog[dev.codex.libretroplatform.runtime.api.AnalogAxis.RightY] ?: 0f
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalogTestPad("LEFT STICK", leftX, leftY, Modifier.weight(1f))
                    AnalogTestPad("RIGHT STICK", rightX, rightY, Modifier.weight(1f))
                }
                Text(
                    "Pressed: ${diagnostics.pressedButtons.takeIf { it.isNotEmpty() }?.joinToString { gamepadButtonLabel(it) } ?: "none"}",
                    color = if (diagnostics.pressedButtons.isEmpty()) TextMuted else Mint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun AnalogTestPad(label: String, x: Float, y: Float, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth().height(92.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0D1114),
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
        ) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                drawLine(TextMuted.copy(alpha = .35f), androidx.compose.ui.geometry.Offset(center.x, 0f), androidx.compose.ui.geometry.Offset(center.x, size.height), strokeWidth = 1.dp.toPx())
                drawLine(TextMuted.copy(alpha = .35f), androidx.compose.ui.geometry.Offset(0f, center.y), androidx.compose.ui.geometry.Offset(size.width, center.y), strokeWidth = 1.dp.toPx())
                drawCircle(TextMuted.copy(alpha = .18f), radius = size.minDimension * .42f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                drawCircle(Mint, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(
                    center.x + x.coerceIn(-1f, 1f) * size.width * .38f,
                    center.y + y.coerceIn(-1f, 1f) * size.height * .38f,
                ))
            }
        }
        Text("${(x * 100).toInt()} / ${(y * 100).toInt()}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

