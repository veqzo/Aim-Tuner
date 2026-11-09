package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable
import kotlin.math.hypot
import kotlin.math.min

private const val VIGNETTE_SIZE = 256
private const val VIGNETTE_INNER = 0.35f
private const val VIGNETTE_MAX_ALPHA = 0.85f

/**
 * The trainer room as it stood when the last round ended, frozen in a frame buffer: the round overlay
 * draws it dimmed and vignetted behind its card, so the player stays in the room between rounds. A
 * new capture replaces the old one; [clear] drops it when the run is left.
 */
class RoundBackdrop : Disposable {
    private val batch = SpriteBatch()
    private val vignette = createVignette()
    private var frame: FrameBuffer? = null

    val present: Boolean get() = frame != null

    /** Renders [draw] (the 3D scene, no HUD) into a new frame buffer of the window's size. */
    fun capture(draw: () -> Unit) {
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        if (width == 0 || height == 0) return
        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, true)
        fbo.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        draw()
        fbo.end()
        frame?.dispose()
        frame = fbo
    }

    /** Draws the frozen room darkened by [dim] (0 = as captured, 1 = black) with the vignette on top; nothing without a capture. */
    fun draw(dim: Float) {
        val fbo = frame ?: return
        val width = Gdx.graphics.backBufferWidth.toFloat()
        val height = Gdx.graphics.backBufferHeight.toFloat()
        Gdx.gl.glViewport(0, 0, width.toInt(), height.toInt())
        batch.projectionMatrix.setToOrtho2D(0f, 0f, width, height)
        batch.begin()
        val light = 1f - dim.coerceIn(0f, 1f)
        batch.setColor(light, light, light, 1f)
        // Frame buffer textures are stored bottom-up: flip on the y axis.
        batch.draw(fbo.colorBufferTexture, 0f, 0f, width, height, 0, 0, fbo.width, fbo.height, false, true)
        batch.setColor(1f, 1f, 1f, 1f)
        batch.draw(vignette, 0f, 0f, width, height)
        batch.end()
    }

    fun clear() {
        frame?.dispose()
        frame = null
    }

    override fun dispose() {
        clear()
        vignette.dispose()
        batch.dispose()
    }

    /** Black, transparent in the middle and darker towards the corners; stretched over the window. */
    private fun createVignette(): Texture {
        val pixmap = Pixmap(VIGNETTE_SIZE, VIGNETTE_SIZE, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        val centre = (VIGNETTE_SIZE - 1) / 2f
        val maxDistance = hypot(centre, centre)
        for (y in 0 until VIGNETTE_SIZE) {
            for (x in 0 until VIGNETTE_SIZE) {
                val distance = hypot(x - centre, y - centre) / maxDistance
                val t = ((distance - VIGNETTE_INNER) / (1f - VIGNETTE_INNER)).coerceIn(0f, 1f)
                val alpha = min(1f, t * t) * VIGNETTE_MAX_ALPHA
                // RGBA8888: black with the alpha in the lowest byte.
                pixmap.drawPixel(x, y, (alpha * 255f).toInt().coerceIn(0, 255))
            }
        }
        return Texture(pixmap).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pixmap.dispose()
        }
    }
}
