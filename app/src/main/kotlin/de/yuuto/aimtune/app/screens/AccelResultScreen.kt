package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.accel.AccelRun
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.caps
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.core.accel.AbStatus
import de.yuuto.aimtune.core.accel.AbVerdict
import de.yuuto.aimtune.storage.RawAccelExport
import java.nio.file.Path
import java.util.Locale

private const val TEXT_WIDTH = 1000f
private const val CARD_WIDTH = 900f
private const val CARD_PADDING = 32f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * The verdict of the blind test. Only when the curve was measurably better is a Raw Accel file
 * offered; otherwise the plain sensitivity stays what AimTune recommends.
 */
class AccelResultScreen(ctx: AppContext, private val run: AccelRun) : UiScreen(ctx) {
    private val verdict: AbVerdict? = run.verdict
    private var exported: Result<Path>? = null

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(heading(), Styles.HEADING)).left().padBottom(32f).row()
        content.add(ui.label(explanation(), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padBottom(32f).row()
        if (verdict?.status == AbStatus.ACCEL_BETTER) content.add(exportCard()).left().width(CARD_WIDTH).row()

        val footer = Table()
        footer.add(ui.button(t("common.back_to_menu"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        if (verdict?.status == AbStatus.ACCEL_BETTER) {
            footer.addButton(ui.button(t("accel.result.copy"), Styles.SECONDARY) { copy() }, minWidth = 260f).padRight(16f)
            footer.addButton(ui.button(t("common.save_as"), Styles.SECONDARY) { saveAs() }, minWidth = 240f).padRight(16f)
            footer.addButton(ui.button(t("accel.result.export"), Styles.PRIMARY) { export() }, minWidth = 300f)
        }
        return ui.frame(null, content, footer)
    }

    override fun onEscape() = ctx.showMenu()

    private fun heading() = when (verdict?.status) {
        AbStatus.ACCEL_BETTER -> t("accel.result.better")
        AbStatus.ACCEL_WORSE -> t("accel.result.worse")
        AbStatus.NO_DIFFERENCE -> t("accel.result.no_difference")
        else -> t("accel.result.stopped")
    }

    private fun explanation(): String {
        val v = verdict ?: return t("accel.result.unfinished")
        val numbers = t(
            "accel.result.numbers", v.pairs, "%+.1f".format(Locale.ROOT, v.meanDifference), "%+.1f".format(Locale.ROOT, v.lowerBound), "%+.1f".format(Locale.ROOT, v.upperBound),
        )
        val trial = when {
            run.isTrial -> " " + t("accel.result.trial_note")
            run.isDraft -> " " + t("accel.result.draft_note")
            run.isSearch -> " " + t("accel.result.search_note")
            else -> ""
        }
        return when (v.status) {
            AbStatus.ACCEL_BETTER -> "$numbers " + t("accel.result.better_note") + trial
            AbStatus.ACCEL_WORSE -> "$numbers " + t("accel.result.worse_note")
            AbStatus.NO_DIFFERENCE -> "$numbers " + t("accel.result.no_difference_note")
            AbStatus.NEED_MORE_ROUNDS -> numbers
        }
    }

    private fun exportCard(): Table {
        val card = Table()
        card.background(ui.skin.getDrawable(Styles.CARD))
        card.pad(CARD_PADDING)
        card.add(ui.caps(t("accel.result.raw_accel_title"))).left().row()
        card.add(
            ui.label(t("accel.result.raw_accel_text"), Styles.CAPTION_MUTED, wrap = true),
        ).left().width(CARD_WIDTH - 2 * CARD_PADDING).padTop(8f).row()
        exported?.let { result ->
            val text = result.fold({ t("accel.result.saved", it) }, { t("accel.result.save_failed", it.message.orEmpty()) })
            card.add(ui.label(text, if (result.isSuccess) Styles.OK else Styles.CAUTION, wrap = true)).left().width(CARD_WIDTH - 2 * CARD_PADDING).padTop(16f)
        }
        return card
    }

    private fun export() {
        exported = ctx.exportAccel(run)
        rebuild()
    }

    /** Asks where to save; a cancelled dialog changes nothing. */
    private fun saveAs() {
        val path = ctx.fileDialogs.saveJson(t("file.dialog.save_title"), ctx.exportDir?.resolve("settings.json")) ?: return
        exported = ctx.saveAccelAs(run, path)
        rebuild()
    }

    private fun copy() {
        val candidate = run.candidate ?: return
        Gdx.app.clipboard.contents = RawAccelExport.settingsJson(candidate.curve, run.dpi, candidate.sensitivityFactor)
    }
}
