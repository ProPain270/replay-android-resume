package dev.codex.libretroplatform

/**
 * Window classes are based on the window allocated to the app, not the
 * physical model. This keeps foldables, tablets, desktop windows, and split
 * screen on the same layout contract.
 */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded,
}

enum class WindowHeightClass {
    Compact,
    Medium,
    Expanded,
}

data class AdaptiveWindowInfo(
    val widthDp: Int,
    val heightDp: Int,
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass,
) {
    val isLargeEnoughForNavigationRail: Boolean
        get() = widthClass == WindowWidthClass.Expanded
}

fun adaptiveWindowInfo(widthDp: Int, heightDp: Int): AdaptiveWindowInfo {
    require(widthDp >= 0) { "window width cannot be negative" }
    require(heightDp >= 0) { "window height cannot be negative" }
    return AdaptiveWindowInfo(
        widthDp = widthDp,
        heightDp = heightDp,
        widthClass = when {
            widthDp >= 840 -> WindowWidthClass.Expanded
            widthDp >= 600 -> WindowWidthClass.Medium
            else -> WindowWidthClass.Compact
        },
        heightClass = when {
            heightDp >= 900 -> WindowHeightClass.Expanded
            heightDp >= 480 -> WindowHeightClass.Medium
            else -> WindowHeightClass.Compact
        },
    )
}

