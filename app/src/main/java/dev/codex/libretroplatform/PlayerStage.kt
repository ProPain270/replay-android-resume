package dev.codex.libretroplatform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codex.libretroplatform.runtime.api.Button

/** Identical measured layout for runtime and edit preview; never scrolls. */
@Composable
internal fun PlayerStage(
    preferences: UserPreferences,
    family: InputFamily,
    systemId: String?,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    fold: FoldBounds? = null,
    onInput: ((Button, Boolean) -> Unit)? = null,
    selected: ControlId? = null,
    onSelect: ((ControlId) -> Unit)? = null,
    onMove: ((ControlId, Float, Float) -> Unit)? = null,
    onMoveStart: (() -> Unit)? = null,
    onMeasured: ((PlayerGeometry, Float, Float) -> Unit)? = null,
    video: @Composable () -> Unit = { PreviewGameFrame() },
) {
    val classic = preferences.playerPresentation == PlayerPresentation.Classic
    val skin = playerSkin(preferences.displayPreset)
    val shellShape = RoundedCornerShape(if (classic) 24.dp else 8.dp)
    BoxWithConstraints(
        modifier
            .clip(shellShape)
            .then(
                if (classic) Modifier.background(Brush.verticalGradient(listOf(skin.shellTop, skin.shellBottom)))
                else Modifier.background(Color(0xFF0D1014)),
            )
            .then(if (classic) Modifier.border(1.dp, skin.shellBorder, shellShape) else Modifier)
            .padding(if (classic) 12.dp else 4.dp),
    ) {
        val inset = if (classic) 12f else 4f
        val localFold = fold?.copy(bounds = fold.bounds.copy(x = fold.bounds.x - inset, y = fold.bounds.y - inset))
        val geometry = playerGeometry(maxWidth.value, maxHeight.value, aspectRatio, localFold)
        SideEffect { onMeasured?.invoke(geometry, maxWidth.value, maxHeight.value) }
        val fitted = geometry.viewport
        if (classic && maxHeight.value >= 260f) {
            ClassicShellChrome(
                copy = playerShellCopy(ConsoleCatalog.forSystem(systemId.orEmpty())),
                skin = skin,
                geometry = geometry,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val sizeFactor = preferences.playerDisplaySize.widthMultiplier.coerceIn(.85f, 1f)
        val v = fitted.copy(x = fitted.x + fitted.width * (1 - sizeFactor) / 2,
            y = fitted.y + fitted.height * (1 - sizeFactor) / 2,
            width = fitted.width * sizeFactor, height = fitted.height * sizeFactor)
        Box(Modifier.absoluteOffset(v.x.dp, v.y.dp).size(v.width.dp, v.height.dp)
            .testTag("player-viewport").clip(RoundedCornerShape(if (classic) 10.dp else 3.dp))
            .background(Color.Black)
            .then(if (classic) Modifier.border(3.dp, skin.screenBezel, RoundedCornerShape(10.dp)).padding(3.dp) else Modifier)) {
            video()
        }
        val d = geometry.controls
        ControlDeck(preferences, family, systemId, geometry.profile,
            Modifier.absoluteOffset(d.x.dp, d.y.dp).size(d.width.dp, d.height.dp).testTag("player-controls"),
            onInput, selected, onSelect, onMove, onMoveStart)
    }
}

@Composable
private fun ClassicShellChrome(
    copy: PlayerShellCopy,
    skin: PlayerSkin,
    geometry: PlayerGeometry,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (geometry.viewport.y >= 28f && copy.upperLeft.isNotBlank()) {
            Text(
                copy.upperLeft,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 4.dp),
                color = skin.hardwareInk.copy(alpha = .72f),
                fontSize = 7.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .7.sp,
                maxLines = 1,
            )
        }
        if (geometry.viewport.y >= 28f && copy.upperRight.isNotBlank()) {
            Text(
                copy.upperRight,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 4.dp),
                color = skin.hardwareInk.copy(alpha = .72f),
                fontSize = 7.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .7.sp,
                maxLines = 1,
            )
        }
        if (geometry.profile == ControlProfile.Portrait) Row(
            modifier = Modifier
                .absoluteOffset(geometry.viewport.x.dp, (geometry.viewport.bottom + 2f).dp)
                .width(geometry.viewport.width.dp)
                .height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (copy.logoTop.isNotBlank()) {
                Text(copy.logoTop, color = skin.logoInk, fontSize = 6.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (copy.logoBottom.isNotBlank()) {
                Text(copy.logoBottom, color = skin.logoInk, fontSize = copy.logoBottomSize.sp, lineHeight = copy.logoBottomSize.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
        }
        Row(
            modifier = Modifier
                .absoluteOffset(geometry.controls.x.dp, (geometry.controls.bottom - 17f).dp)
                .width(geometry.controls.width.dp)
                .height(12.dp)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (copy.lowerLeft.isNotBlank()) {
                Text(copy.lowerLeft, color = skin.hardwareInk.copy(alpha = .66f), fontSize = 6.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            } else {
                Spacer(Modifier.width(1.dp))
            }
            if (copy.lowerRight.isNotBlank()) {
                Text(copy.lowerRight, color = skin.hardwareInk.copy(alpha = .66f), fontSize = 6.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        if (geometry.viewport.y >= 28f) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 14.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(skin.power),
        )
    }
}

@Composable
internal fun PreviewGameFrame() {
    Box(Modifier.fillMaxSize().background(Color(0xFFB7C69B)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / 20
            for (x in 0..20) drawLine(Color.Black.copy(alpha = .04f), Offset(x * step, 0f), Offset(x * step, size.height))
            for (y in 0..24) drawLine(Color.Black.copy(alpha = .04f), Offset(0f, y * step), Offset(size.width, y * step))
        }
        Text("PREVIEW", color = Color(0xFF42543A), fontSize = 12.sp)
    }
}
