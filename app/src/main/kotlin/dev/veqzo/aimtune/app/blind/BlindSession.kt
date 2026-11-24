package dev.veqzo.aimtune.app.blind

import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.RoundSession
import dev.veqzo.aimtune.app.TrainerConfig
import dev.veqzo.aimtune.app.i18n.UiText
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.score.duelEngagements
import dev.veqzo.aimtune.storage.SessionKind

/** The blind sensitivity test as a [RoundSession]: finished rounds go to the optimizer, between rounds comes the confidence. */
class BlindSession(private val ctx: AppContext, private val run: BlindRun) : RoundSession {
    override val label: UiText get() = run.nextRoundLabel()
    override val runKey: Long get() = run.runKey
    override val sessionKind = SessionKind.SENSITIVITY

    override fun onRoundFinished(config: TrainerConfig, recording: RoundRecording) {
        run.test.recordRound(config.cm360, computeRoundMetrics(recording), config.scenario, duelEngagements(config.scenario, recording.targetEvents, recording.endNanos))
    }

    override fun showNext() = ctx.showBetweenRounds(run)

    override fun stop() = ctx.stopBlindTest(run)
}
