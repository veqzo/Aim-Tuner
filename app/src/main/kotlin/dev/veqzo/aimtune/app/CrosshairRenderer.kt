package dev.veqzo.aimtune.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import dev.veqzo.aimtune.core.crosshair.Crosshair
import dev.veqzo.aimtune.core.crosshair.CrosshairLayout
import dev.veqzo.aimtune.core.crosshair.PixelRect

/**
 * Draws crosshair rectangles in screen pixels: one unit of the projection is one pixel of the back buffer, so a
 * 2 px line is 2 px whatever the window size, the Windows display scaling or the UI scale (docs/CROSSHAIR.md).
 * The rectangles come from [CrosshairLayout] with the origin at the top left, drawn without anti-aliasing at
 * integer positions.
 */
class CrosshairRenderer : Disposable {
    private val shapes = ShapeRenderer()
    private val camera = OrthographicCamera()
    private val color = Color()

    /** Draws [crosshair] centered on the whole back buffer (the window), the way the game draws it on the screen. */
    fun drawOnScreen(crosshair: Crosshair) {
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        if (width <= 0 || height <= 0) return
        begin(width, height)
        drawRects(CrosshairLayout.rects(crosshair, width, height), 0, 0, height)
        end()
    }

    /** Starts drawing in a pixel space of [width] x [height] (origin bottom left, like GL). */
    fun begin(width: Int, height: Int) {
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        camera.update()
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
    }

    /**
     * Fills [rects], whose top-left origin is at pixel ([left], [top] counted from the top) of a space [spaceHeight]
     * pixels high; call between [begin] and [end].
     */
    fun drawRects(rects: List<PixelRect>, left: Int, top: Int, spaceHeight: Int) {
        for (r in rects) {
            Color.rgba8888ToColor(color, r.rgba)
            shapes.color = color
            shapes.rect((left + r.x).toFloat(), (spaceHeight - top - r.bottom).toFloat(), r.width.toFloat(), r.height.toFloat())
        }
    }

    fun end() {
        shapes.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dispose() = shapes.dispose()
}
