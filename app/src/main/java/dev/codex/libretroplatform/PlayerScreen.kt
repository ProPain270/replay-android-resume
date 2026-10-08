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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
internal fun PlayerScreen(
    controller: FrontendController,
    onExportNativeSave: () -> Unit = {},
    onExportSaveState: () -> Unit = {},
) {
    val state = controller.uiState
    val item = controller.selectedItem()
    val preferences = controller.effectivePreferences(item)
    val profile = ConsoleCatalog.forSystem(item?.system.orEmpty())
    val folds = rememberFoldingFeatures()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var stageOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var showDiagnostics by remember { mutableStateOf(false) }
    PlayerSystemUi(preferences)
    Column(
        Modifier.fillMaxSize().background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("player-screen"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = controller::exitPlayer) { Text("Library") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(item?.displayTitle ?: "Player", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(item?.system?.uppercase() ?: "PLAYER", color = TextMuted, fontSize = 10.sp)
            }
            TextButton(onClick = { controller.openSettings(AppRoute.Player) }) { Text("Settings") }
            TextButton(
                onClick = { if (state.playerState is PlayerState.Paused) controller.resume() else controller.pause() },
                modifier = Modifier.testTag("player-pause"),
            ) { Text(if (state.playerState is PlayerState.Paused) "Resume" else "Pause") }
        }
        val feature = folds.firstOrNull { it.isSeparating }
        val fold = feature?.let {
            FoldBounds(
                DeckRect((it.bounds.left - stageOrigin.x) / density,
                    (it.bounds.top - stageOrigin.y) / density,
                    it.bounds.width() / density, it.bounds.height() / density),
                it.orientation == androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL,
                it.isSeparating,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            PlayerStage(
                preferences = preferences,
                family = profile?.inputFamily ?: InputFamily.Handheld,
                systemId = profile?.id,
                aspectRatio = profile?.aspectRatio ?: 4f / 3f,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { stageOrigin = it.positionInWindow() },
                fold = fold,
                onInput = controller::sendInput,
            ) {
                RuntimeVideoSurface(
                    controller = controller,
                    aspectRatio = profile?.aspectRatio ?: 4f / 3f,
                    screenBackground = playerSkin(preferences.displayPreset).screenFallback,
                    treatment = if (preferences.performancePreset == PerformancePreset.BatterySaver) ScreenTreatment.Clean else preferences.screenTreatment,
                    scalingMode = preferences.pixelScalingMode,
                )
            }
            PauseOverlay(controller, state.playerState as? PlayerState.Paused, onExportNativeSave, onExportSaveState, onDiagnostics = { showDiagnostics = true })
        }
    }
    if (showDiagnostics) RuntimeDiagnosticsDialog(item, state, onDismiss = { showDiagnostics = false })
    controller.exitSaveFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = controller::dismissExitSaveFailure,
            title = { Text("Your session is still open") },
            text = { Text("$failure\n\nRetry to save your progress. Leaving without saving may lose progress from this session.") },
            confirmButton = {
                TextButton(onClick = controller::exitPlayer, modifier = Modifier.testTag("retry-exit-save")) { Text("Retry save") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = controller::dismissExitSaveFailure) { Text("Stay here") }
                    TextButton(onClick = controller::exitWithoutSaving, modifier = Modifier.testTag("exit-without-saving")) {
                        Text("Leave without saving", color = Terracotta)
                    }
                }
            },
        )
    }
}

@Composable
internal fun RuntimeDiagnosticsDialog(
    item: LibraryItem?,
    state: FrontendUiState,
    onDismiss: () -> Unit,
) {
    val diagnostics = state.runtimeDiagnostics
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runtime details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SESSION", color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                DetailRow("Title", item?.displayTitle ?: "Unknown")
                DetailRow("Core", diagnostics?.coreId ?: item?.coreId ?: "Not resolved")
                DetailRow("State", when (val playerState = state.playerState) {
                    PlayerState.Ready -> "Ready"
                    PlayerState.Running -> "Running"
                    is PlayerState.Paused -> "Paused (${playerState.reason.name.lowercase()})"
                })
                DetailRow(
                    "Video",
                    diagnostics?.let { diagnostic ->
                        if (diagnostic.videoWidth != null && diagnostic.videoHeight != null) {
                            "${diagnostic.videoWidth} × ${diagnostic.videoHeight} native frame"
                        } else "Waiting for first frame"
                    } ?: "Waiting for first frame",
                )
                DetailRow("Audio", diagnostics?.audioSampleRateHz?.let { "$it Hz PCM stereo" } ?: "Reported after first frame")
                DetailRow("Capabilities", state.runtimeCapabilities.sortedBy { it.name }.joinToString { it.name })
                DetailRow("Identity", item?.contentId?.take(16)?.plus("…") ?: "Unknown")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun PlayerSystemUi(preferences: UserPreferences) {
    val view = LocalView.current
    DisposableEffect(preferences.immersivePlayer, preferences.keepScreenOn) {
        val activity = view.context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val systemBars = WindowInsetsCompat.Type.systemBars()
        val barsWereVisible = window?.let { ViewCompat.getRootWindowInsets(it.decorView)?.isVisible(systemBars) } ?: true
        view.keepScreenOn = preferences.keepScreenOn
        if (preferences.immersivePlayer) {
            insetsController?.hide(systemBars)
            insetsController?.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController?.show(systemBars)
        }
        onDispose {
            view.keepScreenOn = false
            if (barsWereVisible) insetsController?.show(systemBars) else insetsController?.hide(systemBars)
        }
    }
}

@Composable
internal fun RuntimeVideoSurface(
    controller: FrontendController,
    aspectRatio: Float = 10f / 9f,
    screenBackground: Color = Color(0xFFB7C49B),
    treatment: ScreenTreatment = ScreenTreatment.Clean,
    scalingMode: PixelScalingMode = PixelScalingMode.Fit,
) {
    val context = LocalContext.current
    val surfaceBackground = android.graphics.Color.rgb(
        (screenBackground.red * 255).toInt(),
        (screenBackground.green * 255).toInt(),
        (screenBackground.blue * 255).toInt(),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(surfaceBackground)),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val cropScale = if (scalingMode == PixelScalingMode.Fill) 1.08f else 1f
                    scaleX = cropScale
                    scaleY = cropScale
                    clip = scalingMode == PixelScalingMode.Fill
                },
            factory = {
                TextureView(context).also { view ->
                controller.setVideoThumbnailProvider { view.getBitmap() }
                view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var nativeSurface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        nativeSurface = Surface(texture)
                        controller.attachSurface(nativeSurface!!)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        nativeSurface?.let { surface -> controller.attachSurface(surface) }
                    }

                    override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                        controller.detachSurface()
                        controller.clearVideoThumbnailProvider()
                        nativeSurface?.release()
                        nativeSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                }
                }
            },
            update = { view ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    view.setRenderEffect(
                        when (treatment) {
                            ScreenTreatment.Mono -> {
                                val matrix = ColorMatrix().apply { setSaturation(0f) }
                                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
                            }
                            ScreenTreatment.Ghosting -> RenderEffect.createBlurEffect(
                                0.45f,
                                0.45f,
                                android.graphics.Shader.TileMode.CLAMP,
                            )
                            else -> null
                        },
                    )
                }
            },
        )
        if (treatment == ScreenTreatment.Scanlines) {
            Canvas(Modifier.fillMaxSize()) {
                val spacing = 4.dp.toPx()
                var y = spacing
                while (y < size.height) {
                    drawLine(
                        color = Color.Black.copy(alpha = .12f),
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    y += spacing
                }
            }
        }
    }
}

internal fun gameDisplayAspectRatio(item: LibraryItem?): Float {
    return ConsoleCatalog.forSystem(item?.system.orEmpty())?.aspectRatio ?: (4f / 3f)
}
