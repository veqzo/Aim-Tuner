package de.yuuto.aimtune.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import de.yuuto.aimtune.app.i18n.I18n
import de.yuuto.aimtune.app.i18n.scenarioName
import de.yuuto.aimtune.app.i18n.scenarioRound
import de.yuuto.aimtune.app.ui.Tokens
import de.yuuto.aimtune.core.crosshair.Crosshair
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.input.RawMouseSource
import java.util.Locale

/**
 * The trainer's on-screen text: round timer, hits, crosshair and the round summary. Drawn in
 * design units (1080 high, like the Scene2D screens) with the shared [Fonts] and the rack colors:
 * uppercase labels in the accent, numbers in mono, hints in a muted neutral. The scene itself keeps
 * its look, because it is a test condition.
 * The debug overlay exists to verify raw input. With a [roundLabel] the HUD is the blind one:
 * it shows "Round 4 / 10" and never the sensitivity (no debug overlay either, it prints deg/count).
 */
class Hud(
    private val config: TrainerConfig,
    private val tokens: Tokens,
    private val i18n: I18n,
    private val roundLabel: String? = null,
    /** The player's own Valorant crosshair, drawn in pixels on the back buffer ([CrosshairRenderer]). */
    private val crosshair: Crosshair = Crosshair(),
) : Disposable {
    private val batch = SpriteBatch()
    private val crosshairRenderer = CrosshairRenderer()
    private val camera = OrthographicCamera()
    private var width = 0f
    private var height = tokens.screenHeight
    var showDebug = roundLabel == null

    private val text = tokens.text
    private val muted = tokens.neutral(400)
    private val highlight = tokens.accent

    fun resize() {
        height = tokens.screenHeight
        width = height * Gdx.graphics.width / Gdx.graphics.height
        camera.setToOrtho(false, width, height)
    }

    fun render(trainer: Trainer, source: RawMouseSource, report: RoundReport?, fps: Int, fonts: Fonts) {
        camera.update()
        if (trainer.phase == Phase.RUNNING) drawCrosshair()
        batch.projectionMatrix = camera.combined
        batch.begin()
        when (trainer.phase) {
            Phase.READY -> drawReady(fonts)
            Phase.RUNNING -> drawRunning(trainer, fonts)
            Phase.FINISHED -> drawFinished(trainer, report, fonts)
        }
        if (showDebug) drawDebug(trainer, source, fps, fonts)
        batch.end()
    }

    private fun drawCrosshair() = crosshairRenderer.drawOnScreen(crosshair)

    private fun drawReady(fonts: Fonts) {
        if (roundLabel != null) {
            drawBlindReady(roundLabel, fonts)
            return
        }
        draw(fonts.title, i18n.t("hud.ready_title", config.profile.name, config.roundSeconds, i18n.scenarioRound(config.scenario)), width / 2, height * 0.62f, highlight, Align.center)
        draw(fonts.bodyLg, i18n.t("hud.click_to_start"), width / 2, height * 0.54f, text, Align.center)
        val info = i18n.t(
            "hud.free_info", "%.1f".format(Locale.ROOT, config.cm360), "%.0f".format(Locale.ROOT, config.dpi),
            "%.0f".format(Locale.ROOT, config.profile.fov.degrees), config.profile.fov.type.name.lowercase(),
        )
        draw(fonts.mono, info, width / 2, height * 0.49f, muted, Align.center)
        draw(fonts.body, i18n.t("hud.free_keys"), width / 2, height * 0.44f, muted, Align.center)
    }

    private fun drawBlindReady(label: String, fonts: Fonts) {
        draw(fonts.label, label.uppercase(), width / 2, height * 0.64f, highlight, Align.center)
        draw(fonts.title, i18n.t("hud.click_to_start"), width / 2, height * 0.58f, text, Align.center)
        draw(fonts.body, i18n.t("hud.blind_info", config.roundSeconds, i18n.scenarioRound(config.scenario)), width / 2, height * 0.49f, muted, Align.center)
        // The instruction wraps (the flash one is longer than a 1080p line); the stop line moves down by the extra lines.
        val wrapped = drawWrapped(fonts.body, instruction(), height * 0.44f, muted)
        draw(fonts.body, i18n.t("hud.blind_stop"), width / 2, height * 0.40f - (wrapped.height - fonts.body.capHeight), muted, Align.center)
    }

    private fun instruction(): String = when (config.scenario) {
        ScenarioKind.FLICK -> i18n.t("hud.instruction.flick")
        ScenarioKind.MICRO -> i18n.t("hud.instruction.micro")
        ScenarioKind.TRACKING -> i18n.t("hud.instruction.tracking")
        ScenarioKind.SWITCHING -> i18n.t("hud.instruction.switching")
        ScenarioKind.FLASH -> i18n.t("hud.instruction.flash")
        ScenarioKind.MICRO_STRAFE -> i18n.t("hud.instruction.micro-strafe")
        ScenarioKind.SWITCH_STRAFE -> i18n.t("hud.instruction.switch-strafe")
        ScenarioKind.RANGE -> i18n.t("hud.instruction.range")
    }

    private fun drawRunning(trainer: Trainer, fonts: Fonts) {
        val seconds = (trainer.timeLeftNanos() / 1_000_000_000L).toInt()
        draw(fonts.numberMd, "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60), width / 2, height - MARGIN, text, Align.center)
        draw(fonts.label, i18n.scenarioName(config.scenario).uppercase(), MARGIN, height - MARGIN, highlight, Align.left)
        val shots = when {
            config.scenario == ScenarioKind.RANGE -> i18n.t("hud.kills", trainer.hits, trainer.hits + trainer.misses)
            config.scenario.usesClicks -> i18n.t("hud.hits_misses", trainer.hits, trainer.misses)
            else -> ""
        }
        val right = roundLabel ?: shots
        draw(fonts.mono, right, width - MARGIN, height - MARGIN, text, Align.right)
        draw(fonts.caption, i18n.t("hud.abort"), MARGIN, MARGIN + fonts.caption.lineHeight, muted, Align.left)
    }

    private fun drawFinished(trainer: Trainer, report: RoundReport?, fonts: Fonts) {
        val s = trainer.summary ?: return
        draw(fonts.label, i18n.t("hud.round_complete").uppercase(), width / 2, height * 0.66f, highlight, Align.center)
        if (config.scenario.usesClicks) {
            val ttk = s.meanTimeToHitNanos?.let { "%.0f ms".format(Locale.ROOT, it / 1e6) } ?: "-"
            draw(fonts.mono, i18n.t("hud.hits_accuracy", s.hits, s.shots, "%.0f".format(Locale.ROOT, s.accuracy * 100)), width / 2, height * 0.58f, text, Align.center)
            draw(fonts.mono, i18n.t("hud.time_to_hit", ttk), width / 2, height * 0.54f, text, Align.center)
        }
        drawReport(report, height * 0.48f, fonts)
        draw(fonts.caption, i18n.t("hud.next"), width / 2, height * 0.30f, muted, Align.center)
    }

    /** Analysis lines under the round summary: first-movement error, corrections, path efficiency, save state. */
    private fun drawReport(report: RoundReport?, top: Float, fonts: Fonts) {
        val lines = when (report) {
            null, RoundReport.Pending -> listOf(i18n.t("hud.analysing") to muted)
            is RoundReport.Failed -> listOf(i18n.t("hud.analysis_failed", report.message) to tokens.caution)
            is RoundReport.Done -> reportLines(report)
        }
        var y = top
        lines.forEach { (line, color) ->
            draw(fonts.mono, line, width / 2, y, color, Align.center)
            y -= fonts.mono.lineHeight
        }
    }

    private fun reportLines(report: RoundReport.Done): List<Pair<String, Color>> {
        val m = report.metrics
        val error = m.meanFirstError?.let {
            i18n.t(if (it >= 0) "hud.first_move_over" else "hud.first_move_under", "%+.0f".format(Locale.ROOT, it * 100))
        }
        return listOfNotNull(
            m.trackingOnTargetRatio?.let { i18n.t("hud.on_target", "%.0f".format(Locale.ROOT, it * 100)) to text },
            m.trackingMeanDistanceDegrees?.let { i18n.t("hud.mean_distance", "%.2f".format(Locale.ROOT, it)) to text },
            error?.let { it to text },
            m.meanCorrectiveSubmovements?.let { i18n.t("hud.corrections", "%.1f".format(Locale.ROOT, it)) to text },
            m.meanPathEfficiency?.let { i18n.t("hud.path_efficiency", "%.0f".format(Locale.ROOT, it * 100)) to text },
            if (report.noMouseInput) i18n.t("hud.no_mouse") to tokens.caution else null,
            (if (report.saveError == null) i18n.t("hud.saved") else i18n.t("hud.not_saved", report.saveError)) to
                (if (report.saveError == null) muted else tokens.caution),
        )
    }

    private fun drawDebug(trainer: Trainer, source: RawMouseSource, fps: Int, fonts: Fonts) {
        val last = trainer.lastEvent
        val lines = listOf(
            "source: ${source.name}",
            "events: ${source.eventsReceived}  rate: %.0f/s  dropped: ${source.droppedEvents}".format(Locale.ROOT, trainer.rate.ratePerSecond(trainer.nowNanos())),
            "last: ${last?.let { "dx=${it.dx} dy=${it.dy}" } ?: "-"}",
            "yaw %.2f  pitch %.2f".format(Locale.ROOT, trainer.view.yawDegrees, trainer.view.pitchDegrees),
            "deg/count %.5f  seed ${config.seed}  fps $fps".format(Locale.ROOT, trainer.degreesPerCountValue),
        )
        var y = height - MARGIN - fonts.numberMd.lineHeight
        lines.forEach {
            draw(fonts.mono, it, MARGIN, y, muted, Align.left)
            y -= fonts.mono.lineHeight
        }
    }

    /** With a target width of 0, [align] is relative to [x] itself (centered or right-aligned at x). */
    private fun draw(font: BitmapFont, s: String, x: Float, y: Float, color: Color, align: Int) {
        font.color = color
        font.draw(batch, s, x, y, 0f, align, false)
    }

    /** Draws [s] centered and wrapped to at most [INSTRUCTION_WIDTH] of the window width; returns the layout for its height. */
    private fun drawWrapped(font: BitmapFont, s: String, y: Float, color: Color): GlyphLayout {
        val wrapWidth = minOf(width * INSTRUCTION_WIDTH, width - 2 * MARGIN)
        font.color = color
        return font.draw(batch, s, (width - wrapWidth) / 2, y, wrapWidth, Align.center, true)
    }

    override fun dispose() {
        batch.dispose()
        crosshairRenderer.dispose()
    }

    private companion object {
        const val MARGIN = 72f

        /** Widest instruction line, as a share of the window width. */
        const val INSTRUCTION_WIDTH = 0.6f
    }
}
