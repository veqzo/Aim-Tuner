package de.yuuto.aimtune.app.blind

import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.RoundSession
import de.yuuto.aimtune.app.TrainerConfig
import de.yuuto.aimtune.app.i18n.UiText
import de.yuuto.aimtune.core.metrics.computeRoundMetrics
import de.yuuto.aimtune.core.recording.RoundRecording
import de.yuuto.aimtune.core.score.duelEngagements
import de.yuuto.aimtune.storage.SessionKind

/** The blind sensitivity test as a [RoundSession]: finished rounds go to the optimizer, between rounds comes the confidence. */
class BlindSession(private val ctx: AppContext, private val run: BlindRun) : RoundSession {
    override val label: UiText get() = run.nextRoundLabel()
    override val runKey: Long get() = run.runKey
    override val sessionKind = SessionKind.SENSITIVITY

    override fun onRoundFinished(config: TrainerConfig, recording: RoundRecording) {
        run.test.recordRound(config.cm360, computeRoundMetrics(recording), config.scenario, duelEngagements(config.scenario, recording.targetEvents))
    }

    override fun showNext() = ctx.showBetweenRounds(run)

    override fun stop() = ctx.stopBlindTest(run)
}
