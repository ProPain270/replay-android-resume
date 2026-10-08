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

internal val Ink = Color(0xFF171817)
internal val Panel = Color(0xFF222321)
internal val PanelRaised = Color(0xFF2D2E2B)
internal val Amber = Color(0xFFE6B66D)
internal val Ivory = Color(0xFFF0EDE4)
// Retain the shared token API without introducing extra decorative accents.
internal val Mint = Ivory
internal val Terracotta = Color(0xFFE0B0A5)
internal val Lilac = Ivory
internal val TextMuted = Color(0xFFB5B5AB)
internal val ContentRail = 760.dp
internal val NarrowRail = 540.dp
internal val Hairline = Color(0xFF373833)
internal val SurfaceStroke = Color(0xFF41423C)
internal val SoftAmber = Amber.copy(alpha = .10f)
internal val SoftLilac = Ivory.copy(alpha = .06f)

/** Restrained hardware palettes shared by the player shell and its controls. */
internal data class PlayerSkin(
    val background: Color,
    val chrome: Color,
    val chromeText: Color,
    val accent: Color,
    val positive: Color,
    val exitSurface: Color,
    val exitText: Color,
    val shellTop: Color,
    val shellBottom: Color,
    val shellBorder: Color,
    val hardwareInk: Color,
    val logoInk: Color,
    val screenBezel: Color,
    val screenFallback: Color,
    val screenBorder: Color,
    val power: Color,
    val controlFill: Color,
    val controlText: Color,
    val controlBorder: Color,
    val dpadShadow: Color,
    val dpadFill: Color,
    val dpadHighlight: Color,
    val dpadCenter: Color,
    val dpadCenterBorder: Color,
)

internal fun playerSkin(preset: DisplayPreset): PlayerSkin = when (preset) {
    DisplayPreset.Classic -> PlayerSkin(
        background = Color(0xFF111514),
        chrome = Color(0xFF1B2220),
        chromeText = Color(0xFFE2E5DD),
        accent = Color(0xFFE0A15C),
        positive = Color(0xFFA8C9B3),
        exitSurface = Color(0xFF2C2422),
        exitText = Color(0xFFE0B0A5),
        shellTop = Color(0xFFD9D7CC),
        shellBottom = Color(0xFFB8B8AE),
        shellBorder = Color(0xFF777A72),
        hardwareInk = Color(0xFF373B37),
        logoInk = Color(0xFF30483C),
        screenBezel = Color(0xFF747970),
        screenFallback = Color(0xFFB7C49B),
        screenBorder = Color(0xFF8A8E83),
        power = Color(0xFFA35E52),
        controlFill = Color(0xFF777B74),
        controlText = Color(0xFF252A27),
        controlBorder = Color(0xFF5F645E),
        dpadShadow = Color(0xFF666A64),
        dpadFill = Color(0xFF484D49),
        dpadHighlight = Color(0xFF9DA39A),
        dpadCenter = Color(0xFF3A403D),
        dpadCenterBorder = Color(0xFF6E766E),
    )
    DisplayPreset.Crisp -> PlayerSkin(
        background = Color(0xFF101415),
        chrome = Color(0xFF1B2224),
        chromeText = Color(0xFFE1E6E4),
        accent = Color(0xFFE0A15C),
        positive = Color(0xFFA8C9B3),
        exitSurface = Color(0xFF2B2524),
        exitText = Color(0xFFE0B0A5),
        shellTop = Color(0xFFD4D9D5),
        shellBottom = Color(0xFFADB6B2),
        shellBorder = Color(0xFF69736F),
        hardwareInk = Color(0xFF27302D),
        logoInk = Color(0xFF2E4A43),
        screenBezel = Color(0xFF596560),
        screenFallback = Color(0xFFD7E0D8),
        screenBorder = Color(0xFF7B8881),
        power = Color(0xFFA35E52),
        controlFill = Color(0xFF69736F),
        controlText = Color(0xFFE4E9E5),
        controlBorder = Color(0xFF4C5752),
        dpadShadow = Color(0xFF4A5550),
        dpadFill = Color(0xFF37413D),
        dpadHighlight = Color(0xFF77857D),
        dpadCenter = Color(0xFF303A36),
        dpadCenterBorder = Color(0xFF5C6A62),
    )
    DisplayPreset.Night -> PlayerSkin(
        background = Color(0xFF0E1212),
        chrome = Color(0xFF171D1C),
        chromeText = Color(0xFFE0E5DE),
        accent = Color(0xFFE0A15C),
        positive = Color(0xFFA8C9B3),
        exitSurface = Color(0xFF292322),
        exitText = Color(0xFFD9A49B),
        shellTop = Color(0xFF303734),
        shellBottom = Color(0xFF191F1D),
        shellBorder = Color(0xFF5E6962),
        hardwareInk = Color(0xFFD3D9D2),
        logoInk = Color(0xFFA9C3B3),
        screenBezel = Color(0xFF0C1110),
        screenFallback = Color(0xFF111817),
        screenBorder = Color(0xFF4D5A54),
        power = Color(0xFFA86A5C),
        controlFill = Color(0xFF343D39),
        controlText = Color(0xFFD9E0D9),
        controlBorder = Color(0xFF56625B),
        dpadShadow = Color(0xFF101513),
        dpadFill = Color(0xFF3D4842),
        dpadHighlight = Color(0xFF6D7A71),
        dpadCenter = Color(0xFF303A35),
        dpadCenterBorder = Color(0xFF59675F),
    )
}

internal data class PlayerShellCopy(
    val upperLeft: String,
    val upperRight: String,
    val logoTop: String,
    val logoBottom: String,
    val logoBottomSize: Float,
    val lowerLeft: String,
    val lowerRight: String,
)

internal fun playerShellCopy(profile: ConsoleProfile?): PlayerShellCopy = when (profile?.id) {
    "gb" -> PlayerShellCopy(
        upperLeft = "DOT MATRIX WITH STEREO SOUND",
        upperRight = "POWER",
        logoTop = "Nintendo",
        logoBottom = "GAME BOY",
        logoBottomSize = 16f,
        lowerLeft = "DMG-01",
        lowerRight = "PHONES",
    )
    "gbc" -> PlayerShellCopy(
        upperLeft = "COLOR SCREEN WITH STEREO SOUND",
        upperRight = "POWER",
        logoTop = "Nintendo",
        logoBottom = "GAME BOY COLOR",
        logoBottomSize = 13f,
        lowerLeft = "CGB-001",
        lowerRight = "PHONES",
    )
    "gba" -> PlayerShellCopy(
        upperLeft = "GAME BOY ADVANCE",
        upperRight = "POWER",
        logoTop = "Nintendo",
        logoBottom = "GAME BOY ADVANCE",
        logoBottomSize = 11f,
        lowerLeft = "AGB-001",
        lowerRight = "PHONES",
    )
    "snes" -> PlayerShellCopy(
        upperLeft = "",
        upperRight = "POWER",
        logoTop = "SUPER NINTENDO",
        logoBottom = "ENTERTAINMENT SYSTEM",
        logoBottomSize = 9f,
        lowerLeft = "SNES",
        lowerRight = "STEREO",
    )
    "n64" -> PlayerShellCopy(
        upperLeft = "NINTENDO 64",
        upperRight = "POWER",
        logoTop = "Nintendo",
        logoBottom = "NINTENDO 64",
        logoBottomSize = 12f,
        lowerLeft = "NUS-001",
        lowerRight = "STEREO",
    )
    "gamecube" -> PlayerShellCopy(
        upperLeft = "NINTENDO GAMECUBE",
        upperRight = "POWER",
        logoTop = "Nintendo",
        logoBottom = "GAMECUBE",
        logoBottomSize = 15f,
        lowerLeft = "DOL-001",
        lowerRight = "STEREO",
    )
    else -> PlayerShellCopy(
        upperLeft = "",
        upperRight = "POWER",
        logoTop = "",
        logoBottom = "",
        logoBottomSize = 16f,
        lowerLeft = "",
        lowerRight = "STEREO",
    )
}

internal val LibretroColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1606),
    primaryContainer = Color(0xFF5C391C),
    onPrimaryContainer = Color(0xFFFFDCC1),
    secondary = Ivory,
    onSecondary = Ink,
    secondaryContainer = PanelRaised,
    onSecondaryContainer = Ivory,
    tertiary = Amber,
    onTertiary = Ink,
    tertiaryContainer = PanelRaised,
    onTertiaryContainer = Ivory,
    background = Ink,
    onBackground = Ivory,
    surface = Panel,
    onSurface = Ivory,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextMuted,
    outline = SurfaceStroke,
)
