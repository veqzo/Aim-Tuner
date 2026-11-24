package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.GdxNativesLoader
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawablesTest {
    @BeforeTest
    fun natives() = GdxNativesLoader.load()

    private fun alphaAt(pixmap: com.badlogic.gdx.graphics.Pixmap, x: Int, y: Int) = Color(pixmap.getPixel(x, y)).a
    private fun colorAt(pixmap: com.badlogic.gdx.graphics.Pixmap, x: Int, y: Int) = Color(pixmap.getPixel(x, y))

    @Test
    fun `fill covers the middle and the corner is transparent`() {
        val fill = Color(0.2f, 0.4f, 0.6f, 1f)
        val p = DrawableFactory.shapePixmap(size = 40, radius = 12f, fill = fill, border = null, borderWidth = 0f)
        val middle = colorAt(p, 20, 20)
        assertEquals(1f, middle.a, 0.01f)
        assertEquals(0.4f, middle.g, 0.01f)
        assertEquals(0f, alphaAt(p, 0, 0), 0.01f)
        assertEquals(0f, alphaAt(p, 39, 39), 0.01f)
        p.dispose()
    }

    @Test
    fun `straight edges are opaque right up to the border`() {
        val p = DrawableFactory.shapePixmap(size = 40, radius = 12f, fill = Color.WHITE, border = null, borderWidth = 0f)
        assertEquals(1f, alphaAt(p, 20, 0), 0.01f)
        assertEquals(1f, alphaAt(p, 0, 20), 0.01f)
        p.dispose()
    }

    @Test
    fun `border draws a ring around a transparent or filled inside`() {
        val border = Color(0.9f, 0.3f, 0.1f, 1f)
        val outlined = DrawableFactory.shapePixmap(size = 40, radius = 10f, fill = null, border = border, borderWidth = 3f)
        assertEquals(0.9f, colorAt(outlined, 20, 1).r, 0.02f)
        assertEquals(1f, alphaAt(outlined, 20, 1), 0.02f)
        assertEquals(0f, alphaAt(outlined, 20, 20), 0.01f)
        outlined.dispose()

        val filled = DrawableFactory.shapePixmap(size = 40, radius = 10f, fill = Color.BLUE, border = border, borderWidth = 3f)
        assertEquals(1f, colorAt(filled, 20, 20).b, 0.02f)
        assertEquals(0.9f, colorAt(filled, 20, 1).r, 0.02f)
        filled.dispose()
    }

    @Test
    fun `edge pixels are anti aliased`() {
        val p = DrawableFactory.shapePixmap(size = 40, radius = 20f, fill = Color.WHITE, border = null, borderWidth = 0f)
        val partial = (0 until 40).flatMap { x -> (0 until 40).map { y -> alphaAt(p, x, y) } }.count { it > 0.05f && it < 0.95f }
        assertTrue(partial > 10, "partially covered pixels: $partial")
        p.dispose()
    }
}
