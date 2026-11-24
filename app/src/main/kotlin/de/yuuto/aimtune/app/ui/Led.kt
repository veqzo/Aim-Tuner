package de.yuuto.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Stack

private const val LED_DOT = 8f
private const val LED_HALO = 24f
private const val HALO_ALPHA = 0.45f

/** The LED states of the Rack design. A LED always stands next to a text: color never carries meaning alone. */
enum class LedState { OK, CAUTION, LIVE, OFF }

fun Tokens.ledColor(state: LedState): Color = when (state) {
    LedState.OK -> ledOk
    LedState.CAUTION -> ledCaution
    LedState.LIVE -> accent
    LedState.OFF -> ledOff
}

/**
 * A status LED: a small dot with a soft halo of its own color. An off LED has no halo. A [LedState.LIVE]
 * LED pulses (a recording or running state); the others stay still.
 */
fun UiSkin.led(state: LedState): Stack {
    val color = tokens.ledColor(state)
    val stack = Stack()
    if (state != LedState.OFF) {
        val halo = Image(glow).also { it.color.set(color.r, color.g, color.b, HALO_ALPHA) }
        stack.add(Container(halo).size(LED_HALO))
    }
    stack.add(Container(Image(dot(color, LED_DOT))).size(LED_DOT))
    if (state == LedState.LIVE) Motion.pulse(stack, tokens.motionSlow * 4)
    return stack
}

/** The design size of [led], for table cells. */
const val LED_CELL = LED_HALO
