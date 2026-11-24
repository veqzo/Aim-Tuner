package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.RoundSession
import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.app.i18n.UiText
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.storage.SessionKind

/** The refine rounds as a [RoundSession]; stored as their own kind, which searches and calibrations ignore. */
class RefineSession(private val ctx: AppContext, private val run: RefineRun) : RoundSession {
    override val label: UiText get() = run.label
    override val runKey: Long get() = run.runKey
    override val sessionKind: SessionKind get() = SessionKind.ACCEL_REFINE

    override fun onRoundFinished(config: TrainerConfig, recording: RoundRecording) = run.recordRound(recording)

    override fun showNext() = ctx.showRefineStep(run)

    override fun stop() = ctx.showMenu()
}
