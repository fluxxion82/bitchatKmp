package com.bitchat.design.globe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scroll is the only continuous zoom a mouse can perform, so getting its direction wrong makes
 * zooming in a one-way trip -- which is the defect this was added to fix.
 */
class ScrollZoomTest {

    @Test
    fun `pushing the wheel forward zooms in`() {
        // Negative delta is forward on every platform Compose reports.
        assertTrue(GlobeMath.zoomFactorForScroll(-1f) > 1f)
    }

    @Test
    fun `pulling the wheel back zooms out`() {
        assertTrue(GlobeMath.zoomFactorForScroll(1f) < 1f)
    }

    @Test
    fun `no scroll leaves the zoom alone`() {
        assertEquals(1f, GlobeMath.zoomFactorForScroll(0f))
    }

    @Test
    fun `scrolling out undoes scrolling in`() {
        // A constant ratio per notch is what makes the gesture reversible; a constant addend would
        // not return you to where you started.
        val inThenOut = GlobeMath.zoomFactorForScroll(-3f) * GlobeMath.zoomFactorForScroll(3f)

        assertTrue(kotlin.math.abs(inThenOut - 1f) < 1e-5, "not reversible: $inThenOut")
    }

    @Test
    fun `a bigger scroll zooms further`() {
        assertTrue(GlobeMath.zoomFactorForScroll(-5f) > GlobeMath.zoomFactorForScroll(-1f))
    }
}
