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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
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

private enum class SettingsSection { Display, Controls, Saves, Library }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    controller: FrontendController,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onImportSystemFile: () -> Unit = {},
) {
    val state = controller.uiState
    val preferences = state.preferences
    val selectedTitle = controller.selectedItem()
    val profile = ConsoleCatalog.forSystem(selectedTitle?.system.orEmpty())
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsSection.Display) }
    var touchLayoutEditorOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = controller::closeSettings) { Text("Back") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 640.dp
            Column(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = ContentRail)
                    .fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Panel).selectableGroup().padding(4.dp),
                ) {
                    SettingsSection.values().forEach { candidate ->
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (section == candidate) PanelRaised else Color.Transparent)
                                .selectable(
                                    selected = section == candidate,
                                    role = Role.Tab,
                                    onClick = { section = candidate },
                                )
                                .heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                candidate.name,
                                color = if (section == candidate) Amber else TextMuted,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (section == candidate) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (wide) {
                        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsPreviewCard(preferences, profile)
                            Text("Default player settings", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!wide) SettingsPreviewCard(preferences, profile)
                        androidx.compose.runtime.key(section) {
                            Column(
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                when (section) {
                                    SettingsSection.Display -> DisplaySettings(controller)
                                    SettingsSection.Controls -> ControlSettings(controller, onEditLayout = { touchLayoutEditorOpen = true })
                                    SettingsSection.Saves -> SaveSettings(controller, onExportBackup, onImportBackup)
                                    SettingsSection.Library -> LibrarySettings(controller, onImportSystemFile)
                                }
                                if ((section == SettingsSection.Display || section == SettingsSection.Controls) && selectedTitle != null) {
                                    SettingsSectionTitle("This game")
                                    Text(selectedTitle.displayTitle, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (selectedTitle.titlePreferences != null)
                                            "This game has saved settings. Copy the current defaults to update them, or clear them to follow defaults."
                                        else "These changes update your defaults. You can also keep a copy for this game.",
                                        color = TextMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    OutlinedButton(onClick = controller::saveCurrentPreferencesForTitle, modifier = Modifier.fillMaxWidth()) {
                                        Text("Save settings for this game")
                                    }
                                    if (selectedTitle.titlePreferences != null) {
                                        TextButton(onClick = controller::clearTitlePreferences) { Text("Use defaults for this game") }
                                    }
                                }
                                if (section == SettingsSection.Display) {
                                    TextButton(onClick = { confirmReset = true }) { Text("Reset preferences") }
                                }
                                state.statusMessage?.let { StatusMessage(it) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (touchLayoutEditorOpen) {
        Dialog(
            onDismissRequest = { touchLayoutEditorOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.widthIn(max = ContentRail).fillMaxWidth().fillMaxHeight(),
                color = Ink,
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Touch layout", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { touchLayoutEditorOpen = false }) { Text("Done") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TouchLayoutEditorCard(
                            preferences = preferences,
                            inputFamily = profile?.inputFamily ?: InputFamily.Handheld,
                            systemId = profile?.id,
                            aspectRatio = profile?.aspectRatio ?: 10f / 9f,
                            onChange = { next -> controller.updatePreferences { it.copy(touchLayout = next) } },
                            onReset = { controller.updatePreferences { it.copy(touchLayout = TouchLayoutAdjustments()) } },
                        )
                    }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset preferences?") },
            text = { Text("Restore default app preferences, including player, controls and favorites. Imported games and save files stay on this device.") },
            confirmButton = {
                TextButton(onClick = { controller.resetPreferences(); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DisplaySettings(controller: FrontendController) {
    val preferences = controller.uiState.preferences
    SettingsSectionTitle("Picture")
    SettingsChoices("Player layout", PlayerPresentation.values().toList(), preferences.playerPresentation, { it.label }) { value ->
        controller.updatePreferences { it.copy(playerPresentation = value) }
    }
    SettingsChoices("Display finish", DisplayPreset.values().toList(), preferences.displayPreset, { it.label() }) { value ->
        controller.updatePreferences { it.copy(displayPreset = value) }
    }
    SettingsChoices("Game window", PlayerDisplaySize.values().toList(), preferences.playerDisplaySize, { it.label }) { value ->
        controller.updatePreferences { it.copy(playerDisplaySize = value) }
    }
    SettingsChoices("Pixel scaling", PixelScalingMode.values().toList(), preferences.pixelScalingMode, { it.label }) { value ->
        controller.updatePreferences { it.copy(pixelScalingMode = value) }
    }
    Text(preferences.pixelScalingMode.description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    SettingsChoices("Screen treatment", ScreenTreatment.values().toList(), preferences.screenTreatment, { it.label }) { value ->
        controller.updatePreferences { it.copy(screenTreatment = value) }
    }
    Text(preferences.screenTreatment.description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    SettingToggle("Immersive player", "Hide Android system bars during play.", preferences.immersivePlayer) { value ->
        controller.updatePreferences { it.copy(immersivePlayer = value) }
    }
    SettingToggle("Keep screen awake", "Prevent sleep during play.", preferences.keepScreenOn) { value ->
        controller.updatePreferences { it.copy(keepScreenOn = value) }
    }
    SettingToggle("Reduce motion", "Use instant transitions and static artwork.", preferences.reduceMotion) { value ->
        controller.updatePreferences { it.copy(reduceMotion = value) }
    }
    SettingsSectionTitle("Audio")
    SettingToggle("Mute audio", "Silence game audio.", preferences.audioMuted) { value ->
        controller.updatePreferences { it.copy(audioMuted = value) }
    }
    Text("Volume  ${(preferences.audioVolume * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
    Slider(
        value = preferences.audioVolume,
        onValueChange = { value -> controller.updatePreferences { it.copy(audioVolume = value) } },
        modifier = Modifier.semantics { contentDescription = "Game volume" },
        valueRange = 0f..1f, steps = 19, enabled = !preferences.audioMuted,
    )
    SettingsSectionTitle("Performance")
    SettingsChoices("Presentation", PerformancePreset.values().toList(), preferences.performancePreset, { it.label }) { value ->
        controller.updatePreferences { it.copy(performancePreset = value) }
    }
    Text(preferences.performancePreset.description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ControlSettings(controller: FrontendController, onEditLayout: () -> Unit) {
    val preferences = controller.uiState.preferences
    SettingsSectionTitle("Touch controls")
    SettingsChoices("Button size", TouchLayoutSize.values().toList(), preferences.touchLayoutSize, { it.label }) { value ->
        controller.updatePreferences { it.copy(touchLayoutSize = value) }
    }
    SettingsChoices("Touch reach", TouchReachMode.values().toList(), preferences.touchReachMode, { it.label }) { value ->
        controller.updatePreferences { it.copy(touchReachMode = value) }
    }
    Text(preferences.touchReachMode.description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
    Button(onClick = onEditLayout, modifier = Modifier.fillMaxWidth()) { Text("Edit touch layout") }
    SettingToggle("Left-handed layout", "Face buttons on the left; D-pad on the right.", preferences.leftHanded) { value ->
        controller.updatePreferences { it.copy(leftHanded = value) }
    }
    SettingToggle("Button labels", "Show labels on touch controls.", preferences.showButtonLabels) { value ->
        controller.updatePreferences { it.copy(showButtonLabels = value) }
    }
    SettingToggle("Haptic feedback", "Feel a light tap when pressing a control.", preferences.hapticsEnabled) { value ->
        controller.updatePreferences { it.copy(hapticsEnabled = value) }
    }
    Text("Control opacity  ${(preferences.controlsOpacity * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
    Slider(
        value = preferences.controlsOpacity,
        onValueChange = { value -> controller.updatePreferences { it.copy(controlsOpacity = value) } },
        modifier = Modifier.semantics { contentDescription = "Touch control opacity" },
        valueRange = .35f..1f, steps = 12,
    )
    SettingsSectionTitle("Controllers")
    GamepadSettings(controller)
}

@Composable
private fun SaveSettings(controller: FrontendController, onExportBackup: () -> Unit, onImportBackup: () -> Unit) {
    val preferences = controller.uiState.preferences
    SettingsSectionTitle("Automatic saves")
    SettingToggle("Save when leaving", "Pause and save battery data when you leave a game or background the app.", preferences.autoSaveOnBackground) { value ->
        controller.updatePreferences { it.copy(autoSaveOnBackground = value) }
    }
    SettingToggle("Resume latest checkpoint", "Restore the newest verified save state when opening a game.", preferences.autoResumeFromLatestState) { value ->
        controller.updatePreferences { it.copy(autoResumeFromLatestState = value) }
    }
    SettingsSectionTitle("Backup")
    Text("Back up settings, collections, artwork and saves. Game files aren't included.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) { Text("Export backup") }
    OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) { Text("Restore backup") }
    Text(
        "Battery saves and manual checkpoints are separate. Exporting makes a copy; restoring can replace saved data.",
        color = TextMuted, style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = controller::openRecovery) { Text("Recovery and storage") }
}

@Composable
private fun LibrarySettings(controller: FrontendController, onImportSystemFile: () -> Unit) {
    val state = controller.uiState
    SettingsSectionTitle("Game files")
    Text("Import files or scan a folder. Your library keeps a private copy and leaves the source unchanged.", color = TextMuted)
    OutlinedButton(onClick = controller::chooseGames, modifier = Modifier.fillMaxWidth()) { Text("Add games") }
    SettingsSectionTitle("System files")
    Text("Import a BIOS or other system file when a compatible core requires it. Importing a file alone doesn't enable an unsupported system.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    if (state.systemFiles.isEmpty()) {
        Text("No system files imported.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    } else {
        state.systemFiles.forEach { record ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(record.sizeBytes), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { controller.removeSystemFile(record.id) }) { Text("Remove") }
            }
        }
    }
    OutlinedButton(onClick = onImportSystemFile, modifier = Modifier.fillMaxWidth()) { Text("Import system file") }
    SettingsSectionTitle("System support")
    CoreCapabilityMatrix(controller)
    TextButton(onClick = controller::openRecovery) { Text("Recovery and runtime limits") }
}

@Composable
private fun <T> SettingsChoices(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

@Composable
internal fun SettingsPreviewCard(preferences: UserPreferences, consoleProfile: ConsoleProfile?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Ivory)
                Text(preferences.playerPresentation.label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
            PlayerStage(
                preferences = preferences,
                family = consoleProfile?.inputFamily ?: InputFamily.Handheld,
                systemId = consoleProfile?.id,
                aspectRatio = consoleProfile?.aspectRatio ?: 10f / 9f,
                modifier = Modifier.fillMaxWidth().height(168.dp),
            )
        }
    }
}

@Composable
internal fun CoreCapabilityMatrix(controller: FrontendController) {
    val selectedSystem = controller.selectedItem()?.system
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Available systems", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Only systems marked Playable can launch games in this build.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            ConsoleCatalog.profiles.forEach { profile ->
                val isSelected = selectedSystem?.let { ConsoleCatalog.forSystem(it)?.id == profile.id } == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(profile.displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold)
                        Text(profile.coreId, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    StatusPill(
                        when {
                            profile.bundled && profile.status == SupportStatus.Playable -> "Playable"
                            profile.requiresHardwareVideo -> "Not available"
                            else -> profile.status.label()
                        },
                        positive = profile.bundled && profile.status == SupportStatus.Playable,
                    )
                }
            }
        }
    }
}

internal fun previewRatioLabel(profile: ConsoleProfile): String = when {
    abs(profile.aspectRatio - (10f / 9f)) < .02f -> "10:9"
    abs(profile.aspectRatio - (3f / 2f)) < .02f -> "3:2"
    abs(profile.aspectRatio - (4f / 3f)) < .02f -> "4:3"
    else -> "NATIVE"
}

@Composable
internal fun MiniDpad(skin: PlayerSkin) {
    Canvas(Modifier.size(48.dp)) {
        val arm = size.minDimension / 3f
        val color = skin.dpadFill
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(arm, 0f),
            size = androidx.compose.ui.geometry.Size(arm, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(arm * .18f, arm * .18f),
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, arm),
            size = androidx.compose.ui.geometry.Size(size.width, arm),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(arm * .18f, arm * .18f),
        )
    }
}

@Composable
internal fun MiniFaceButtons(skin: PlayerSkin) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(25.dp).clip(RoundedCornerShape(50)).background(skin.controlFill))
        Box(Modifier.size(25.dp).clip(RoundedCornerShape(50)).background(skin.controlFill.copy(alpha = .86f)))
    }
}

@Composable
internal fun SettingsSectionTitle(title: String) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Ivory, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SettingToggle(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Row(
            modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
