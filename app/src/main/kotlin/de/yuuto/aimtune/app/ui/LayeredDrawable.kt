package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable

/**
 * A [base] shape with an [icon] on top, tinted with [iconColor]. The icon keeps its min size; it is
 * centred, or placed [iconRight] units from the right edge when that is set (select box chevron).
 */
class LayeredDrawable(
    private val base: Drawable,
    private val icon: Drawable,
    private val iconColor: Color,
    private val iconRight: Float? = null,
) : BaseDrawable(base) {
    override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
        base.draw(batch, x, y, width, height)
        val old = batch.packedColor
        val iw = icon.minWidth
        val ih = icon.minHeight
        val ix = if (iconRight == null) x + (width - iw) / 2 else x + width - iconRight - iw
        batch.setColor(iconColor.r, iconColor.g, iconColor.b, iconColor.a * batch.color.a)
        icon.draw(batch, ix, y + (height - ih) / 2, iw, ih)
        batch.packedColor = old
    }
}
