package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.ui.LedState
import de.yuuto.aimtune.app.ui.ModuleStatus
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.modulePanel
import de.yuuto.aimtune.app.update.AppVersion
import de.yuuto.aimtune.app.update.InstallKind
import de.yuuto.aimtune.app.update.ReleaseInfo
import de.yuuto.aimtune.app.update.UpdateFlow
import de.yuuto.aimtune.app.update.UpdateState
import java.util.Locale

private const val TEXT_WIDTH = 760f
private const val COLUMN_GAP = 64f
private const val WELL_WIDTH = 760f
private const val WELL_PADDING = 36f
private const val TEXT_GAP = 20f
private const val FOOTER_BUTTON_HEIGHT = 64f

/**
 * Updates the app from its release on GitHub, after the player asked for it: what will happen, then the download with its progress.
 * The file is checked against the size and SHA-256 GitHub reports; an installed copy runs the installer, a portable one replaces its
 * own files. Where the app cannot do this (a run from Gradle, a release without the file) the release page is offered instead.
 * One opened module: what happens on the left, the progress in a recessed panel on the right, the state as the LED of the head.
 */
class UpdateScreen(ctx: AppContext, private val release: ReleaseInfo, initial: UpdateState = UpdateState.Idle(ctx.installKind())) : UiScreen(ctx) {
    @Volatile
    private var state: UpdateState = initial
    private var flow: UpdateFlow? = null

    override fun build(): Actor {
        val left = Table().top().left()
        left.add(ui.label(t("update.versions", AppVersion.current, release.version.toString()), Styles.TITLE, wrap = true)).left().width(TEXT_WIDTH).row()
        left.add(ui.label(explanation(), Styles.BODY_MUTED, wrap = true)).left().width(TEXT_WIDTH).padTop(TEXT_GAP).row()
        val body = Table().top().left()
        body.add(left).top().left().width(TEXT_WIDTH)
        statusText().takeIf { it.isNotEmpty() }?.let { body.add(statusWell(it)).top().left().width(WELL_WIDTH).padLeft(COLUMN_GAP) }
        body.add().expandX()
        val panel = ui.modulePanel(t("update.title"), status())
        panel.add(body).grow()
        val content = Table()
        content.add(panel).grow()

        val footer = Table()
        val busy = state is UpdateState.Preparing || state is UpdateState.Downloading || state is UpdateState.Applying
        footer.add(ui.button(t(if (busy) "update.cancel" else "common.back"), Styles.TEXT) { leave() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        val offerStart = !busy && canUpdate()
        footer.addButton(ui.button(t("update.open_page"), Styles.SECONDARY) { ctx.openUrl(release.url) }, minWidth = 300f).padRight(if (offerStart) 16f else 0f)
        if (offerStart) footer.addButton(ui.button(t("update.start"), Styles.PRIMARY) { start() }, minWidth = 300f)
        return frame(content, footer)
    }

    override fun onEscape() = leave()

    private fun canUpdate() = state is UpdateState.Idle && (state as UpdateState.Idle).kind != InstallKind.UNKNOWN || state is UpdateState.Failed

    private fun explanation(): String = when ((state as? UpdateState.Idle)?.kind ?: ctx.installKind()) {
        InstallKind.INSTALLED -> t("update.explain_installed")
        InstallKind.PORTABLE -> t("update.explain_portable")
        InstallKind.UNKNOWN -> t("update.explain_unknown")
    }

    /** Lit while the update runs, a caution after a failure; off when only the release page can help. */
    private fun status(): ModuleStatus = when (state) {
        is UpdateState.Idle -> if (canUpdate()) ModuleStatus(LedState.OK, t("update.status_ready")) else ModuleStatus(LedState.OFF, t("update.status_manual"))
        is UpdateState.Failed -> ModuleStatus(LedState.CAUTION, t("update.status_failed"))
        else -> ModuleStatus(LedState.LIVE, t("update.status_busy"))
    }

    private fun statusWell(text: String): Table {
        val well = Table().top().left()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(WELL_PADDING)
        well.add(ui.label(text, if (state is UpdateState.Failed) Styles.CAUTION else Styles.BODY, wrap = true)).left().width(WELL_WIDTH - 2 * WELL_PADDING)
        return well
    }

    private fun statusText(): String = when (val s = state) {
        is UpdateState.Idle -> ""
        UpdateState.Preparing -> t("update.preparing")
        is UpdateState.Downloading -> t("update.downloading", "%.1f".format(Locale.ROOT, s.done / 1_048_576.0), "%.1f".format(Locale.ROOT, s.total / 1_048_576.0))
        UpdateState.Applying -> t("update.applying")
        is UpdateState.Failed -> t("update.failed", reasonText(s.reason))
    }

    private fun reasonText(reason: String): String = when (reason) {
        UpdateFlow.Reasons.UNREACHABLE -> t("update.reason_unreachable")
        UpdateFlow.Reasons.NOT_PACKAGED -> t("update.reason_not_packaged")
        UpdateFlow.Reasons.NO_FILE -> t("update.reason_no_file")
        else -> t("update.reason_other", reason)
    }

    private fun start() {
        if (flow != null && state !is UpdateState.Failed) return
        state = UpdateState.Preparing
        val kind = ctx.installKind()
        val dir = ctx.updateDir ?: return show(UpdateState.Failed(UpdateFlow.Reasons.NOT_PACKAGED))
        val running = UpdateFlow(
            workDir = dir, kind = kind, launcher = ctx.launcher(),
            onState = { show(it) },
            onFinished = { Gdx.app.postRunnable { Gdx.app.exit() } },
        )
        flow = running
        Thread({ running.run(release) }, "aimtune-update").apply { isDaemon = true }.start()
    }

    /** Shows [next] on the UI thread. */
    private fun show(next: UpdateState) {
        state = next
        Gdx.app.postRunnable { if (ctx.currentScreen === this) rebuild() }
    }

    private fun leave() {
        flow?.cancel()
        ctx.showMenu()
    }
}
