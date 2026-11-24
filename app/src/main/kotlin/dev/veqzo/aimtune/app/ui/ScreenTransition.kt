package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.Disposable

private const val RECEDE_SCALE = 0.985f

/**
 * The screen change of the Rack shell: the old screen, frozen as a snapshot, fades out over the new
 * one and recedes slightly, so the eye sees which surface replaced which. The new screen is live
 * from the first frame, so input never waits for the motion.
 */
class ScreenTransition(private val duration: Float) : Disposable {
    private val batch = SpriteBatch()
    private var snapshot: FrameBuffer? = null
    private var elapsed = 0f

    /** Starts a transition from [frozen]; this object owns the frame buffer from now on. */
    fun start(frozen: FrameBuffer) {
        snapshot?.dispose()
        snapshot = frozen
        elapsed = 0f
    }

    /** Draws the fading snapshot over the frame that was just rendered; call after the screen's render. */
    fun drawOver(delta: Float) {
        val fbo = snapshot ?: return
        elapsed += delta
        val progress = (elapsed / duration).coerceIn(0f, 1f)
        if (progress >= 1f) {
            finish()
            return
        }
        val eased = Motion.enter.apply(progress)
        val width = Gdx.graphics.backBufferWidth.toFloat()
        val height = Gdx.graphics.backBufferHeight.toFloat()
        val scale = 1f - (1f - RECEDE_SCALE) * eased
        val w = width * scale
        val h = height * scale
        Gdx.gl.glViewport(0, 0, width.toInt(), height.toInt())
        batch.projectionMatrix.setToOrtho2D(0f, 0f, width, height)
        batch.begin()
        batch.setColor(1f, 1f, 1f, 1f - eased)
        // Frame buffer textures are stored bottom-up: flip on the y axis.
        batch.draw(fbo.colorBufferTexture, (width - w) / 2, (height - h) / 2, w, h, 0, 0, fbo.width, fbo.height, false, true)
        batch.end()
    }

    private fun finish() {
        snapshot?.dispose()
        snapshot = null
    }

    override fun dispose() {
        finish()
        batch.dispose()
    }
}
