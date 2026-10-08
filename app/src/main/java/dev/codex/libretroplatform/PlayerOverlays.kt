package dev.codex.libretroplatform

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codex.libretroplatform.runtime.api.Capability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseOverlay(
    controller: FrontendController,
    paused: PlayerState.Paused?,
    onExportNativeSave: () -> Unit = {},
    onExportSaveState: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
) {
    if (paused == null) return
    var replaceSlot by remember { mutableStateOf<Int?>(null) }
    var restoreHistory by remember { mutableStateOf<AutomaticCheckpoint?>(null) }
    val state = controller.uiState
    val supportsStates = Capability.SaveState in state.runtimeCapabilities
    Dialog(
        onDismissRequest = controller::resume,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.92f),
            color = Panel, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paused", style = MaterialTheme.typography.headlineMedium)
                Text(controller.selectedItem()?.displayTitle ?: "Your game", color = TextMuted)
                Button(onClick = controller::resume, modifier = Modifier.fillMaxWidth().testTag("resume-player")) { Text("Resume game") }
                if (controller.checkpointBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
                Text("Automatic history", style = MaterialTheme.typography.titleMedium)
                Text("The last three checkpoints. Manual slots are kept separately.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                if (controller.automaticCheckpoints.isEmpty()) Text(
                    when {
                        !supportsStates -> "This core does not support checkpoints."
                        !state.preferences.autoSaveOnBackground -> "Automatic checkpoints are off. Enable them in Save settings."
                        else -> "Your next pause will create a checkpoint."
                    }, color = TextMuted,
                )
                controller.automaticCheckpoints.forEach { checkpoint ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val image = remember(checkpoint.id) { checkpoint.thumbnailPath?.let(BitmapFactory::decodeFile)?.asImageBitmap() }
                        if (image != null) Image(image, contentDescription = "Checkpoint preview", contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp, 54.dp))
                        Column(Modifier.weight(1f)) {
                            Text(saveStateAgeLabel(checkpoint.createdAtEpochMs), style = MaterialTheme.typography.bodyMedium)
                            Text(formatBytes(checkpoint.payloadSizeBytes), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(enabled = !controller.checkpointBusy, onClick = { restoreHistory = checkpoint },
                            modifier = Modifier.testTag("automatic-${checkpoint.slot}")) { Text("Restore") }
                    }
                }
                HorizontalDivider()
                Text("Manual save slots", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (1..9).forEach { number ->
                        val available = state.saveStateSlots.firstOrNull { it.slot == number }?.available == true
                        FilterChip(selected = state.saveStateSlot == number, onClick = { controller.setSaveStateSlot(number) },
                            label = { Text("$number${if (available) " •" else ""}") }, modifier = Modifier.testTag("slot-$number"))
                    }
                }
                val selected = state.saveStateSlots.firstOrNull { it.slot == state.saveStateSlot }
                Text(selected?.createdAtEpochMs?.let { "Slot ${state.saveStateSlot} · ${saveStateAgeLabel(it)}" }
                    ?: "Slot ${state.saveStateSlot} is empty", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = supportsStates && !controller.checkpointBusy, onClick = {
                        if (selected?.available == true) replaceSlot = state.saveStateSlot else controller.saveState()
                    }, modifier = Modifier.weight(1f).testTag("manual-save")) { Text("Save slot") }
                    OutlinedButton(enabled = supportsStates && selected?.available == true && !controller.checkpointBusy,
                        onClick = controller::loadState, modifier = Modifier.weight(1f).testTag("manual-load")) { Text("Load slot") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onExportNativeSave, modifier = Modifier.weight(1f)) { Text("Export battery save") }
                    TextButton(onClick = onExportSaveState, modifier = Modifier.weight(1f)) { Text("Export state") }
                }
                OutlinedButton(onClick = { controller.saveNative() }, modifier = Modifier.fillMaxWidth()) { Text("Save battery data now") }
                TextButton(onClick = onDiagnostics) { Text("Performance & runtime") }
                TextButton(onClick = { controller.openSettings(AppRoute.Player) }) { Text("Player settings") }
                TextButton(onClick = controller::exitPlayer, modifier = Modifier.fillMaxWidth().testTag("exit-player")) { Text("Save & return to library") }
            }
        }
    }
    replaceSlot?.let { slot ->
        AlertDialog(onDismissRequest = { replaceSlot = null }, title = { Text("Replace slot $slot?") },
            text = { Text("This replaces the manual checkpoint in this slot. Automatic history stays separate.") },
            confirmButton = { TextButton(onClick = { controller.setSaveStateSlot(slot); controller.saveState(); replaceSlot = null }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { replaceSlot = null }) { Text("Cancel") } })
    }
    restoreHistory?.let { checkpoint ->
        AlertDialog(onDismissRequest = { restoreHistory = null }, title = { Text("Restore this checkpoint?") },
            text = { Text("Return to the checkpoint from ${saveStateAgeLabel(checkpoint.createdAtEpochMs)}. Your saved manual slots will not change.") },
            confirmButton = { TextButton(onClick = { controller.restoreAutomaticCheckpoint(checkpoint.id); restoreHistory = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreHistory = null }) { Text("Cancel") } })
    }
}
