package dev.veqzo.aimtune.core.crosshair

import kotlin.math.roundToInt

/** A filled rectangle in screen pixels with the origin at the top left of the screen; [rgba] is 0xRRGGBBAA. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int, val rgba: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * Where the parts of a crosshair are on a screen of [screenWidth] x [screenHeight] pixels: first all outlines (black,
 * drawn behind), then the lines and the dot. Sizes are native screen pixels; nothing scales with the window or
 * the Windows display scaling, the way Valorant's crosshair does not (docs/CROSSHAIR.md).
 *
 * The center is the pixel boundary at (width / 2, height / 2): on an even-sized screen that is the exact center, on an
 * odd-sized one it is the left / top edge of the center pixel. An element of thickness t starts at center - t / 2
 * (integer division), so an odd thickness covers the center pixel, an even one is centered on the boundary. The
 * lines run from `center -+ offset` outward by their length; the outline surrounds every element by its thickness.
 * Movement and firing error are not drawn: the trainer has no spread.
 */
object CrosshairLayout {
    fun rects(crosshair: Crosshair, screenWidth: Int, screenHeight: Int): List<PixelRect> {
        require(screenWidth > 0 && screenHeight > 0) { "screen must be at least 1 x 1 pixel" }
        val c = crosshair.clamped()
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        val fills = mutableListOf<PixelRect>()
        if (c.dot) {
            val t = c.dotThickness
            fills += PixelRect(cx - t / 2, cy - t / 2, t, t, withOpacity(c.color.rgba(), c.dotOpacity))
        }
        for (lines in listOf(c.inner, c.outer)) {
            if (!lines.show || lines.length == 0 && lines.effectiveVerticalLength == 0) continue
            fills += lineRects(lines, cx, cy, withOpacity(c.color.rgba(), lines.opacity))
        }
        val outlines = if (c.outlines) {
            fills.map { PixelRect(it.x - c.outlineThickness, it.y - c.outlineThickness, it.width + 2 * c.outlineThickness, it.height + 2 * c.outlineThickness, withOpacity(BLACK, c.outlineOpacity)) }
        } else {
            emptyList()
        }
        return outlines + fills
    }

    private fun lineRects(lines: CrosshairLines, cx: Int, cy: Int, rgba: Int): List<PixelRect> {
        val t = lines.thickness
        val h = lines.length
        val v = lines.effectiveVerticalLength
        val o = lines.offset
        val out = mutableListOf<PixelRect>()
        if (h > 0) {
            out += PixelRect(cx - o - h, cy - t / 2, h, t, rgba)
            out += PixelRect(cx + o, cy - t / 2, h, t, rgba)
        }
        if (v > 0) {
            out += PixelRect(cx - t / 2, cy - o - v, t, v, rgba)
            out += PixelRect(cx - t / 2, cy + o, t, v, rgba)
        }
        return out
    }

    /** [rgba] with its alpha multiplied by [opacity]. */
    fun withOpacity(rgba: Int, opacity: Double): Int {
        val alpha = ((rgba and 0xFF) * opacity.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, 255)
        return (rgba and 0xFF.inv()) or alpha
    }

    private const val BLACK = 0x000000FF
}
