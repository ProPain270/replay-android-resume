package dev.codex.libretroplatform

import kotlin.math.min

internal fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

enum class ControlProfile { Portrait, Landscape, Tabletop, Book }
enum class ControlId { Dpad, A, B, X, Y, Select, Start, L, R, L2, R2 }

data class ControlPlacement(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
) {
    fun sanitized() = copy(
        x = x.finiteOrZero().coerceIn(-.45f, .45f),
        y = y.finiteOrZero().coerceIn(-.45f, .45f),
        scale = (if (scale.isFinite()) scale else 1f).coerceIn(.7f, 1.3f),
    )
}

internal fun controlKey(profile: ControlProfile, id: ControlId) = "${profile.name}.${id.name}"
internal fun validControlKey(key: String): Boolean = ControlProfile.values().any { profile ->
    ControlId.values().any { key == controlKey(profile, it) }
}

data class DeckRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    fun contains(px: Float, py: Float) = px >= x && px <= right && py >= y && py <= bottom
    fun overlaps(other: DeckRect): Boolean = x < other.right && right > other.x && y < other.bottom && bottom > other.y
}

data class FoldBounds(val bounds: DeckRect, val horizontal: Boolean, val separating: Boolean)
data class PlayerGeometry(val viewport: DeckRect, val controls: DeckRect, val profile: ControlProfile)

/** All dimensions are local dp. One calculation drives the live player and the editor. */
fun playerGeometry(width: Float, height: Float, aspect: Float, fold: FoldBounds? = null): PlayerGeometry {
    val w = width.finiteOrZero().coerceAtLeast(1f)
    val h = height.finiteOrZero().coerceAtLeast(1f)
    val ratio = if (aspect.isFinite() && aspect > 0f) aspect else 4f / 3f
    fun fitted(area: DeckRect): DeckRect {
        val vw = min(area.width.coerceAtLeast(1f), area.height.coerceAtLeast(1f) * ratio)
        val vh = vw / ratio
        return DeckRect(area.x + (area.width - vw) / 2, area.y + (area.height - vh) / 2, vw, vh)
    }
    if (fold?.separating == true) {
        val b = fold.bounds
        if (fold.horizontal && b.y >= 80f && h - b.bottom >= 160f) {
            return PlayerGeometry(fitted(DeckRect(0f, 0f, w, b.y - 8f)),
                DeckRect(0f, b.bottom + 8f, w, h - b.bottom - 8f), ControlProfile.Tabletop)
        }
        if (!fold.horizontal && b.x >= 140f && w - b.right >= 180f) {
            return PlayerGeometry(fitted(DeckRect(0f, 0f, b.x - 8f, h)),
                DeckRect(b.right + 8f, 0f, w - b.right - 8f, h), ControlProfile.Book)
        }
    }
    if (w > h * 1.2f && w >= 480f) {
        val wing = (w * .20f).coerceIn(116f, 170f)
        return PlayerGeometry(fitted(DeckRect(wing, 0f, w - wing * 2, h)),
            DeckRect(0f, 0f, w, h), ControlProfile.Landscape)
    }
    val deckH = (h * .34f).coerceIn(min(192f, h * .48f), min(290f, h * .55f))
    val video = fitted(DeckRect(0f, 0f, w, (h - deckH - 8f).coerceAtLeast(1f)))
    // Keep game and controls together instead of stretching empty shell space.
    val top = ((h - video.height - deckH - 8f) / 2).coerceAtLeast(0f)
    return PlayerGeometry(video.copy(y = top), DeckRect(0f, top + video.height + 8f, w, deckH), ControlProfile.Portrait)
}

data class ControlNode(val id: ControlId, val bounds: DeckRect)

/** Includes collision rejection: invalid moves retain the last safe placement in the editor. */
fun controlNodes(
    width: Float, height: Float, family: InputFamily, systemId: String?,
    preferences: UserPreferences, profile: ControlProfile,
): List<ControlNode> {
    val fullWidth = width.finiteOrZero().coerceAtLeast(1f)
    val deckHeight = height.finiteOrZero().coerceAtLeast(1f)
    val side = profile == ControlProfile.Landscape
    val oneHanded = preferences.touchReachMode != TouchReachMode.Full
    val w = if (!oneHanded) fullWidth else if (side) (fullWidth * .20f).coerceIn(116f, 170f).coerceAtMost(fullWidth)
        else (fullWidth * .72f).coerceAtLeast(min(260f, fullWidth))
    val originX = if (preferences.touchReachMode == TouchReachMode.OneHandedRight) fullWidth - w else 0f
    // A narrow book pane needs a vertical controller, not a miniature two-hand
    // deck. Keep the controls together even when the pane is unusually tall.
    val stacked = (profile == ControlProfile.Book && w < 320f) || (side && oneHanded)
    val h = if (stacked) min(deckHeight, 420f) else deckHeight
    val originY = (deckHeight - h) / 2f
    val base = min(if (side) 48f else 54f, min(w / (if (stacked) 4.2f else if (side) 13f else 7.2f), h / (if (stacked) 8f else 4.1f))).coerceAtLeast(18f)
    val scale = preferences.touchLayoutSize.multiplier
    val layout = preferences.touchLayout.sanitized()
    val centerY = if (stacked) .30f else if (side) .5f else .49f
    val faceY = if (stacked) .65f else centerY
    val left = if (stacked) .5f else if (side) .10f else .23f
    val right = if (stacked) .5f else 1f - left
    fun rect(id: ControlId, x: Float, y: Float, bw: Float, bh: Float): ControlNode {
        val saved = layout.controls[controlKey(profile, id)]?.sanitized() ?: ControlPlacement()
        val legacyX = if (id == ControlId.Dpad) layout.dpadX else if (id in listOf(ControlId.A, ControlId.B, ControlId.X, ControlId.Y)) layout.faceX else 0f
        val legacyY = if (id == ControlId.Dpad) layout.dpadY else if (id in listOf(ControlId.A, ControlId.B, ControlId.X, ControlId.Y)) layout.faceY else 0f
        val rw = (bw * scale * saved.scale).coerceAtMost(w)
        val rh = (bh * scale * saved.scale).coerceAtMost(deckHeight)
        val mirrored = if (preferences.leftHanded) 1f - x else x
        val px = originX + (mirrored * w + (saved.x + legacyX) * fullWidth - rw / 2).coerceIn(0f, w - rw)
        val py = (originY + y * h + (saved.y + legacyY) * deckHeight - rh / 2).coerceIn(0f, deckHeight - rh)
        return ControlNode(id, DeckRect(px, py, rw, rh))
    }
    val result = mutableListOf(rect(ControlId.Dpad, left, centerY, base * 2.5f, base * 2.5f))
    if (family == InputFamily.Handheld) {
        val dx = base * scale * .57f / w
        result += rect(ControlId.B, right - dx, faceY + base * scale * .23f / h, base, base)
        result += rect(ControlId.A, right + dx, faceY - base * scale * .23f / h, base, base)
    } else {
        val deltaX = base * scale * .78f / w
        val deltaY = base * scale * .78f / h
        result += rect(ControlId.X, right, faceY - deltaY, base, base)
        result += rect(ControlId.Y, right - deltaX, faceY, base, base)
        result += rect(ControlId.A, right + deltaX, faceY, base, base)
        result += rect(ControlId.B, right, faceY + deltaY, base, base)
    }
    // Select / Start stay reachable at the bottom edge, away from the game.
    result += rect(ControlId.Select, if (stacked) .24f else if (side) left else .40f, .89f, maxOf(52f, base * 1.15f), maxOf(28f, base * .58f))
    result += rect(ControlId.Start, if (stacked) .76f else if (side) right else .60f, .89f, maxOf(52f, base * 1.15f), maxOf(28f, base * .58f))
    if (family != InputFamily.Handheld || systemId == "gba") {
        result += rect(ControlId.L, if (stacked) .25f else left, .09f, base * 1.6f, base * .60f)
        result += rect(ControlId.R, if (stacked) .75f else right, .09f, base * 1.6f, base * .60f)
    }
    return result
}

fun safeControlPlacement(nodes: List<ControlNode>, id: ControlId, forbidden: DeckRect? = null): Boolean {
    val selected = nodes.firstOrNull { it.id == id } ?: return false
    if (forbidden?.overlaps(selected.bounds) == true) return false
    // SNES diamond circles have overlapping bounding boxes by design; compare
    // their central hit regions so diagonal neighbors can retain native geometry.
    fun core(r: DeckRect): DeckRect = r.copy(x = r.x + r.width * .15f, y = r.y + r.height * .15f, width = r.width * .7f, height = r.height * .7f)
    return nodes.filter { it.id != id }.none { core(selected.bounds).overlaps(core(it.bounds)) }
}
