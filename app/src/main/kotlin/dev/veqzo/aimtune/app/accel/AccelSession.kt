package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.RoundSession
import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.app.i18n.UiText
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.storage.SessionKind

/** The acceleration calibration and A/B rounds as a [RoundSession]; they are stored as their own kind of session. */
class AccelSession(private val ctx: AppContext, private val run: AccelRun) : RoundSession {
    override val label: UiText get() = run.label
    override val runKey: Long get() = run.runKey
    // Read when a round has been played and before the run moves on, so it is the kind of the phase that round belonged to.
    override val sessionKind: SessionKind get() = if (run.phase == AccelPhase.SEARCH) SessionKind.ACCEL_SEARCH else SessionKind.ACCEL_AB

    override fun onRoundFinished(config: TrainerConfig, recording: RoundRecording) = run.recordRound(recording)

    override fun showNext() = ctx.showAccelStep(run)

    override fun stop() = ctx.showMenu()
}
