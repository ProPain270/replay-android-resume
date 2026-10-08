package dev.codex.libretroplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun widthBreakpointsAreInclusiveAtTheDocumentedEdges() {
        assertEquals(WindowWidthClass.Compact, adaptiveWindowInfo(599, 479).widthClass)
        assertEquals(WindowWidthClass.Medium, adaptiveWindowInfo(600, 480).widthClass)
        assertEquals(WindowWidthClass.Expanded, adaptiveWindowInfo(840, 900).widthClass)
    }

    @Test
    fun heightBreakpointsAreIndependentOfWidth() {
        assertEquals(WindowHeightClass.Compact, adaptiveWindowInfo(1_200, 479).heightClass)
        assertEquals(WindowHeightClass.Medium, adaptiveWindowInfo(1_200, 480).heightClass)
        assertEquals(WindowHeightClass.Expanded, adaptiveWindowInfo(1_200, 900).heightClass)
    }

    @Test
    fun expandedWindowsOptIntoTheRailOnlyWhenWidthIsExpanded() {
        assertTrue(adaptiveWindowInfo(840, 900).isLargeEnoughForNavigationRail)
        assertEquals(false, adaptiveWindowInfo(839, 900).isLargeEnoughForNavigationRail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWidthIsRejected() {
        adaptiveWindowInfo(-1, 500)
    }
}

