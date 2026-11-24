package de.yuuto.aimtune.core.harness.review

import de.yuuto.aimtune.core.accel.AccelAnalysis
import de.yuuto.aimtune.core.accel.boundedRecommendation
import de.yuuto.aimtune.core.accel.fitAccel
import de.yuuto.aimtune.core.accel.movementSamples
import de.yuuto.aimtune.core.harness.TEST_DPI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Step 2 of docs/FIND-CURVE-REDESIGN.md: does the classic-with-offset family change how often the error analysis offers a curve?
 * Same plain calibration rounds (15, click scenarios), fitted with and without the family, for the four player models of the gate
 * harness; flat players are the null (offers = false positives), preferences of +-15 % and +-30 % are the detection.
 * Report: core/build/reports/harness/gate-find-redesign-2-offset.txt. AIMTUNE_SEEDS scales the run counts (40 = the table).
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class OffsetFamilyGateTest {
    private val scale = (System.getenv("AIMTUNE_SEEDS")?.toIntOrNull() ?: 40) / 40.0

    @Test
    fun offsetFamily() {
        val out = StringBuilder()
        out.appendLine("== GATE find-redesign-2 offset family @ git ${gitShortHash()} | 15 plain click rounds, bounded fit, with / without classic+offset ==")
        out.appendLine("model      pref    runs | offered without  offered with | classic+offset chosen")
        val rounds = 15
        for (model in gateModels) for (change in listOf(0.0, -0.30, -0.15, 0.15, 0.30)) {
            val runs = (if (change == 0.0) 200 * scale else 30 * scale).toInt().coerceAtLeast(8)
            val rows = parallelList(runs) { s ->
                val calib = playPlainRounds(model.apply(gainPlayer(change)), 120_000L + s, rounds)
                val analysis = AccelAnalysis(movementSamples(calib, TEST_DPI))
                val without = boundedRecommendation(fitAccel(analysis, withOffsetFamily = false), analysis) != null
                val withResult = fitAccel(analysis, withOffsetFamily = true)
                val with = boundedRecommendation(withResult, analysis)
                Triple(without, with != null, withResult.recommended?.family == "classic+offset")
            }
            out.appendLine(
                "%-10s %+4.0f%%  %5d | %15d  %12d | %d".format(model.name, change * 100, runs, rows.count { it.first }, rows.count { it.second }, rows.count { it.third }),
            )
        }
        write("gate-find-redesign-2-offset.txt", out.toString())
    }
}
