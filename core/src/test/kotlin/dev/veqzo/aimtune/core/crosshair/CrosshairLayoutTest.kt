package dev.veqzo.aimtune.core.crosshair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrosshairLayoutTest {
    private val bare = Crosshair(outlines = false, dot = false, inner = CrosshairLines(thickness = 2, length = 6, offset = 3, opacity = 1.0), outer = CrosshairLines(show = false))

    private fun fills(c: Crosshair, w: Int = 1920, h: Int = 1080) = CrosshairLayout.rects(c, w, h).map { it.x to it.y to (it.width to it.height) }

    @Test
    fun `inner lines have the pixel lengths, gaps and thickness of the code on a 1080p screen`() {
        val rects = CrosshairLayout.rects(bare, 1920, 1080)
        assertEquals(4, rects.size)
        val left = rects[0]
        val right = rects[1]
        val top = rects[2]
        val bottom = rects[3]
        assertEquals(listOf(951, 539, 6, 2), listOf(left.x, left.y, left.width, left.height))
        assertEquals(listOf(963, 539, 6, 2), listOf(right.x, right.y, right.width, right.height))
        assertEquals(listOf(959, 531, 2, 6), listOf(top.x, top.y, top.width, top.height))
        assertEquals(listOf(959, 543, 2, 6), listOf(bottom.x, bottom.y, bottom.width, bottom.height))
        // The gap between the arms is 2 * offset, symmetric around the exact center (960, 540) of an even-sized screen.
        assertEquals(6, right.x - left.right)
        assertEquals(960 - left.right, right.x - 960)
        assertEquals(540 - top.bottom, bottom.y - 540)
    }

    @Test
    fun `the layout does not depend on a window scale, only on the pixels of the screen`() {
        val small = CrosshairLayout.rects(bare, 1280, 720)
        assertEquals(listOf(631, 359, 6, 2), listOf(small[0].x, small[0].y, small[0].width, small[0].height))
        val big = CrosshairLayout.rects(bare, 3840, 2160)
        assertEquals(listOf(1911, 1079, 6, 2), listOf(big[0].x, big[0].y, big[0].width, big[0].height))
    }

    @Test
    fun `an odd thickness covers the center pixel, an even one is centered on the boundary`() {
        val one = bare.copy(inner = bare.inner.copy(thickness = 1))
        val v = CrosshairLayout.rects(one, 1921, 1081)[2]
        assertEquals(960, v.x, "1 px line covers the center pixel 960 of 1921")
        assertEquals(1, v.width)
        val three = bare.copy(inner = bare.inner.copy(thickness = 3))
        assertEquals(959, CrosshairLayout.rects(three, 1921, 1081)[2].x)
        val even = CrosshairLayout.rects(bare, 1921, 1081)[2]
        assertEquals(959, even.x)
    }

    @Test
    fun `outlines surround every element by their thickness, drawn before the fills, with their own opacity`() {
        val c = bare.copy(outlines = true, outlineThickness = 2, outlineOpacity = 0.5, dot = true, dotThickness = 3, dotOpacity = 1.0)
        val rects = CrosshairLayout.rects(c, 1920, 1080)
        assertEquals(10, rects.size, "5 fills and 5 outlines")
        val outlines = rects.take(5)
        val fills = rects.drop(5)
        for ((outline, fill) in outlines.zip(fills)) {
            assertEquals(fill.x - 2, outline.x)
            assertEquals(fill.y - 2, outline.y)
            assertEquals(fill.width + 4, outline.width)
            assertEquals(fill.height + 4, outline.height)
            assertEquals(0x00000080, outline.rgba)
        }
        val dot = fills[0]
        assertEquals(listOf(959, 539, 3, 3), listOf(dot.x, dot.y, dot.width, dot.height))
    }

    @Test
    fun `color and opacity end up in the alpha, hidden and zero-length lines produce nothing`() {
        val cyan = bare.copy(color = CrosshairColor(5), inner = bare.inner.copy(opacity = 0.5))
        assertEquals(0x00FFFF80, CrosshairLayout.rects(cyan, 1920, 1080).first().rgba)
        assertEquals(0, CrosshairLayout.rects(bare.copy(inner = bare.inner.copy(show = false)), 1920, 1080).size)
        assertEquals(0, CrosshairLayout.rects(bare.copy(inner = bare.inner.copy(length = 0)), 1920, 1080).size)
        val verticalOnly = CrosshairLayout.rects(bare.copy(inner = bare.inner.copy(length = 0, verticalLength = 4)), 1920, 1080)
        assertEquals(2, verticalOnly.size)
        assertTrue(verticalOnly.all { it.height == 4 && it.width == 2 })
    }

    @Test
    fun `separate vertical length and outer lines are placed outside the inner ones`() {
        val c = bare.copy(
            inner = bare.inner.copy(verticalLength = 3),
            outer = CrosshairLines(thickness = 2, length = 2, offset = 10, opacity = 1.0),
        )
        val rects = CrosshairLayout.rects(c, 1920, 1080)
        assertEquals(8, rects.size)
        val innerTop = rects[2]
        assertEquals(3, innerTop.height)
        val outerLeft = rects[4]
        assertEquals(listOf(948, 539, 2, 2), listOf(outerLeft.x, outerLeft.y, outerLeft.width, outerLeft.height))
        assertTrue(rects.all { it.x in 0 until 1920 && it.y in 0 until 1080 })
        assertEquals(fills(c).size, rects.size)
    }

    @Test
    fun `values beyond the ranges of the game are limited when drawn`() {
        val huge = bare.copy(inner = bare.inner.copy(length = 500, thickness = 99, offset = 400))
        val rect = CrosshairLayout.rects(huge, 1920, 1080)[0]
        assertEquals(20, rect.width)
        assertEquals(10, rect.height)
    }

    /** Draws the rectangles into a grid ('o' outline, 'X' fill) and returns the rows of the bounding box of everything drawn. */
    private fun picture(crosshair: Crosshair, size: Int): List<String> {
        val grid = Array(size) { CharArray(size) { '.' } }
        for (r in CrosshairLayout.rects(crosshair, size, size)) {
            for (y in r.y until r.bottom) for (x in r.x until r.right) grid[y][x] = if (r.rgba == OUTLINE) 'o' else 'X'
        }
        val drawn = grid.indices.filter { y -> grid[y].any { it != '.' } }
        val rows = drawn.first()..drawn.last()
        val cols = (0 until size).filter { x -> grid.any { it[x] != '.' } }
        return rows.map { y -> cols.first().rangeTo(cols.last()).map { grid[y][it] }.joinToString("") }
    }

    @Test
    fun `the picture of a dot with outline and of four short lines matches the reference`() {
        val dot = Crosshair(color = CrosshairColor(0), outlines = true, outlineThickness = 1, outlineOpacity = 1.0, dot = true, dotThickness = 3, inner = CrosshairLines(show = false), outer = CrosshairLines(show = false))
        assertEquals(listOf("ooooo", "oXXXo", "oXXXo", "oXXXo", "ooooo"), picture(dot, 21))
        val lines = Crosshair(
            color = CrosshairColor(0), outlines = false, dot = false,
            inner = CrosshairLines(thickness = 1, length = 2, offset = 1, opacity = 1.0), outer = CrosshairLines(show = false),
        )
        assertEquals(listOf("...X..", "...X..", "......", "XX..XX", "...X..", "...X.."), picture(lines, 21))
    }

    private companion object {
        /** Black at full opacity, the color of an outline with opacity 1. */
        const val OUTLINE = 0x000000FF
    }
}
