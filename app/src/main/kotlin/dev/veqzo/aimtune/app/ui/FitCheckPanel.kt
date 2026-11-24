package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.core.units.doesTurnFitPad
import kotlin.math.min

private const val LINE_AREA_HEIGHT = 40f
private const val LINE_THICKNESS = 4f
private const val TICK_HEIGHT = 22f
private const val TICK_WIDTH = 3f
private const val TEXT_GAP = 6f

/**
 * "Fit check" of the setup: draws the distance of one full turn against the pad width, in the
 * accent color when it fits and in the caution color (clipped to the pad) when it does not.
 * [turnText] and [padText] are the two values as text in the user's language.
 */
class FitCheckPanel(
    private val ui: UiSkin,
    private val cm360: Double,
    private val padWidthCm: Double,
    turnText: String,
    padText: String,
) : Table() {
    init {
        add(TurnLine()).colspan(2).growX().height(LINE_AREA_HEIGHT).row()
        add(ui.label(turnText, Styles.MONO_MUTED)).left().expandX().padTop(TEXT_GAP)
        add(ui.label(padText, Styles.MONO_MUTED)).right().padTop(TEXT_GAP)
    }

    val fits: Boolean get() = doesTurnFitPad(cm360, padWidthCm)

    private inner class TurnLine : Actor() {
        override fun draw(batch: Batch, parentAlpha: Float) {
            val ratio = cm360 / padWidthCm
            val length = width * min(ratio, 1.0).toFloat()
            val color = if (fits) ui.tokens.accent else ui.tokens.caution
            val centerY = y + height / 2
            batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
            ui.whiteSolid.draw(batch, x, centerY - LINE_THICKNESS / 2, length, LINE_THICKNESS)
            ui.whiteSolid.draw(batch, x, centerY - TICK_HEIGHT / 2, TICK_WIDTH, TICK_HEIGHT)
            ui.whiteSolid.draw(batch, x + length - TICK_WIDTH, centerY - TICK_HEIGHT / 2, TICK_WIDTH, TICK_HEIGHT)
            batch.color = Color.WHITE
        }
    }
}
