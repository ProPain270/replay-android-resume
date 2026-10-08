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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(controller: FrontendController) {
    val state = controller.uiState
    var showCreateCollection by remember { mutableStateOf(false) }
    var showGrid by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    if (state.library.isEmpty()) {
        EmptyLibrary(
            modifier = Modifier.fillMaxWidth().widthIn(max = NarrowRail).fillMaxSize(),
            onAdd = controller::chooseGames,
            onRecovery = controller::openRecovery,
            onSettings = { controller.openSettings(AppRoute.Library) },
        )
        return
    }
    val query = state.libraryQuery.trim()
    val visibleLibrary = state.library
        .filter { item ->
            when (state.libraryFilter) {
                LibraryFilter.All -> true
                LibraryFilter.Playable -> item.supportStatus == SupportStatus.Playable
                LibraryFilter.Favorites -> state.preferences.favorites.contains(item.id)
            }
        }
        .filter { item -> state.librarySystem == null || item.system == state.librarySystem }
        .filter { item ->
            state.libraryCollectionId == null || state.collections
                .firstOrNull { collection -> collection.id == state.libraryCollectionId }
                ?.itemIds
                ?.contains(item.id) == true
        }
        .filter { item ->
            query.isBlank() || listOf(item.displayTitle, item.title, item.system, item.sourceDisplayName)
                .any { value -> value.contains(query, ignoreCase = true) }
        }
        .let { items ->
            when (state.librarySort) {
                LibrarySort.Recent -> items.sortedWith(
                    compareByDescending<LibraryItem> { it.lastPlayedAt ?: it.importedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.sortTitleKey },
                )
                LibrarySort.Title -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortTitleKey })
            }
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gridColumns = ((maxWidth.value.coerceAtMost(ContentRail.value) - 44f) / 160f).toInt().coerceIn(2, 4)
        val gridAvailable = maxWidth >= 360.dp
        val useGrid = showGrid && gridAvailable
        LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = ContentRail)
            .align(Alignment.TopCenter)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Library",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${state.library.size} game${if (state.library.size == 1) "" else "s"}",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.semantics {
                            contentDescription = "Open settings"
                            role = Role.Button
                        },
                        onClick = { controller.openSettings(AppRoute.Library) },
                        shape = RoundedCornerShape(12.dp),
                        color = PanelRaised,
                        contentColor = Ivory,
                    ) {
                        Text("⚙", modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontWeight = FontWeight.Bold)
                    }
                    Surface(
                        modifier = Modifier.semantics {
                            contentDescription = "Add games"
                            role = Role.Button
                        },
                        onClick = controller::chooseGames,
                        shape = RoundedCornerShape(12.dp),
                        color = Amber,
                        contentColor = Color(0xFF2A1606),
                    ) {
                        Text(
                            "+  Add",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.libraryQuery,
                onValueChange = controller::updateLibraryQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search games") },
                placeholder = { Text("Title, system or file") },
                trailingIcon = {
                    if (state.libraryQuery.isNotEmpty()) {
                        TextButton(onClick = { controller.updateLibraryQuery("") }) { Text("Clear") }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.values().forEach { filter ->
                    FilterChip(
                        selected = state.libraryFilter == filter,
                        onClick = { controller.setLibraryFilter(filter) },
                        label = { Text(filter.label()) },
                    )
                }
                Surface(
                    onClick = {
                        controller.setLibrarySort(if (state.librarySort == LibrarySort.Recent) LibrarySort.Title else LibrarySort.Recent)
                    },
                    shape = RoundedCornerShape(100.dp),
                    color = PanelRaised,
                    contentColor = Amber,
                ) {
                    Text(
                        if (state.librarySort == LibrarySort.Recent) "RECENT ↓" else "TITLE A–Z",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        val availableSystems = state.library.map { it.system }.distinct().sorted()
        if (availableSystems.size > 1) item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.librarySystem == null,
                    onClick = { controller.setLibrarySystem(null) },
                    label = { Text("All systems") },
                )
                availableSystems.forEach { system ->
                    FilterChip(
                        selected = state.librarySystem == system,
                        onClick = { controller.setLibrarySystem(system) },
                        label = { Text(system) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.collections.isNotEmpty()) {
                    FilterChip(
                        selected = state.libraryCollectionId == null,
                        onClick = { controller.setLibraryCollection(null) },
                        label = { Text("All collections") },
                    )
                    state.collections.forEach { collection ->
                        FilterChip(
                            selected = state.libraryCollectionId == collection.id,
                            onClick = { controller.setLibraryCollection(collection.id) },
                            label = { Text(collection.name) },
                        )
                    }
                }
                TextButton(onClick = { showCreateCollection = true }) { Text("+ Collection") }
            }
        }
        val continueItem = state.library
            .filter { it.supportStatus == SupportStatus.Playable && it.privateContentRef != null && it.lastPlayedAt != null }
            .maxByOrNull { it.lastPlayedAt ?: 0L }
        if (continueItem != null && query.isBlank() && state.libraryFilter == LibraryFilter.All &&
            state.librarySystem == null && state.libraryCollectionId == null) item {
            Card(
                onClick = { controller.openPlayer(continueItem.id) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Continue ${continueItem.displayTitle}"
                },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GameArtwork(continueItem, Modifier.size(60.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Continue", color = Amber, style = MaterialTheme.typography.labelMedium)
                        Text(continueItem.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(continueItem.system, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("›", color = Amber, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${visibleLibrary.size} games", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = TextMuted)
                if (gridAvailable) {
                    TextButton(onClick = { showGrid = !showGrid }) {
                        Text(if (showGrid) "List view" else "Grid view")
                    }
                }
            }
        }
        if (visibleLibrary.isEmpty()) {
            item {
                EmptySearchState(query = query, onClear = {
                    controller.updateLibraryQuery("")
                    controller.setLibraryFilter(LibraryFilter.All)
                    controller.setLibrarySystem(null)
                    controller.setLibraryCollection(null)
                })
            }
        } else if (useGrid) {
            items(visibleLibrary.chunked(gridColumns), key = { row -> "grid-${row.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        LibraryArtCard(
                            item = item,
                            isFavorite = state.preferences.favorites.contains(item.id),
                            onToggleFavorite = { controller.toggleFavorite(item.id) },
                            onClick = { controller.openDetails(item.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(visibleLibrary, key = { it.id }) { item ->
                LibraryCard(
                    item = item,
                    isFavorite = state.preferences.favorites.contains(item.id),
                    onToggleFavorite = { controller.toggleFavorite(item.id) },
                    onClick = { controller.openDetails(item.id) },
                )
            }
        }
        state.statusMessage?.let { message -> item { StatusMessage(message) } }
    }
    }
    if (showCreateCollection) {
        CreateCollectionDialog(
            onDismiss = { showCreateCollection = false },
            onCreate = { name ->
                controller.createCollection(name)
                showCreateCollection = false
            },
        )
    }
}

@Composable
internal fun CreateCollectionDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Group games into a collection.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Collection name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun EmptyLibrary(modifier: Modifier, onAdd: () -> Unit, onRecovery: () -> Unit, onSettings: (() -> Unit)? = null) {
    Column(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Add your first game", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Import a game you already own and it will appear here with its support status and saves.",
            color = TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Add games", fontWeight = FontWeight.Bold) }
        TextButton(onClick = onRecovery) { Text("View recovery and limits") }
        onSettings?.let { TextButton(onClick = it) { Text("Settings") } }
    }
}

@Composable
internal fun LibraryCard(
    item: LibraryItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameArtwork(item, Modifier.size(64.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.system, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOf(item.supportStatus.label(), item.saveSummary.label()).joinToString(" · "),
                    color = TextMuted, style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Surface(
                    onClick = onToggleFavorite,
                    modifier = Modifier.semantics {
                        contentDescription = if (isFavorite) "Remove ${item.displayTitle} from favorites" else "Add ${item.displayTitle} to favorites"
                        role = Role.Button
                    },
                    color = Color.Transparent,
                    contentColor = if (isFavorite) Amber else TextMuted,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (isFavorite) "★" else "☆", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun LibraryArtCard(
    item: LibraryItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
    ) {
        Box {
            GameArtwork(item, Modifier.fillMaxWidth().aspectRatio(1f))
            Surface(
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).semantics {
                    contentDescription = if (isFavorite) "Remove ${item.displayTitle} from favorites" else "Add ${item.displayTitle} to favorites"
                    role = Role.Button
                },
                shape = RoundedCornerShape(10.dp),
                color = Ink.copy(alpha = .94f),
                contentColor = if (isFavorite) Amber else Ivory,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text(if (isFavorite) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.displayTitle, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            Text(item.system, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (item.saveSummary == SaveSummary.RecoveryAvailable) item.saveSummary.label() else item.supportStatus.label(),
                color = if (item.saveSummary == SaveSummary.RecoveryAvailable) Amber else TextMuted,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun EmptySearchState(query: String, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No matching games", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (query.isBlank()) "Try another filter or collection." else "Try a different title, system or file.",
                color = TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(onClick = onClear) { Text("Clear search and filters") }
        }
    }
}

@Composable
internal fun BrandMark(size: androidx.compose.ui.unit.Dp = 42.dp) {
    // A neutral cartridge symbol, not an invented product wordmark.
    Canvas(Modifier.size(size)) {
        val canvasSize = this.size
        val stroke = canvasSize.minDimension * .045f
        drawRoundRect(
            color = TextMuted,
            topLeft = androidx.compose.ui.geometry.Offset(canvasSize.width * .2f, canvasSize.height * .12f),
            size = androidx.compose.ui.geometry.Size(canvasSize.width * .6f, canvasSize.height * .76f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(canvasSize.width * .08f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        drawRect(
            color = Amber,
            topLeft = androidx.compose.ui.geometry.Offset(canvasSize.width * .31f, canvasSize.height * .24f),
            size = androidx.compose.ui.geometry.Size(canvasSize.width * .38f, canvasSize.height * .24f),
        )
    }
}

@Composable
internal fun GameArtwork(item: LibraryItem, modifier: Modifier = Modifier) {
    val customArtwork = remember(item.artworkPath) {
        item.artworkPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    if (customArtwork != null) {
        Image(
            bitmap = customArtwork.asImageBitmap(),
            contentDescription = "Cover art for ${item.displayTitle}",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(PanelRaised)
            .border(1.dp, SurfaceStroke, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            item.displayTitle.firstOrNull()?.uppercase().orEmpty(),
            color = TextMuted,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsScreen(
    controller: FrontendController,
    onChooseArtwork: () -> Unit = {},
) {
    val item = controller.selectedItem()
    if (item == null) {
        controller.openLibrary()
        return
    }
    val state = controller.uiState
    var showMetadataEditor by remember(item.id) { mutableStateOf(false) }
    var showCollectionManager by remember(item.id) { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Game details") },
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
            ) {
                Box(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 52.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GameArtwork(item, Modifier.size(104.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(item.system.uppercase(), color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text(
                                item.displayTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 23.sp),
                                fontWeight = FontWeight.Black,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    item.supportStatus == SupportStatus.Playable && item.privateContentRef != null -> "Ready to play"
                                    item.privateContentRef == null -> "Game file missing"
                                    else -> item.supportStatus.label()
                                },
                                color = if (item.supportStatus == SupportStatus.Playable && item.privateContentRef != null) Mint else Amber,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).semantics {
                            contentDescription = if (state.preferences.favorites.contains(item.id)) "Remove favorite" else "Add favorite"
                            role = Role.Button
                        },
                        onClick = { controller.toggleFavorite(item.id) },
                        color = PanelRaised,
                        contentColor = if (state.preferences.favorites.contains(item.id)) Amber else TextMuted,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
                    ) {
                        Text(if (state.preferences.favorites.contains(item.id)) "★" else "☆", modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(item.supportStatus.label(), positive = item.supportStatus == SupportStatus.Playable)
                StatusPill(item.saveSummary.label(), positive = item.saveSummary != SaveSummary.NoSave)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceStroke),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("System", item.system)
                    ConsoleCatalog.forSystem(item.system)?.let { profile ->
                        DetailRow(
                            "Emulator",
                            if (profile.bundled) "${profile.coreId}  •  bundled" else "${profile.coreId}  •  core not bundled yet",
                        )
                        if (profile.requiresSystemFiles) DetailRow("System files", "Required by this emulator")
                    }
                    DetailRow("Metadata", item.metadataStatus.label())
                    if (item.titleAlias != null) DetailRow("Original title", item.title)
                    DetailRow("Source", item.sourceDisplayName)
                    DetailRow("Provenance", if (item.sourceUri != null) "Imported through Android file picker" else "Not retained")
                    DetailRow("Content identity", "SHA-256 ${item.contentId.take(12)}…  •  ${item.contentSizeBytes / 1024} KB")
                    DetailRow("Private copy", if (item.privateContentRef != null) "Verified app-private copy" else "Unavailable")
                }
            }
            if (item.supportStatus == SupportStatus.Playable && item.privateContentRef != null) {
                AccentAction("Open player  →", { controller.openPlayer(item.id) }, Modifier.fillMaxWidth())
            } else {
                Text("Player unavailable", color = TextMuted)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction("Restore copy", { controller.retryScan(item.id) }, Modifier.weight(1f))
                QuietAction("Edit title", { showMetadataEditor = true }, Modifier.weight(1f))
            }
            QuietAction("Player settings", { controller.openSettings(AppRoute.Details) }, Modifier.fillMaxWidth())
            QuietAction("Choose cover art", onChooseArtwork, Modifier.fillMaxWidth())
            QuietAction("Add to collection", { showCollectionManager = true }, Modifier.fillMaxWidth())
            if (item.artworkPath != null) {
                TextButton(onClick = { controller.removeArtwork() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove custom cover art", color = TextMuted)
                }
            }
            TextButton(onClick = controller::removeSelectedItem, modifier = Modifier.fillMaxWidth()) {
                Text("Remove from library", color = Terracotta)
            }
            state.statusMessage?.let { StatusMessage(it) }
            Text(
                if (item.supportStatus == SupportStatus.Playable) {
                    "Your original game file stays unchanged."
                } else {
                    "This game needs a supported emulator and a readable game file."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showMetadataEditor) {
        MetadataEditorDialog(
            item = item,
            onDismiss = { showMetadataEditor = false },
            onSave = { alias, sortTitle ->
                controller.updateLibraryMetadata(item.id, alias, sortTitle)
                showMetadataEditor = false
            },
        )
    }
    if (showCollectionManager) {
        CollectionManagerDialog(
            item = item,
            collections = state.collections,
            onToggle = { collectionId -> controller.toggleItemInCollection(collectionId, item.id) },
            onDelete = controller::deleteCollection,
            onDismiss = { showCollectionManager = false },
        )
    }
}

@Composable
internal fun CollectionManagerDialog(
    item: LibraryItem,
    collections: List<LibraryCollection>,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            if (collections.isEmpty()) {
                Text("No collections yet. Create one in Library.", color = TextMuted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose collections for ${item.displayTitle}.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    collections.forEach { collection ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = item.id in collection.itemIds,
                                onClick = { onToggle(collection.id) },
                                label = { Text(collection.name) },
                            )
                            TextButton(onClick = { onDelete(collection.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun MetadataEditorDialog(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var alias by remember(item.id, item.titleAlias) { mutableStateOf(item.titleAlias.orEmpty()) }
    var sortTitle by remember(item.id, item.sortTitle) { mutableStateOf(item.sortTitle.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit library title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Changes the library name, not the game file.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Display title") },
                    placeholder = { Text(item.title) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sortTitle,
                    onValueChange = { sortTitle = it },
                    label = { Text("Sort title (optional)") },
                    placeholder = { Text("Same as display title") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(alias, sortTitle) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
