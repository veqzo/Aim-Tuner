package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.scenes.scene2d.ui.Table
import java.util.Locale

private const val WELL_PADDING_X = 36f
private const val WELL_PADDING_Y = 24f
private const val UNIT_GAP = 14f
private const val UNIT_LIFT = 22f
private const val CAPTION_GAP = 6f
private const val COUNT_FROM = 0.6

/**
 * The display of a module: a recessed well with one big mono number, its unit beside it and a
 * caption above. The number counts up from a little below its value when the readout first
 * appears; [animate] false shows it at once (a rebuild must not replay the moment).
 */
fun UiSkin.readout(caption: String, value: Double?, unit: String, animate: Boolean, style: String = Styles.NUMBER_XL): Table {
    val well = Table()
    well.background(skin.getDrawable(Styles.WELL))
    well.pad(WELL_PADDING_Y, WELL_PADDING_X, WELL_PADDING_Y, WELL_PADDING_X)
    well.add(label(caption, Styles.LABEL)).left().colspan(2).padBottom(CAPTION_GAP).row()
    // When the well is stretched, the caption stays at the top and the number sits on the bottom edge.
    well.add().colspan(2).expandY().row()
    val number = label("", style)
    val format: (Double) -> String = { "%.1f".format(Locale.ROOT, it) }
    when {
        value == null -> number.setText("--")
        animate -> Motion.countUp(number, value * COUNT_FROM, value, tokens.motionSlow * 2, format = format)
        else -> number.setText(format(value))
    }
    well.add(number).left().bottom()
    well.add(label(unit, Styles.NUMBER_MD)).left().bottom().padLeft(UNIT_GAP).padBottom(UNIT_LIFT).expandX()
    return well
}
