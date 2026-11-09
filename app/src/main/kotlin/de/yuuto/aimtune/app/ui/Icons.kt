package de.yuuto.aimtune.app.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The line icons of the Rack design, drawn on a 24 × 24 grid with y pointing down (like SVG). Each
 * icon is a set of strokes of equal width; [IconRaster] turns them into white coverage that the UI
 * tints. Keep icons to a few strokes: they sit next to text at 16-28 units.
 */
enum class Icon(val strokes: List<Stroke>) {
    PLAY(listOf(Stroke.Poly(listOf(8f to 5f, 19f to 12f, 8f to 19f), closed = true))),
    PAUSE(listOf(Stroke.Line(9f, 5f, 9f, 19f), Stroke.Line(15f, 5f, 15f, 19f))),
    STOP(listOf(Stroke.Poly(listOf(6f to 6f, 18f to 6f, 18f to 18f, 6f to 18f), closed = true))),
    CHECK(listOf(Stroke.Poly(listOf(5f to 12.5f, 10f to 17.5f, 19f to 7f)))),
    CLOSE(listOf(Stroke.Line(6f, 6f, 18f, 18f), Stroke.Line(18f, 6f, 6f, 18f))),
    CHEVRON_DOWN(listOf(Stroke.Poly(listOf(6f to 9.5f, 12f to 15.5f, 18f to 9.5f)))),
    CHEVRON_RIGHT(listOf(Stroke.Poly(listOf(9.5f to 6f, 15.5f to 12f, 9.5f to 18f)))),
    CHEVRON_LEFT(listOf(Stroke.Poly(listOf(14.5f to 6f, 8.5f to 12f, 14.5f to 18f)))),
    INFO(listOf(Stroke.Arc(12f, 12f, 9f, 0f, 360f), Stroke.Line(12f, 11f, 12f, 16.5f), Stroke.Line(12f, 7.6f, 12f, 7.8f))),
    WARN(
        listOf(
            Stroke.Poly(listOf(12f to 4f, 21f to 19.5f, 3f to 19.5f), closed = true),
            Stroke.Line(12f, 10f, 12f, 14f),
            Stroke.Line(12f, 16.8f, 12f, 17f),
        ),
    ),

    /** Turn the module around: two arcs with arrow heads. */
    FLIP(
        listOf(
            Stroke.Arc(12f, 12f, 7.5f, 200f, 340f),
            Stroke.Poly(listOf(19.5f to 6.5f, 19.4f to 10.6f, 15.4f to 10.2f)),
            Stroke.Arc(12f, 12f, 7.5f, 20f, 160f),
            Stroke.Poly(listOf(4.5f to 17.5f, 4.6f to 13.4f, 8.6f to 13.8f)),
        ),
    ),
    HISTORY(listOf(Stroke.Arc(12f, 12f, 8.5f, 0f, 360f), Stroke.Poly(listOf(12f to 7.5f, 12f to 12f, 15.5f to 14f)))),
    CROSSHAIR(
        listOf(
            Stroke.Arc(12f, 12f, 7f, 0f, 360f),
            Stroke.Line(12f, 2.5f, 12f, 7.5f),
            Stroke.Line(12f, 16.5f, 12f, 21.5f),
            Stroke.Line(2.5f, 12f, 7.5f, 12f),
            Stroke.Line(16.5f, 12f, 21.5f, 12f),
        ),
    ),

    /** Settings: three sliders, an instrument's trim controls rather than a cog. */
    SLIDERS(
        listOf(
            Stroke.Line(4f, 7f, 20f, 7f), Stroke.Line(4f, 12f, 20f, 12f), Stroke.Line(4f, 17f, 20f, 17f),
            Stroke.Line(9f, 4.5f, 9f, 9.5f), Stroke.Line(15f, 9.5f, 15f, 14.5f), Stroke.Line(7f, 14.5f, 7f, 19.5f),
        ),
    ),
    SOUND(
        listOf(
            Stroke.Poly(listOf(4f to 9.5f, 8f to 9.5f, 12.5f to 5.5f, 12.5f to 18.5f, 8f to 14.5f, 4f to 14.5f), closed = true),
            Stroke.Arc(13f, 12f, 4f, -50f, 50f),
            Stroke.Arc(13f, 12f, 7.5f, -50f, 50f),
        ),
    ),
    SHARE(
        listOf(
            Stroke.Line(12f, 4f, 12f, 15f),
            Stroke.Poly(listOf(7.5f to 8.5f, 12f to 4f, 16.5f to 8.5f)),
            Stroke.Poly(listOf(5f to 13f, 5f to 20f, 19f to 20f, 19f to 13f)),
        ),
    ),
    DOWNLOAD(
        listOf(
            Stroke.Line(12f, 4f, 12f, 15f),
            Stroke.Poly(listOf(7.5f to 10.5f, 12f to 15f, 16.5f to 10.5f)),
            Stroke.Line(5f, 20f, 19f, 20f),
        ),
    ),

    /** A gain curve over a baseline: the curve module and Raw Accel. */
    CURVE(listOf(Stroke.Poly(listOf(3f to 19f, 21f to 19f)), Stroke.Poly(listOf(4f to 15f, 9f to 14.5f, 13f to 12f, 16.5f to 8f, 20f to 6f)))),

    /** A target ring with a dot: the sensitivity module. */
    TARGET(listOf(Stroke.Arc(12f, 12f, 8.5f, 0f, 360f), Stroke.Arc(12f, 12f, 4f, 0f, 360f), Stroke.Line(12f, 11.9f, 12f, 12.1f))),
    ;

    sealed interface Stroke {
        /** Squared distance is not enough here: AA needs the real distance in grid units. */
        fun distance(x: Float, y: Float): Float

        data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Stroke {
            override fun distance(x: Float, y: Float): Float = segmentDistance(x, y, x0, y0, x1, y1)
        }

        data class Poly(val points: List<Pair<Float, Float>>, val closed: Boolean = false) : Stroke {
            override fun distance(x: Float, y: Float): Float {
                val ends = if (closed) points + points.first() else points
                return ends.zipWithNext { a, b -> segmentDistance(x, y, a.first, a.second, b.first, b.second) }.min()
            }
        }

        /** Arc around ([cx], [cy]) from [startDeg] to [endDeg], clockwise on screen (y down), 0° pointing right. */
        data class Arc(val cx: Float, val cy: Float, val r: Float, val startDeg: Float, val endDeg: Float) : Stroke {
            override fun distance(x: Float, y: Float): Float {
                val dx = x - cx
                val dy = y - cy
                if (endDeg - startDeg >= FULL_TURN) return abs(hypot(dx, dy) - r)
                val angle = normalize(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                val start = normalize(startDeg)
                val sweep = endDeg - startDeg
                if (normalize(angle - start) <= sweep) return abs(hypot(dx, dy) - r)
                return minOf(endDistance(x, y, startDeg), endDistance(x, y, endDeg))
            }

            private fun endDistance(x: Float, y: Float, deg: Float): Float {
                val rad = deg * PI.toFloat() / HALF_TURN
                return hypot(x - (cx + r * cos(rad)), y - (cy + r * sin(rad)))
            }
        }
    }

    companion object {
        const val GRID = 24f
        const val STROKE = 2f
        private const val FULL_TURN = 360f
        private const val HALF_TURN = 180f

        private fun normalize(deg: Float): Float = ((deg % FULL_TURN) + FULL_TURN) % FULL_TURN

        private fun segmentDistance(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val vx = x1 - x0
            val vy = y1 - y0
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0f) 0f else (((px - x0) * vx + (py - y0) * vy) / len2).coerceIn(0f, 1f)
            return hypot(px - (x0 + t * vx), py - (y0 + t * vy))
        }
    }
}

/** Rasterizes an [Icon] into coverage values (0..1) for a square of [size] pixels, row 0 at the top. */
object IconRaster {
    fun coverage(icon: Icon, size: Int, strokeWidth: Float = Icon.STROKE): FloatArray {
        val unit = Icon.GRID / size
        val half = strokeWidth / 2f
        val out = FloatArray(size * size)
        for (row in 0 until size) {
            for (col in 0 until size) {
                val gx = (col + 0.5f) * unit
                val gy = (row + 0.5f) * unit
                val d = icon.strokes.minOf { it.distance(gx, gy) }
                // One pixel of anti-aliasing, measured in grid units.
                out[row * size + col] = ((half - d) / unit + 0.5f).coerceIn(0f, 1f)
            }
        }
        return out
    }
}
