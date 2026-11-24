package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Layout

/**
 * Motion of the Rack design. Durations come from the tokens (fast, base, slow). Motion answers an
 * action or marks one moment (a result reveal); it never loops except on a live element such as a
 * recording LED. [instant] turns every helper into its end state, for the screenshot tour and for
 * players who switch motion off.
 */
object Motion {
    /** True: helpers jump to their end state. */
    var instant = false

    val enter: Interpolation = Interpolation.pow3Out

    /** Fades [actor] in after [delay] seconds over [duration]. */
    fun fadeIn(actor: Actor, duration: Float, delay: Float = 0f) {
        if (instant) {
            actor.color.a = 1f
            return
        }
        actor.color.a = 0f
        actor.addAction(Actions.sequence(Actions.delay(delay), Actions.fadeIn(duration, enter)))
    }

    /** A soft pulse of the alpha between [low] and 1, for live elements only (recording LED, countdown). */
    fun pulse(actor: Actor, period: Float, low: Float = PULSE_LOW) {
        if (instant) return
        actor.addAction(
            Actions.forever(
                Actions.sequence(Actions.alpha(low, period / 2, Interpolation.sine), Actions.alpha(1f, period / 2, Interpolation.sine)),
            ),
        )
    }

    /** Counts [label] up from [from] to [to] with [format], then leaves the exact final text. */
    fun countUp(label: Label, from: Double, to: Double, duration: Float, delay: Float = 0f, format: (Double) -> String) {
        label.setText(format(if (instant) to else from))
        if (instant) return
        label.addAction(Actions.sequence(Actions.delay(delay), CountUp(label, from, to, format).also { it.duration = duration; it.interpolation = enter }))
    }

    /**
     * Turns a transform group around its vertical axis: it shrinks to a line, [atEdge] swaps the
     * face, then it opens again. [actor] needs `isTransform = true`; the origin is set to its centre.
     */
    fun flip(actor: Actor, duration: Float, atEdge: () -> Unit) {
        if (instant) {
            atEdge()
            return
        }
        actor.setOrigin(actor.width / 2, actor.height / 2)
        actor.clearActions()
        actor.addAction(
            Actions.sequence(
                Actions.scaleTo(0f, 1f, duration / 2, Interpolation.pow2In),
                Actions.run(atEdge),
                Actions.scaleTo(1f, 1f, duration / 2, Interpolation.pow2Out),
            ),
        )
    }

    /** A quick press feedback: the transform [actor] dips to [scale] and springs back. */
    fun press(actor: Actor, duration: Float, scale: Float = PRESS_SCALE): Action? {
        if (instant) return null
        actor.setOrigin(actor.width / 2, actor.height / 2)
        return Actions.sequence(Actions.scaleTo(scale, scale, duration / 3, Interpolation.pow2Out), Actions.scaleTo(1f, 1f, duration * 2 / 3, Interpolation.swingOut))
            .also(actor::addAction)
    }

    private const val PULSE_LOW = 0.35f
    private const val PRESS_SCALE = 0.97f
}

/** Interpolates a number shown in a label. */
private class CountUp(private val label: Label, private val from: Double, private val to: Double, private val format: (Double) -> String) : TemporalAction() {
    override fun update(percent: Float) {
        label.setText(format(if (percent >= 1f) to else from + (to - from) * percent))
    }
}

/**
 * Wraps one actor so it can slide and fade in without fighting the parent's layout: the wrapper
 * takes the layout bounds, the child moves inside it. Use [play] to start; [delay] staggers a group.
 */
class Reveal(private val child: Actor, private val distance: Float = DEFAULT_DISTANCE) : WidgetGroup(child) {
    private var elapsed = 0f
    private var delay = 0f
    private var duration = 0f
    private var running = false
    private var progress = 1f

    /** Starts the reveal; the child is invisible until [delay] has passed. */
    fun play(duration: Float, delay: Float = 0f): Reveal {
        if (Motion.instant) return this
        this.duration = duration
        this.delay = delay
        elapsed = 0f
        progress = 0f
        running = true
        applyProgress()
        return this
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!running) return
        elapsed += delta
        progress = ((elapsed - delay) / duration).coerceIn(0f, 1f)
        if (progress >= 1f) running = false
        applyProgress()
    }

    private fun applyProgress() {
        val eased = Motion.enter.apply(progress)
        child.y = -distance * (1f - eased)
        color.a = eased
    }

    override fun layout() {
        child.setBounds(0f, child.y, width, height)
        if (child is Layout) child.validate()
    }

    override fun getPrefWidth(): Float = (child as? Layout)?.prefWidth ?: child.width

    override fun getPrefHeight(): Float = (child as? Layout)?.prefHeight ?: child.height

    override fun getMinWidth(): Float = (child as? Layout)?.minWidth ?: child.width

    override fun getMinHeight(): Float = (child as? Layout)?.minHeight ?: child.height

    private companion object {
        const val DEFAULT_DISTANCE = 18f
    }
}

/** Wraps [actors] in [Reveal]s that enter one after another, [step] seconds apart. */
fun stagger(actors: List<Actor>, duration: Float, step: Float, delay: Float = 0f): List<Reveal> =
    actors.mapIndexed { i, actor -> Reveal(actor).play(duration, delay + i * step) }
