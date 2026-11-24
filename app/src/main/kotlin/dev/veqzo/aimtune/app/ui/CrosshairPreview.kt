package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.utils.Disposable
import dev.veqzo.aimtune.app.CrosshairRenderer
import dev.veqzo.aimtune.app.Theme
import dev.veqzo.aimtune.core.crosshair.Crosshair
import dev.veqzo.aimtune.core.crosshair.CrosshairLayout
import dev.veqzo.aimtune.core.crosshair.PixelRect
import kotlin.math.roundToInt

enum class PreviewBackground { LIGHT, DARK, TRAINER }

/**
 * A live preview of a crosshair on a background. Unlike the rest of the UI it is drawn in screen pixels, not design
 * units, so what the player sees is the size the trainer draws and the game draws at this resolution: the box of the
 * actor is converted to back buffer pixels, and the crosshair is centered in it ([CrosshairLayout] with the box as the screen).
 */
class CrosshairPreview(private val crosshair: () -> Crosshair, private val background: () -> PreviewBackground) : Actor(), Disposable {
    private val renderer = CrosshairRenderer()
    private val corner = Vector2()
    private val other = Vector2()

    override fun draw(batch: Batch, parentAlpha: Float) {
        val stage = stage ?: return
        corner.set(0f, 0f)
        other.set(width, height)
        localToStageCoordinates(corner)
        localToStageCoordinates(other)
        stage.stageToScreenCoordinates(corner)
        stage.stageToScreenCoordinates(other)
        val scale = Gdx.graphics.backBufferWidth.toFloat() / Gdx.graphics.width
        val left = (minOf(corner.x, other.x) * scale).roundToInt()
        val top = (minOf(corner.y, other.y) * scale).roundToInt()
        val right = (maxOf(corner.x, other.x) * scale).roundToInt()
        val bottom = (maxOf(corner.y, other.y) * scale).roundToInt()
        val boxWidth = right - left
        val boxHeight = bottom - top
        if (boxWidth < 1 || boxHeight < 1) return

        batch.end()
        val bbWidth = Gdx.graphics.backBufferWidth
        val bbHeight = Gdx.graphics.backBufferHeight
        renderer.begin(bbWidth, bbHeight)
        renderer.drawRects(backgroundRects(background(), boxWidth, boxHeight), left, top, bbHeight)
        renderer.drawRects(CrosshairLayout.rects(crosshair(), boxWidth, boxHeight), left, top, bbHeight)
        renderer.end()
        // The batch of the stage expects its own blending state back.
        Gdx.gl.glEnable(GL20.GL_BLEND)
        batch.begin()
    }

    override fun dispose() = renderer.dispose()

    companion object {
        private val LIGHT = rgba(Color.valueOf("d9dce3"))
        private val DARK = rgba(Color.valueOf("0f1016"))

        private fun rgba(color: Color): Int = Color.rgba8888(color)

        /** The background of a [width] x [height] box: a plain light or dark surface, or wall, floor and a head-sized dummy like the trainer's. */
        fun backgroundRects(kind: PreviewBackground, width: Int, height: Int): List<PixelRect> = when (kind) {
            PreviewBackground.LIGHT -> listOf(PixelRect(0, 0, width, height, LIGHT))
            PreviewBackground.DARK -> listOf(PixelRect(0, 0, width, height, DARK))
            PreviewBackground.TRAINER -> {
                val horizon = height / 2 + height / 10
                val cx = width / 2
                listOf(
                    PixelRect(0, 0, width, horizon, rgba(Theme.roomStructure)),
                    PixelRect(0, horizon, width, height - horizon, rgba(Theme.roomFloor)),
                    PixelRect(cx + width / 6, horizon - 44, 14, 44, rgba(Theme.roomBody)),
                    PixelRect(cx + width / 6 + 3, horizon - 56, 8, 8, rgba(Theme.accentSoft)),
                )
            }
        }
    }
}
