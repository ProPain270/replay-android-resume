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

class MainActivity : ComponentActivity() {
    private lateinit var controller: FrontendController
    private lateinit var gamepadInput: GamepadInputRouter
    private lateinit var inputManager: InputManager
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            if (::gamepadInput.isInitialized) controller.setConnectedGamepads(gamepadInput.connectedDevices())
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (::gamepadInput.isInitialized) {
                gamepadInput.onDeviceRemoved(deviceId)
                controller.setConnectedGamepads(gamepadInput.connectedDevices())
            }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            if (::gamepadInput.isInitialized) controller.setConnectedGamepads(gamepadInput.connectedDevices())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ViewModelProvider(
            this,
            FrontendViewModel.Factory(application),
        )[FrontendViewModel::class.java].controller
        gamepadInput = GamepadInputRouter(controller)
        controller.setConnectedGamepads(gamepadInput.connectedDevices())
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
        setContent { LibretroApp(controller) }
    }

    override fun onResume() {
        super.onResume()
        if (::gamepadInput.isInitialized) controller.setConnectedGamepads(gamepadInput.connectedDevices())
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (::gamepadInput.isInitialized && gamepadInput.onKeyEvent(event)) true else super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (::gamepadInput.isInitialized && gamepadInput.onMotionEvent(event)) true else super.dispatchGenericMotionEvent(event)

    override fun onStop() {
        if (::gamepadInput.isInitialized) gamepadInput.clear()
        controller.onLifecycleStopped()
        super.onStop()
    }

    override fun onDestroy() {
        if (::inputManager.isInitialized) inputManager.unregisterInputDeviceListener(inputDeviceListener)
        if (::gamepadInput.isInitialized) gamepadInput.clear()
        super.onDestroy()
    }
}

@Composable
fun LibretroApp(controller: FrontendController) {
    val context = LocalContext.current
    val nativeSaveExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(controller::exportNativeSave) }
    val saveStateExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(controller::exportSaveState) }
    val backupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(controller::exportPortableBackup) }
    val backupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(controller::importPortableBackup) }
    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(controller::importArtwork) }
    val systemFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { controller.importSystemFile(it, displayName(context, it)) } }
    val currentState = controller.uiState
    // Keep the callback attached to observable UI state. Root destinations
    // leave Android's system back-to-home animation available, while nested
    // routes retain the product's safe import/player semantics.
    BackHandler(
        enabled = currentState.route != AppRoute.Welcome && currentState.route != AppRoute.Library,
    ) {
        controller.navigateBack()
    }
    MaterialTheme(colorScheme = LibretroColors) {
        Box(modifier = Modifier.fillMaxSize().background(Ink)) {
            AmbientBackdrop()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                val content: @Composable () -> Unit = {
                    when (currentState.route) {
                        AppRoute.Welcome -> WelcomeScreen(controller)
                        AppRoute.ContentSource -> ContentSourceScreen(controller)
                        AppRoute.Import -> ImportScreen(controller)
                        AppRoute.Library -> LibraryScreen(controller)
                        AppRoute.Details -> DetailsScreen(
                            controller = controller,
                            onChooseArtwork = { artworkPicker.launch(arrayOf("image/*")) },
                        )
                        AppRoute.Player -> PlayerScreen(
                            controller = controller,
                            onExportNativeSave = { nativeSaveExporter.launch(controller.suggestedSaveFileName(state = false)) },
                            onExportSaveState = { saveStateExporter.launch(controller.suggestedSaveFileName(state = true)) },
                        )
                        AppRoute.Settings -> SettingsScreen(
                            controller = controller,
                            onExportBackup = { backupExporter.launch(controller.suggestedBackupFileName()) },
                            onImportBackup = { backupImporter.launch(arrayOf("application/zip", "application/octet-stream")) },
                            onImportSystemFile = { systemFilePicker.launch(arrayOf("*/*")) },
                        )
                        AppRoute.Recovery -> RecoveryScreen(controller)
                    }
                }
                if (currentState.route == AppRoute.Welcome || currentState.route == AppRoute.Player) {
                    content()
                } else {
                    AdaptiveAppFrame(
                        controller = controller,
                        route = currentState.route,
                        content = content,
                    )
                }
            }
        }
    }
}

private enum class AdaptiveDestination(
    val label: String,
    val glyph: String,
) {
    Library("Library", "⌂"),
    Add("Add games", "+"),
    Settings("Settings", "⚙"),
    Recovery("Recovery", "↺"),
}

private fun adaptiveDestination(route: AppRoute): AdaptiveDestination = when (route) {
    AppRoute.ContentSource, AppRoute.Import -> AdaptiveDestination.Add
    AppRoute.Settings -> AdaptiveDestination.Settings
    AppRoute.Recovery -> AdaptiveDestination.Recovery
    else -> AdaptiveDestination.Library
}

@Composable
private fun AdaptiveAppFrame(
    controller: FrontendController,
    route: AppRoute,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = adaptiveWindowInfo(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
        if (window.isLargeEnoughForNavigationRail) {
            Row(Modifier.fillMaxSize()) {
                AdaptiveNavigationRail(controller = controller, route = route)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    content()
                }
            }
        } else {
            content()
        }
    }
}

@Composable
private fun AdaptiveNavigationRail(
    controller: FrontendController,
    route: AppRoute,
) {
    val selected = adaptiveDestination(route)
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding(),
        containerColor = Panel.copy(alpha = .94f),
        header = {
            Column(
                modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrandMark(size = 38.dp)
                Text("REPLAY", color = TextMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
            }
        },
    ) {
        AdaptiveDestination.values().forEach { destination ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = {
                    when (destination) {
                        AdaptiveDestination.Library -> controller.openLibrary()
                        AdaptiveDestination.Add -> controller.chooseGames()
                        AdaptiveDestination.Settings -> controller.openSettings(AppRoute.Library)
                        AdaptiveDestination.Recovery -> controller.openRecovery()
                    }
                },
                icon = { Text(destination.glyph, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black) },
                label = { Text(destination.label, maxLines = 1) },
                alwaysShowLabel = true,
                modifier = Modifier.semantics {
                    contentDescription = destination.label
                    role = Role.Button
                },
            )
        }
    }
}

internal fun displayName(context: Context, uri: Uri): String {
    val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor.use { result ->
        if (result != null && result.moveToFirst()) {
            val index = result.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return result.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Selected content" } ?: "Selected content"
}
