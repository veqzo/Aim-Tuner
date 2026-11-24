package dev.veqzo.aimtune.app.contribute

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.screens.StoredCounts
import dev.veqzo.aimtune.app.screens.UiScreen
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.addButton
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.modulePanel

private const val LEFT_WIDTH = 760f
private const val COLUMN_GAP = 64f
private const val WELL_PADDING = 36f
private const val WELL_WIDTH = 860f
private const val TEXT_GAP = 20f
private const val CAPTION_GAP = 8f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val BYTES_PER_KB = 1024

/**
 * "Contribute training data", opened from Share data when the build switches it on ([Contributions]). It explains why real rounds
 * help, what the file holds and that a public issue is tied to the GitHub account, then runs the steps of [ContributionFlow], each
 * on a click. AimTune uploads nothing: the person attaches or links the file in the browser. Esc and Back cancel at any step.
 */
class ContributeScreen(
    ctx: AppContext,
    private val back: () -> Unit,
    private val stored: StoredCounts,
    private val flow: ContributionFlow,
) : UiScreen(ctx) {

    override fun build(): Actor {
        val left = Table().top().left()
        left.add(ui.label(t("contribute.intro"), Styles.BODY, wrap = true)).left().width(LEFT_WIDTH).row()
        left.add(ui.label(t("contribute.voluntary"), Styles.BODY_MUTED, wrap = true)).left().width(LEFT_WIDTH).padTop(TEXT_GAP).row()
        left.add(ui.label(t("contribute.steps"), Styles.BODY_MUTED, wrap = true)).left().width(LEFT_WIDTH).padTop(TEXT_GAP).row()
        stateLines().forEach { left.add(it).left().width(LEFT_WIDTH).padTop(TEXT_GAP).row() }

        val body = Table().top().left()
        body.add(left).top().left().width(LEFT_WIDTH)
        body.add(infoWell()).top().left().width(WELL_WIDTH).padLeft(COLUMN_GAP)
        body.add().expandX()
        val panel = ui.modulePanel(t("contribute.title"), status())
        panel.add(body).grow()
        val content = Table()
        content.add(panel).grow()
        return frame(content, footer())
    }

    override fun render(delta: Float) {
        if (flow.poll()) rebuild()
        super.render(delta)
    }

    override fun onEscape() = leave()

    private fun leave() {
        flow.cancel()
        back()
    }

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { leave() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        when (val state = flow.state) {
            ContributionState.Intro, is ContributionState.Failed, ContributionState.Exporting -> {
                val create = ui.button(t("contribute.create"), Styles.PRIMARY) { act(flow::start) }
                create.isDisabled = stored.rounds == 0 || state == ContributionState.Exporting
                footer.addButton(create, minWidth = 260f)
            }
            is ContributionState.Ready -> {
                footer.addButton(ui.button(t("contribute.show_file"), Styles.SECONDARY) { act(flow::revealFile) }, minWidth = 220f).padRight(16f)
                val url = state.link.getOrNull()
                if (url != null && state.browserFailed) {
                    footer.addButton(ui.button(t("contribute.copy_link"), Styles.SECONDARY) { Gdx.app.clipboard.contents = url }, minWidth = 220f).padRight(16f)
                }
                val open = ui.button(t("contribute.open_issue"), Styles.PRIMARY) { act(flow::openIssue) }
                open.isDisabled = url == null
                footer.addButton(open, minWidth = 300f)
            }
        }
        return footer
    }

    private fun act(step: () -> Unit) {
        step()
        rebuild()
    }

    private fun status(): ModuleStatus = when (flow.state) {
        ContributionState.Intro -> if (stored.rounds == 0) ModuleStatus(LedState.OFF, t("share.none")) else ModuleStatus(LedState.OK, t("share.counts", stored.sessions, stored.rounds))
        ContributionState.Exporting -> ModuleStatus(LedState.LIVE, t("contribute.working"))
        is ContributionState.Ready -> ModuleStatus(LedState.OK, t("contribute.ready_status"))
        is ContributionState.Failed -> ModuleStatus(LedState.CAUTION, t("contribute.failed_status"))
    }

    /** What the current step says under the intro. */
    private fun stateLines(): List<Actor> = when (val state = flow.state) {
        ContributionState.Intro -> emptyList()
        ContributionState.Exporting -> listOf(ui.label(t("contribute.working"), Styles.BODY_MUTED))
        is ContributionState.Failed -> listOf(
            ui.label(t(if (state.reason == FailReason.EMPTY) "contribute.empty" else "contribute.failed"), Styles.CAUTION, wrap = true),
        )
        is ContributionState.Ready -> buildList {
            val export = state.export
            add(ui.label(t("contribute.ready", export.sessions, export.rounds, (export.bytes + BYTES_PER_KB - 1) / BYTES_PER_KB), Styles.OK, wrap = true))
            if (state.link.isFailure) add(ui.label(t("contribute.no_link"), Styles.CAUTION, wrap = true))
            if (state.revealFailed) add(ui.label(t("contribute.reveal_failed", export.file.parent), Styles.CAUTION, wrap = true))
            if (state.browserFailed) add(ui.label(t("contribute.browser_failed"), Styles.CAUTION, wrap = true))
        }
    }

    /** What the file holds and what sharing it in public means, in a recessed panel. */
    private fun infoWell(): Table {
        val inner = WELL_WIDTH - 2 * WELL_PADDING
        val well = Table().top().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING)
        well.add(ui.caps(t("share.contains_title"))).left().row()
        well.add(ui.label(t("share.contains"), Styles.BODY_MUTED, wrap = true)).left().width(inner).padTop(CAPTION_GAP).padBottom(TEXT_GAP * 1.5f).row()
        well.add(ui.caps(t("share.not_contains_title"))).left().row()
        well.add(ui.label(t("share.not_contains"), Styles.BODY_MUTED, wrap = true)).left().width(inner).padTop(CAPTION_GAP).padBottom(TEXT_GAP * 1.5f).row()
        well.add(ui.caps(t("contribute.public_title"))).left().row()
        well.add(ui.label(t("contribute.public"), Styles.BODY_MUTED, wrap = true)).left().width(inner).padTop(CAPTION_GAP)
        return well
    }
}
