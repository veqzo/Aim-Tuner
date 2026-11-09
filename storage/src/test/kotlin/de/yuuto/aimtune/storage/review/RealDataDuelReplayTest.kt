package de.yuuto.aimtune.storage.review

import de.yuuto.aimtune.core.optimizer.Observation
import de.yuuto.aimtune.core.optimizer.OptimizationResult
import de.yuuto.aimtune.core.optimizer.SensitivityOptimizer
import de.yuuto.aimtune.core.score.DuelScorer
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.core.score.ScoreCalculator
import de.yuuto.aimtune.core.score.ScoreMetric
import de.yuuto.aimtune.core.score.engagements
import de.yuuto.aimtune.core.units.AimStyle
import de.yuuto.aimtune.core.units.SearchRange
import de.yuuto.aimtune.core.units.searchRange
import de.yuuto.aimtune.storage.Database
import de.yuuto.aimtune.storage.RoundRepository
import de.yuuto.aimtune.storage.RoundRow
import de.yuuto.aimtune.storage.SessionKind
import de.yuuto.aimtune.storage.scoreInputs
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * M23 step c (docs/VALORANT-WEIGHTS.md): rescores the blind sensitivity rounds of a COPY of a real database (AIMTUNE_REAL_DB)
 * with the duel-win score and compares the optimizer's result with the current weighted score. Opening the copy migrates it,
 * so never point this at the live database. AIMTUNE_REAL_CM360 is the player's current cm/360 (centre of the search range,
 * as the app builds it), AIMTUNE_REAL_PAD_CM the pad width. Writes build/reports/real-duel-replay.txt.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REAL_DB", matches = ".+")
class RealDataDuelReplayTest {
    @Test
    fun replay() {
        val out = StringBuilder()
        Database.open(Path.of(System.getenv("AIMTUNE_REAL_DB"))).use { db ->
            val repository = RoundRepository(db)
            val sessions = repository.sessions().filter { it.kind == SessionKind.SENSITIVITY }
            val rounds = sessions.flatMap { repository.rounds(it.id) }.filter { it.blind }
            val current = System.getenv("AIMTUNE_REAL_CM360")?.toDouble() ?: rounds.map { it.cm360 }.sorted()[rounds.size / 2]
            val range = searchRange(AimStyle.MIXED, current, System.getenv("AIMTUNE_REAL_PAD_CM")?.toDouble() ?: PAD_CM)
            out.appendLine("blind sensitivity rounds: ${rounds.size} ${rounds.groupingBy { it.scenario }.eachCount()}; range $range")
            val click = rounds.filter { it.scenario in CLICK }
            out.appendLine("old weights, all rounds:   " + format(analyze(range, rounds, weighted(rounds))))
            out.appendLine("old weights, click rounds: " + format(analyze(range, click, weighted(click))))
            val duel = DuelScorer().scoreRounds(click.map { engagements(checkNotNull(repository.loadRecording(it.id)).targetEvents) }, click.map { it.scenario })
            out.appendLine("duel score, click rounds:  " + format(analyze(range, click, duel)))
            val old = weighted(click)
            out.appendLine("round                 cm360   old   duel")
            click.indices.forEach { out.appendLine("%-20s %6.1f %5.1f %6.1f".format(click[it].scenario, click[it].cm360, old[it] ?: Double.NaN, duel[it] ?: Double.NaN)) }
        }
        println(out)
        val dir = Path.of("build", "reports")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("real-duel-replay.txt"), out.toString())
    }

    private fun weighted(rounds: List<RoundRow>): List<Double?> =
        ScoreCalculator(USER_WEIGHTS).scoreRounds(rounds.map { it.scoreInputs() }, rounds.map { it.scenario })

    private fun analyze(range: SearchRange, rounds: List<RoundRow>, scores: List<Double?>): OptimizationResult? {
        val step = HashMap<Long, Int>()
        val observations = rounds.indices.filter { scores[it] != null }.map {
            Observation(rounds[it].sessionId, rounds[it].cm360, scores[it]!!, rounds[it].scenario.hashCode(), checkNotNull(step.merge(rounds[it].sessionId, 1, Int::plus)) - 1)
        }
        return SensitivityOptimizer(range, seed = 0L).analyze(observations)
    }

    private fun format(r: OptimizationResult?): String = if (r == null) {
        "no result"
    } else {
        "optimum %.1f cm/360 (Valorant @800 %.3f), 80%% interval %.1f-%.1f, equivalent %.1f-%.1f, confidence %d%%, rounds %d"
            .format(r.optimumCm360, VALORANT_800 / r.optimumCm360, r.intervalLowCm360, r.intervalHighCm360, r.equivalentLowCm360, r.equivalentHighCm360, r.confidencePercent, r.rounds)
    }

    private companion object {
        val CLICK = setOf("flick", "micro", "switching", "flash", "micro-strafe", "switch-strafe")

        /** The weights in the player's settings when the rounds were played. */
        val USER_WEIGHTS = MetricWeights(mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0, ScoreMetric.TRACKING to 20.0, ScoreMetric.FLASH_DODGE to 15.0))
        const val PAD_CM = 32.0

        /** Valorant sensitivity at 800 DPI is this divided by cm/360 (yaw 0.07°/count: 360 x 2.54 / (0.07 x 800)). */
        const val VALORANT_800 = 360.0 * 2.54 / (0.07 * 800.0)
    }
}
