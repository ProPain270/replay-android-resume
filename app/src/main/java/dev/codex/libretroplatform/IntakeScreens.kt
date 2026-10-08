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
internal fun WelcomeScreen(controller: FrontendController) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).widthIn(max = NarrowRail).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Your game library", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Add games you own to play on this device.", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
            Surface(
                color = Panel,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Game files aren't included.", fontWeight = FontWeight.SemiBold)
                    Text("Choose files or a folder. Review them before importing; your originals stay unchanged.", color = TextMuted)
                }
            }
            AccentAction("Add games", controller::chooseGames, Modifier.fillMaxWidth())
            if (controller.uiState.library.isNotEmpty()) {
                QuietAction("Open library", controller::openLibrary, Modifier.fillMaxWidth())
            }
            TextButton(onClick = { controller.openRecovery() }) { Text("Recovery and storage") }
        }
    }
}

@Composable
internal fun PromiseRow(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(number, color = Amber, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentSourceScreen(controller: FrontendController) {
    val context = LocalContext.current
    val state = controller.uiState
    var showFileTypes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val candidates = uris.map { uri ->
            ImportCandidate(uri = uri, displayName = displayName(context, uri))
        }
        controller.receiveFiles(candidates)
    }
    val singleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            controller.receiveFiles(listOf(ImportCandidate(uri, displayName(context, uri))))
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            controller.receiveFolder(uri, displayName(context, uri).ifBlank { "Selected folder" })
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Add to library") },
                navigationIcon = { TextButton(onClick = { controller.navigateBack() }) { Text("Back") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Choose your games", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Select game files or scan a folder. You'll review the selection before importing.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccentAction("Select files", { filePicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction("Select one file", { singleFilePicker.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                QuietAction("Scan a folder", { folderPicker.launch(null) }, Modifier.weight(1f))
            }
            TextButton(onClick = { showFileTypes = !showFileTypes }) {
                Text(if (showFileTypes) "Hide supported file types" else "Supported file types")
            }
            if (showFileTypes) Text(
                "Folder scan searches subfolders for .gb, .gbc, .gba, .sfc, .smc, .fig, .swc, .bs, .st, .n64, .z64, .v64, .gcm, .iso, .rvz, .gcz, .ciso, and .wbfs. Up to 2,000 game files are queued for review at a time.",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
            if (state.folderScanInProgress) {
                QuietAction("Cancel folder scan", controller::cancelFolderScan, Modifier.fillMaxWidth())
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionEyebrow("Before you import")
            Text("The library checks each game and shows whether it can play on this build.", color = TextMuted)
            Text(
                "Your original files stay unchanged. The app only accesses the files or folder you choose.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportScreen(controller: FrontendController) {
    val state = controller.uiState
    val importState = state.importState
    val candidates = state.pendingCandidates
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Review import") },
                navigationIcon = { TextButton(onClick = { controller.navigateBack() }) { Text("Back") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionEyebrow("Selected games", "${candidates.size} files")
            Text(
                if (importState is ImportState.Queued) "Remove any files you don't want, then start the import."
                else "Your original files stay unchanged.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (importState) {
                ImportState.NoSelection -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = Panel,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
                    ) {
                        Text("No selection yet. Go back and choose a file or folder to begin.", modifier = Modifier.padding(18.dp), color = TextMuted)
                    }
                }
                is ImportState.Queued -> {
                    CandidateList(candidates, onRemove = { candidate -> controller.removePendingCandidate(candidate.uri) })
                    AccentAction("Start import  →", controller::startImport, Modifier.fillMaxWidth())
                }
                is ImportState.Importing -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = Panel,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Preparing ${importState.total} item(s)…", fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber, trackColor = SurfaceStroke)
                        }
                    }
                    CandidateList(candidates)
                    QuietAction("Cancel import", controller::cancelImport, Modifier.fillMaxWidth())
                }
                is ImportState.Completed -> {
                    ImportSummaryCard(importState.summary)
                    CandidateList(candidates)
                    AccentAction("Open library  →", controller::openLibrary, Modifier.fillMaxWidth())
                }
                is ImportState.Cancelled -> {
                    ImportSummaryCard(importState.summary)
                    CandidateList(candidates)
                    AccentAction("Open library  →", controller::openLibrary, Modifier.fillMaxWidth())
                }
            }
            state.statusMessage?.let { message ->
                StatusMessage(message)
            }
        }
    }
}

@Composable
internal fun CandidateList(candidates: List<ImportCandidate>, onRemove: ((ImportCandidate) -> Unit)? = null) {
    val previewLimit = 80
    val preview = candidates.take(previewLimit)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        preview.forEach { candidate ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(if (candidate.isDirectory) "Folder" else "File", positive = true)
                    Text(candidate.displayName, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (onRemove != null) {
                        TextButton(onClick = { onRemove(candidate) }) { Text("Remove") }
                    }
                }
            }
        }
        if (candidates.size > preview.size) {
            Text(
                "Showing the first ${preview.size}; all ${candidates.size} candidates remain queued for import.",
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun ImportSummaryCard(summary: ImportSummary) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (summary.cancelled) "Import cancelled" else "Import complete",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("${summary.imported} added  •  ${summary.skipped} skipped")
            if (summary.failures.isNotEmpty()) {
                HorizontalDivider()
                summary.failures.forEach { failure ->
                    Text("${failure.displayName}: ${failure.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
