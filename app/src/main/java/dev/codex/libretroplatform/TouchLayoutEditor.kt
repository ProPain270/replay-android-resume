package dev.codex.libretroplatform

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchLayoutEditorCard(
    preferences: UserPreferences,
    inputFamily: InputFamily,
    onChange: (TouchLayoutAdjustments) -> Unit,
    onReset: () -> Unit,
    systemId: String? = null,
    aspectRatio: Float = 10f / 9f,
) {
    var draft by remember(preferences.touchLayout) { mutableStateOf(preferences.touchLayout.sanitized()) }
    var profile by rememberSaveable { mutableStateOf(ControlProfile.Portrait) }
    var selected by remember { mutableStateOf(ControlId.Dpad) }
    val history = remember { mutableStateListOf<TouchLayoutAdjustments>() }
    var measured by remember { mutableStateOf<PlayerGeometry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun checkpoint() {
        if (history.lastOrNull() != draft) history.add(draft)
        if (history.size > 40) history.removeAt(0)
    }
    fun adjust(id: ControlId, dx: Float = 0f, dy: Float = 0f, scale: Float? = null) {
        val g = measured ?: return
        val key = controlKey(profile, id)
        val previous = draft.controls[key] ?: ControlPlacement()
        val next = previous.copy(x = previous.x + dx, y = previous.y + dy, scale = scale ?: previous.scale).sanitized()
        val candidate = draft.copy(controls = draft.controls + (key to next))
        val nodes = controlNodes(g.controls.width, g.controls.height, inputFamily, systemId, preferences.copy(touchLayout = candidate), profile)
        if (safeControlPlacement(nodes, id, if (profile == ControlProfile.Landscape) g.viewport else null)) {
            draft = candidate
            message = null
        } else message = "Keep controls clear of each other and the screen."
    }
    Column(Modifier.fillMaxWidth().testTag("touch-editor"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tap a control to select it. Drag to move; use the arrows for fine adjustments.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ControlProfile.values().forEach { p ->
                FilterChip(selected = profile == p, onClick = { profile = p }, label = { Text(p.name) })
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (profile == ControlProfile.Landscape) 210.dp else 320.dp)) {
            val virtualWidth = when (profile) { ControlProfile.Landscape, ControlProfile.Book, ControlProfile.Tabletop -> 760f; else -> 390f }
            val virtualHeight = when (profile) { ControlProfile.Landscape -> 320f; ControlProfile.Book -> 520f; ControlProfile.Tabletop -> 720f; else -> 640f }
            val scale = minOf(maxWidth.value / virtualWidth, maxHeight.value / virtualHeight)
            val virtualFold = when (profile) {
                ControlProfile.Tabletop -> FoldBounds(DeckRect(0f, 350f, virtualWidth, 12f), true, true)
                ControlProfile.Book -> FoldBounds(DeckRect(370f, 0f, 12f, virtualHeight), false, true)
                else -> null
            }
            PlayerStage(
                preferences.copy(touchLayout = draft),
                inputFamily, systemId, aspectRatio,
                Modifier.requiredSize(virtualWidth.dp, virtualHeight.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale },
                fold = virtualFold,
                selected = selected,
                onSelect = { selected = it },
                onMoveStart = ::checkpoint,
                onMove = { id, dx, dy ->
                    measured?.controls?.let { deck -> adjust(id, dx / deck.width, dy / deck.height) }
                },
                onMeasured = { g, _, _ -> measured = g },
            )
        }
        Text("Selected: ${selected.name}", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            measured?.let { g ->
                controlNodes(g.controls.width, g.controls.height, inputFamily, systemId, preferences.copy(touchLayout = draft), profile).forEach { node ->
                    FilterChip(selected = selected == node.id, onClick = { selected = node.id }, label = { Text(node.id.name) })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Left" to (-.015f to 0f), "Right" to (.015f to 0f), "Up" to (0f to -.015f), "Down" to (0f to .015f)).forEach { (name, movement) ->
                TextButton(onClick = { checkpoint(); adjust(selected, movement.first, movement.second) },
                    modifier = Modifier.semantics { contentDescription = "Move ${selected.name} $name" }) { Text(name) }
            }
        }
        val current = draft.controls[controlKey(profile, selected)] ?: ControlPlacement()
        Text("Control size: ${(current.scale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = current.scale, onValueChange = { checkpoint(); adjust(selected, scale = it) }, valueRange = .7f..1.3f,
            modifier = Modifier.semantics { contentDescription = "Selected control size" })
        message?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = history.isNotEmpty(), onClick = {
                if (history.isNotEmpty()) draft = history.removeAt(history.lastIndex)
            }, modifier = Modifier.testTag("layout-undo")) { Text("Undo") }
            TextButton(onClick = { checkpoint(); draft = TouchLayoutAdjustments(); message = "Defaults restored in preview. Save to apply." }) { Text("Reset") }
            Button(onClick = { onChange(draft); history.clear(); message = "Layout saved." }, modifier = Modifier.weight(1f).testTag("layout-save")) { Text("Save layout") }
        }
        Text("Each orientation has its own layout. Changes apply when you save.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}
