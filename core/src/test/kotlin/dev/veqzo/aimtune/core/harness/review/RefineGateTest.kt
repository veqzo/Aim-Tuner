package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelScenarios
import dev.veqzo.aimtune.core.accel.RawAccelArgs
import dev.veqzo.aimtune.core.accel.RawAccelCapMode
import dev.veqzo.aimtune.core.accel.RawAccelMode
import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.accel.RefineEngine
import dev.veqzo.aimtune.core.accel.RefineParameter
import dev.veqzo.aimtune.core.accel.RefinePhase
import dev.veqzo.aimtune.core.accel.RefineResult
import dev.veqzo.aimtune.core.accel.RefineRound
import dev.veqzo.aimtune.core.accel.adjusted
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.sim.HandAdaptation
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * M18/M22c gate: the headless driver of "Refine my Raw Accel curve" ([RefineEngine], the logic the app runs) with simulated players
 * whose hand is adapted to the imported curve. The imported profile is the user's real one (classic, gain form, offset 10,
 * cap 62.5 in/s at 1.6, output DPI 600 at device DPI 800). The game sensitivity is 55 cm/360, flash rounds are on.
 *
 * What the simulated player wants is a profile, its "ideal": [Model.ideal] gives the shape of the gain it expects at a hand
 * speed (the simulated player's `gainPreference`); its optimum scale is calibrated so that the
 * imported profile played at the game sensitivity sits at the player's optimum level: without that, the NULL model would
 * not be null (the simulated hand has one best cm/360, and a curve changes the cm/360 it effectively plays). The calibration
 * is printed in the report.
 *
 * The user policy is fixed: every stage is played to its end, the player continues through all stages and takes the test.
 * AIMTUNE_SEEDS scales the run counts (40 = the numbers in the table header).
 *
 * AIMTUNE_HAND=real plays the same gate with a hand fitted to the user's stored rounds (accel_refine session, per-report speeds,
 * [RefineSpeedCalibrationTest]): the game's target geometry, the user's 48.74 cm/360, and Fitts parameters and a hand speed that give
 * its speed quantiles (the default hand is 3-4 times slower, so the profile's curve hardly acts on it).
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class RefineGateTest {
    private val realHand = System.getenv("AIMTUNE_HAND") == "real"
    private val gameCm360 = if (realHand) 48.74 else 55.0
    private val geometry: TrainerGeometry? = if (realHand) TrainerGeometry.DEFAULT else null
    private val handBase = if (realHand) SimulatedPlayer(fittsInterceptMs = 40.0, fittsSlopeMs = 25.0, maxHandSpeedCmPerS = 300.0) else SimulatedPlayer()
    /** AIMTUNE_MIX=flash (flash rounds only) or heavy (three of four rounds flash): the engine's rotation for the flash-weighting experiment. */
    private val mixName = System.getenv("AIMTUNE_MIX")
    private val mix: List<ScenarioKind>? = when (mixName) {
        "flash" -> listOf(ScenarioKind.FLASH)
        "heavy" -> listOf(
            ScenarioKind.FLASH, ScenarioKind.FLICK, ScenarioKind.FLASH, ScenarioKind.FLASH, ScenarioKind.SWITCHING, ScenarioKind.FLASH, ScenarioKind.FLASH, ScenarioKind.MICRO,
        )
        else -> null
    }
    private val startScale = if (realHand) 0.35 else 0.95
    private val weights = MetricWeights.DEFAULT

    /**
     * AIMTUNE_SCORE replaces the round score the engine searches and tests with (the weights and the metrics it is fed; "harmful" is
     * still judged with the standard score on the standard mix). The values ride on scoring metrics, only their meaning differs:
     * turn = the flash turn time (carried by TIME_TO_HIT, lower is better), bias = minus the absolute signed first error (the
     * overshoot or undershoot a wrong gain causes), abserr = minus the mean absolute first error, combo = turn and abserr together.
     */
    private val scoreMode = System.getenv("AIMTUNE_SCORE")
    private val engineWeights = when (scoreMode) {
        null -> weights
        "turn" -> MetricWeights(mapOf(ScoreMetric.TIME_TO_HIT to 1.0))
        "bias", "abserr" -> MetricWeights(mapOf(ScoreMetric.ACCURACY to 1.0))
        "combo" -> MetricWeights(mapOf(ScoreMetric.ACCURACY to 1.0, ScoreMetric.TIME_TO_HIT to 1.0))
        else -> error("unknown AIMTUNE_SCORE $scoreMode")
    }

    private fun engineInputs(metrics: dev.veqzo.aimtune.core.metrics.RoundMetrics): Map<ScoreMetric, Double> = when (scoreMode) {
        null -> metrics.scoreInputs()
        "turn" -> listOfNotNull(metrics.flashMeanTurnMs?.let { ScoreMetric.TIME_TO_HIT to it }).toMap()
        "bias" -> listOfNotNull(metrics.meanFirstError?.let { ScoreMetric.ACCURACY to -kotlin.math.abs(it) }).toMap()
        "abserr" -> listOfNotNull(metrics.meanAbsFirstError?.let { ScoreMetric.ACCURACY to -it }).toMap()
        else -> listOfNotNull(
            metrics.flashMeanTurnMs?.let { ScoreMetric.TIME_TO_HIT to it }, metrics.meanAbsFirstError?.let { ScoreMetric.ACCURACY to -it },
        ).toMap()
    }

    private fun playEngine(player: SimulatedPlayer, round: RefineRound, index: Int): Map<ScoreMetric, Double> =
        engineInputs(
            computeRoundMetrics(
                HeadlessRound.of(round.scenario, player, TEST_DPI, round.cm360, round.seed, ROUND_SECONDS, index * ROUND_SECONDS * NANOS_PER_SECOND, accel = round.profile, geometry = geometry),
            ),
        )
    private val imported = RawAccelProfile(
        "default", RawAccelArgs(RawAccelMode.CLASSIC, gain = true, inputOffset = 10.0, capX = 62.5, capY = 1.6, capMode = RawAccelCapMode.IN_OUT), 600.0, 800.0,
    )
    private val scale = (System.getenv("AIMTUNE_SEEDS")?.toIntOrNull() ?: 40) / 40.0

    private fun n(base: Int) = (base * scale).toInt().coerceAtLeast(4)

    /**
     * A simulated player: the profile whose gain shape it expects, its range bias, and the stage ([want]) and factor its true
     * preference is at, for the direction columns (null: no preference).
     */
    private class Model(
        val name: String,
        val ideal: RawAccelProfile,
        val want: Pair<RefineParameter, Double>? = null,
        val rangeBias: Double = 0.0,
        val runs: Int,
        val stageRounds: Int = 12,
    )

    private val models = listOf(
        Model("REFINE_NULL", imported, runs = n(500)),
        Model("REFINE_CAP-25", imported.adjusted(RefineParameter.HIGH_GAIN, 0.75), RefineParameter.HIGH_GAIN to 0.75, runs = n(200)),
        Model("REFINE_CAP+25", imported.adjusted(RefineParameter.HIGH_GAIN, 1.25), RefineParameter.HIGH_GAIN to 1.25, runs = n(200)),
        Model("REFINE_TRANS-33", imported.adjusted(RefineParameter.TRANSITION, 0.67), RefineParameter.TRANSITION to 0.67, runs = n(200)),
        Model("REFINE_TRANS+50", imported.adjusted(RefineParameter.TRANSITION, 1.5), RefineParameter.TRANSITION to 1.5, runs = n(200)),
        Model("REFINE_RANGE", imported, rangeBias = 0.05, runs = n(100)),
        // Larger preferences. HIGH_GAIN searches 0.75..1.33 only: 0.5 and 2.0 lie outside, the best reachable factor is the range end.
        Model("REFINE_CAP x0.5 (out)", imported.adjusted(RefineParameter.HIGH_GAIN, 0.5), RefineParameter.HIGH_GAIN to 0.5, runs = n(200)),
        Model("REFINE_CAP x1.33 (end)", imported.adjusted(RefineParameter.HIGH_GAIN, 1.33), RefineParameter.HIGH_GAIN to 1.33, runs = n(200)),
        Model("REFINE_CAP x2.0 (out)", imported.adjusted(RefineParameter.HIGH_GAIN, 2.0), RefineParameter.HIGH_GAIN to 2.0, runs = n(200)),
        // TRANSITION searches 0.5..2.0: both are the range ends.
        Model("REFINE_TRANS x0.5 (end)", imported.adjusted(RefineParameter.TRANSITION, 0.5), RefineParameter.TRANSITION to 0.5, runs = n(200)),
        Model("REFINE_TRANS x2.0 (end)", imported.adjusted(RefineParameter.TRANSITION, 2.0), RefineParameter.TRANSITION to 2.0, runs = n(200)),
    )

    /** Hand speeds the simulated hand met and how far the true-preference curve is from the imported one there (one run). */
    private class Probe(private val offset: Double, private val truth: (Double) -> Double) {
        var calls = 0L
        var above = 0L
        var importedSum = 0.0
        var diffAbsSum = 0.0

        fun see(speed: Double, imported: Double) {
            calls++
            if (speed > offset) above++
            importedSum += imported
            diffAbsSum += kotlin.math.abs(imported - truth(speed))
        }
    }

    private val optimumScales = HashMap<Pair<Double, Boolean>, Double>()
    private var lastSweep = ""

    private fun player(model: Model, optimumScale: Double, probe: Probe? = null): SimulatedPlayer {
        val preference: (Double) -> Double = { v -> model.ideal.curve.multiplier(v) }
        // The probe sees every gain evaluation of the aimer (a few per reach), not the settling of the hand below.
        val seen: (Double) -> Double = { v -> preference(v).also { probe?.see(v, imported.curve.multiplier(v)) } }
        return handBase.copy(
            optimumScale = optimumScale, gainPreference = seen, rangeBias = model.rangeBias,
            // A fresh, mutable hand per simulated person, settled on the curve it has been playing: the imported one.
            adaptation = HandAdaptation.adaptedTo(preference, delivered = { v -> imported.curve.multiplier(v) }),
        )
    }

    private fun play(player: SimulatedPlayer, round: RefineRound, index: Int): Map<ScoreMetric, Double> =
        computeRoundMetrics(
            HeadlessRound.of(round.scenario, player, TEST_DPI, round.cm360, round.seed, ROUND_SECONDS, index * ROUND_SECONDS * NANOS_PER_SECOND, accel = round.profile, geometry = geometry),
        ).scoreInputs()

    /**
     * The sensitivity factor at which a stateless player with [optimumScale] scores best with the imported profile (smooth fit of a
     * sweep), by the score and round mix the engine runs with, so that the null model is a null for that score.
     */
    private fun sensitivityOptimum(rangeBias: Double, optimumScale: Double, flash: Boolean): Double {
        val grid = geometricGrid(0.75, 1.33, 9)
        val per = 300
        val base = handBase.copy(optimumScale = optimumScale, gainPreference = { imported.curve.multiplier(it) }, rangeBias = rangeBias)
        val kinds = mix ?: AccelScenarios.of(flash)
        val inputs = grid.indices.map { gi ->
            parallelList(per) { r ->
                val round = RefineRound(kinds[r % kinds.size], 77L * 1_009 + gi * 101 + r, imported, gameCm360 / grid[gi])
                playEngine(base, round, gi * per + r)
            }
        }
        val scores = ScoreCalculator(engineWeights).scoreRounds(inputs.flatten(), grid.indices.flatMap { gi -> List(per) { kinds[it % kinds.size].id } })
        var i = 0
        val means = grid.indices.map { (0 until per).mapNotNull { scores[i++] }.average() }
        val all = scores.filterNotNull()
        val mean = all.average()
        lastSweep = grid.zip(means).joinToString(" ") { "%.2f:%.1f".format(it.first, it.second) } + "  (round score sd %.1f)".format(kotlin.math.sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size))
        return SmoothCurve(grid, means, degree = 2).argmax(grid.first(), grid.last())
    }

    /** Scales a player's optimum so that its best sensitivity with the imported profile is 1 (the optimum is proportional to 1 / scale). */
    private fun calibrated(rangeBias: Double, flash: Boolean, log: StringBuilder): Double = optimumScales.getOrPut(rangeBias to flash) {
        var scaleNow = startScale
        repeat(3) { step ->
            val k = sensitivityOptimum(rangeBias, scaleNow, flash)
            log.appendLine("  calibration rangeBias $rangeBias step ${step + 1}: optimumScale %.4f -> best sensitivity factor %.3f".format(scaleNow, k))
            scaleNow *= k
        }
        val check = sensitivityOptimum(rangeBias, scaleNow, flash)
        log.appendLine("  sweep (sensitivity factor : mean score): $lastSweep")
        log.appendLine("  calibration rangeBias $rangeBias final: optimumScale %.4f -> best sensitivity factor %.3f".format(scaleNow, check))
        scaleNow
    }

    private class Run(val results: Map<RefineParameter, RefineResult>, val changed: Boolean, val status: AbStatus?, val benefit: Double?, val probe: Probe)

    private fun runOnce(model: Model, optimumScale: Double, seed: Long, flash: Boolean): Run {
        val probe = Probe(imported.args.inputOffset) { v -> model.ideal.curve.multiplier(v) }
        val player = player(model, optimumScale, probe)
        val engine = RefineEngine(imported, gameCm360, engineWeights, seed, flash = flash, stageRounds = model.stageRounds, scenarioMix = mix)
        var index = 0
        while (engine.phase != RefinePhase.DONE) {
            if (engine.phase == RefinePhase.STAGE_RESULT) engine.continueToNextStage()
            else engine.record(playEngine(player, engine.nextRound(), index++))
        }
        val status = engine.verdict?.status
        val benefit = if (status == AbStatus.ACCEL_BETTER) trueBenefit(model, optimumScale, engine.candidate, seed, flash) else null
        return Run(engine.stageResults.associateBy { it.parameter }, engine.hasCandidate, status, benefit, probe)
    }

    /** Mean score of the candidate minus the control over 40 pairs of the real plan, played by a fresh player of the same model. */
    private fun trueBenefit(model: Model, optimumScale: Double, candidate: RawAccelProfile, seed: Long, flash: Boolean): Double {
        val pairs = 40
        val player = player(model, optimumScale)
        val plan = AccelAbPlan(AccelScenarios.of(flash), 900_000L + seed, pairs)
        val rounds = (0 until 2 * pairs).map { plan.round(it) }
        val inputs = rounds.mapIndexed { i, r ->
            val profile = if (r.condition == AbCondition.ACCEL) candidate else imported
            play(player, RefineRound(r.scenario, r.seed, profile, gameCm360), i)
        }
        val scores = ScoreCalculator(weights).scoreRounds(inputs, rounds.map { it.scenario.id })
        val diffs = (0 until pairs).mapNotNull { p ->
            val a = (2 * p..2 * p + 1).first { rounds[it].condition == AbCondition.ACCEL }
            val c = (2 * p..2 * p + 1).first { rounds[it].condition == AbCondition.PLAIN }
            val sa = scores[a]
            val sc = scores[c]
            if (sa != null && sc != null) sa - sc else null
        }
        return diffs.average()
    }

    @Test
    fun gate() = runGate(flash = true, file = if (realHand) "gate-realhand${mixName?.let { "-$it" } ?: ""}${scoreMode?.let { "-score-$it" } ?: ""}.txt" else "gate-m22c.txt")

    /** The same models without flash rounds (click scenarios only), for the side-by-side with [gate]. */
    @Test
    fun gateNoFlash() = runGate(flash = false, file = if (realHand) "gate-realhand-noflash.txt" else "gate-m22-noflash.txt")

    private fun runGate(flash: Boolean, file: String) {
        val out = StringBuilder()
        out.appendLine("== GATE m22c 'Refine my Raw Accel curve' @ git ${gitShortHash()} | hand ${if (realHand) "fitted to the user's rounds, game geometry" else "default"} | profile: classic gain, offset 10, cap 62.5 in/s -> 1.6, output 600 / device 800 | game ${gameCm360} cm/360 | flash ${if (flash) "on" else "off"} | round mix ${mixName ?: "standard"} | engine score ${scoreMode ?: "standard"} ==")
        out.appendLine("policy: 8 warm-up rounds, stages HIGH_GAIN, TRANSITION of 12 rounds each (the base never changes, no LEVEL stage), continue through all stages, then the A/B test (max 12 pairs, alpha 0.01 per look, >= 6 pairs, >= 2 points). Export = the test says the candidate is better.")
        val started = System.currentTimeMillis()
        val rows = StringBuilder()
        val calibration = StringBuilder()
        for (model in models) {
            val optimumScale = calibrated(model.rangeBias, flash, calibration)
            val runs = parallelList(model.runs) { s -> runOnce(model, optimumScale, 31_000L + s, flash) }
            rows.appendLine(row(model, runs))
            out.appendLine("[${model.name} done, elapsed %d s]".format((System.currentTimeMillis() - started) / 1000))
        }
        out.appendLine()
        out.appendLine("-- calibration of the simulated hand (sensitivity factor at which the imported profile scores best; target 1.00)")
        out.append(calibration)
        out.appendLine()
        out.appendLine("-- results. runs = simulated people; side = the wanted stage's best factor on the wanted side of 1 (CAP: HIGH_GAIN, TRANS: TRANSITION); best(med) = its median best factor; carried = the stage's range excluded 1;")
        out.appendLine("-- any carried = some stage carried a change; exported = the A/B test said better; harmful = exported although the 40-pair mean benefit of the candidate over the imported profile is < 0; benefit = mean of that over exports")
        out.appendLine("model                    runs  best(med)  side    gain carried  trans carried | any carried  tested  better  worse  nodiff | exported  harmful  benefit | above offset  mean imported  mean |d mult|")
        out.append(rows)
        out.appendLine()
        out.appendLine("targets: REFINE_NULL exported <= 5 %; harmful exports = 0 in every model; CAP, TRANS and RANGE: report only (direction and carried rates).")
        write(file, out.toString())
    }

    private fun row(model: Model, runs: List<Run>): String {
        val n = runs.size
        fun pct(count: Int) = "%5.1f%%".format(100.0 * count / n)
        val wanted = model.want?.let { (parameter, factor) -> runs.map { it.results[parameter] } to factor }
        val bests = wanted?.first?.mapNotNull { it?.best }.orEmpty()
        val side = wanted?.let { (results, factor) ->
            results.count { it != null && (if (factor > 1.0) it.best > 1.0 else it.best < 1.0) }
        } ?: -1
        val exported = runs.filter { it.status == AbStatus.ACCEL_BETTER }
        val harmful = exported.count { (it.benefit ?: 0.0) < 0.0 }
        val statuses = runs.mapNotNull { it.status }
        fun carried(p: RefineParameter) = runs.count { it.results[p]?.excludesNeutral == true }
        val calls = runs.sumOf { it.probe.calls }.coerceAtLeast(1L)
        val above = 100.0 * runs.sumOf { it.probe.above } / calls
        val importedMean = runs.sumOf { it.probe.importedSum } / calls
        val diff = runs.sumOf { it.probe.diffAbsSum } / calls
        return "%-24s %4d  %9s  %6s  %11s  %12s | %11s  %6d  %6d  %5d  %6d | %8s  %7d  %7s | %11.1f%%  %13.3f  %14.4f".format(
            model.name, n, if (bests.isEmpty()) "-" else "%.3f".format(median(bests)), if (side < 0) "-" else pct(side), pct(carried(RefineParameter.HIGH_GAIN)),
            pct(carried(RefineParameter.TRANSITION)), pct(runs.count { it.changed }), statuses.size,
            statuses.count { it == AbStatus.ACCEL_BETTER }, statuses.count { it == AbStatus.ACCEL_WORSE },
            statuses.count { it == AbStatus.NO_DIFFERENCE || it == AbStatus.NEED_MORE_ROUNDS }, pct(exported.size), harmful,
            if (exported.isEmpty()) "-" else "%+.2f".format(exported.mapNotNull { it.benefit }.average()), above, importedMean, diff,
        )
    }
}
