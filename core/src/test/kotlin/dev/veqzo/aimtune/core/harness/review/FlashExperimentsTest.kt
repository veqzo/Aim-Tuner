package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.scenario.ScenarioSchedule
import dev.veqzo.aimtune.core.score.FlashImportance
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.AccelAnalysis
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.accel.AccelCurve
import dev.veqzo.aimtune.core.accel.NaturalCurve
import dev.veqzo.aimtune.core.harness.playBlindTest
import dev.veqzo.aimtune.core.units.SearchRange
import kotlin.math.abs
import dev.veqzo.aimtune.core.accel.AccelReason
import dev.veqzo.aimtune.core.accel.fitAccel
import dev.veqzo.aimtune.core.accel.movementSamples
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.harness.geometricGrid
import dev.veqzo.aimtune.core.harness.parabolicOptimum
import dev.veqzo.aimtune.core.metrics.RoundMetrics
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.scenario.ScenarioKind
import dev.veqzo.aimtune.core.scenario.TrainerGeometry
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.ScoreCalculator
import dev.veqzo.aimtune.core.score.ScoreMetric
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.exp
import kotlin.test.Test

/**
 * Flash turn-away experiments (docs/ALGORITHMS.md "Flash turn-away"). Gated like the other review
 * experiments: set AIMTUNE_REVIEW; AIMTUNE_RUNS scales the number of simulated rounds or players per row.
 */
@EnabledIfEnvironmentVariable(named = "AIMTUNE_REVIEW", matches = ".+")
class FlashExperimentsTest {
    private val runs = (System.getenv("AIMTUNE_RUNS") ?: "30").toInt()
    private val geometry = TrainerGeometry.DEFAULT

    private fun round(kind: ScenarioKind, player: SimulatedPlayer, cm360: Double, seed: Long, offset: Long = 0L): RoundMetrics =
        computeRoundMetrics(HeadlessRound.of(kind, player, TEST_DPI, cm360, seed, 45, offset, geometry = geometry))

    /**
     * Flash rounds across the sensitivity range: what each metric does on its own and where the combined
     * score peaks. A score that rewards the highest sensitivity would peak at the edge of the grid.
     */
    @Test
    fun flashSweep() {
        val out = StringBuilder()
        val grid = geometricGrid(20.0, 160.0, 9)
        for (optimum in listOf(35.0, 50.0, 70.0)) {
            val player = SimulatedPlayer.forHeadSizedTargets(optimum)
            val metrics = grid.mapIndexed { gi, cm -> parallelList(runs) { r -> round(ScenarioKind.FLASH, player, cm, 9_000L + gi * 1_000 + r, r * 45_000_000_000L) } }
            val inputs = metrics.map { group -> group.map { it.scoreInputs() } }
            out.appendLine("== player optimum $optimum cm/360 (head-sized targets), $runs flash rounds per sensitivity ==")
            out.appendLine("cm/360   dodge   turn ms   first-shot acc   time to hit ms   flashes/round   score(all)   score(dodge only)   score(return only)")
            val all = meanScoresOf(inputs, MetricWeights(mapOf(ScoreMetric.FLASH_DODGE to 15.0, ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0)))
            val dodgeOnly = meanScoresOf(inputs, MetricWeights(mapOf(ScoreMetric.FLASH_DODGE to 1.0)))
            val returnOnly = meanScoresOf(inputs, MetricWeights(mapOf(ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0)))
            for (gi in grid.indices) {
                val g = metrics[gi]
                out.appendLine(
                    "%6.1f   %4.0f%%   %7.0f   %13.0f%%   %14.0f   %13.1f   %10.1f   %17.1f   %18.1f".format(
                        grid[gi], 100 * g.mapNotNull { it.flashDodgeRate }.average(), g.mapNotNull { it.flashMeanTurnMs }.average(),
                        100 * g.mapNotNull { it.firstShotAccuracy }.average(), g.mapNotNull { it.meanTimeToHitMs }.average(),
                        g.map { it.hits }.average(), all[gi], dodgeOnly[gi], returnOnly[gi],
                    ),
                )
            }
            val often = meanScoresOf(inputs, MetricWeights(mapOf(ScoreMetric.FLASH_DODGE to 35.0, ScoreMetric.ACCURACY to 40.0, ScoreMetric.TIME_TO_HIT to 30.0)))
            out.appendLine(
                "optimum of the combined score: sometimes (dodge weight 15) %.1f, often (35) %.1f; dodge only %.1f, return only %.1f cm/360 (grid %.0f-%.0f)".format(
                    parabolicOptimum(grid, all), parabolicOptimum(grid, often), parabolicOptimum(grid, dodgeOnly), parabolicOptimum(grid, returnOnly), grid.first(), grid.last(),
                ),
            )
            out.appendLine()
        }
        write("flash-sweep.txt", out.toString())
    }

    private val mixes = linkedMapOf(
        "flick only" to listOf(ScenarioKind.FLICK),
        "click mix (flick, switching, micro)" to listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO),
        "click mix + flash (25 %)" to listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.FLASH, ScenarioKind.MICRO),
        "flick + flash (50 %, the default scenarios)" to listOf(ScenarioKind.FLICK, ScenarioKind.FLASH),
        "flash only" to listOf(ScenarioKind.FLASH),
    )

    /** A simulated hand and the sensitivity it plays at; the default hand moves slowly with head-sized targets (speeds of 3-5 in/s). */
    private class Variant(val name: String, val base: SimulatedPlayer, val cm360: Double)

    private val variants = listOf(
        Variant("default hand, 50 cm/360", SimulatedPlayer.forHeadSizedTargets(50.0), 50.0),
        Variant("fast hand, 50 cm/360", SimulatedPlayer.forHeadSizedTargets(50.0).copy(fittsSlopeMs = 55.0, maxHandSpeedCmPerS = 60.0), 50.0),
        Variant("default hand, low sensitivity 90 cm/360", SimulatedPlayer.forHeadSizedTargets(90.0), 90.0),
    )

    private fun gainPlayer(change: Double, base: SimulatedPlayer = SimulatedPlayer.forHeadSizedTargets(50.0)) =
        base.copy(gainPreference = { v -> 1.0 + change * (1.0 - exp(-v / 6.0)) })

    /**
     * The analysis of the plain rounds (docs/ALGORITHMS.md "Acceleration") with and without flash rounds, at 8, 15
     * and 30 rounds: how often a curve is offered, in the right direction, and how fast the used movements were.
     */
    @Test
    fun curveDetection() {
        val out = StringBuilder()
        for (variant in variants) {
            out.appendLine("== Curve offered by the error analysis, $runs simulated players per cell: ${variant.name} ==")
            for (rounds in listOf(8, 15, 30)) {
                out.appendLine("-- $rounds rounds --")
                for ((label, kinds) in mixes) {
                    val cells = listOf(0.0, -0.15, 0.15, -0.25, 0.25, 0.5).map { change ->
                        val player = gainPlayer(change, variant.base)
                        val results = parallelList(runs) { run ->
                            val metrics = (0 until rounds).map { r -> round(kinds[r % kinds.size], player, variant.cm360, 80_000L + run * 100 + r) }
                            val analysis = AccelAnalysis(movementSamples(metrics, TEST_DPI))
                            val fit = fitAccel(analysis)
                            val offered = fit.recommended
                            Triple(offered?.let { (it.curve.multiplier(30.0) > it.curve.multiplier(3.0)) == (change > 0) }, fit.reasonCode, analysis.speedRange)
                        }
                        val judged = results.count { it.second != AccelReason.NOT_ENOUGH_MOVEMENTS && it.second != AccelReason.NARROW_SPEED_RANGE }
                        "%+4.0f%%: offered %2d (right %2d) judged %2d".format(change * 100, results.count { it.first != null }, results.count { it.first == true }, judged)
                    }
                    out.appendLine("  %-46s %s".format(label, cells.joinToString(" | ")))
                }
                write("flash-curve-detection.txt", out.toString())
            }
        }
    }

    /** The wanted gain per speed pooled over many players, per scenario, for a falling and a flat player. */
    @Test
    fun wantedGainBySpeed() {
        val out = StringBuilder()
        for (change in listOf(0.0, -0.25)) {
            val player = gainPlayer(change)
            for (kind in listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO, ScenarioKind.FLASH)) {
                val samples = parallelList(runs) { run -> (0 until 10).flatMap { r -> movementSamples(listOf(round(kind, player, 50.0, 90_000L + run * 100 + r)), TEST_DPI) } }.flatten()
                val analysis = AccelAnalysis(samples)
                out.appendLine("-- true change %+.0f%%, %s: %d movements, %d used, mean error ratio %+.3f, median %+.3f --".format(
                    change * 100, kind.id, samples.size, analysis.used.size, analysis.used.map { it.errorRatio }.average(), analysis.used.map { it.errorRatio }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }))
                for (p in analysis.points) out.appendLine("     speed %5.1f in/s: wanted gain %+.3f (se %.3f, n=%d)".format(p.speed, p.logGain, p.standardError, p.count))
            }
        }
        write("flash-wanted-gain.txt", out.toString())
    }

    /** False offers for players without any gain preference, with many more players than the power table. */
    @Test
    fun nullWithFlash() {
        val out = StringBuilder()
        val n = maxOf(runs, 150)
        // The default hand never reaches the speed range the analysis needs (it is rejected before any fit), so it cannot give false offers.
        for (variant in variants.drop(1)) for (rounds in listOf(15, 30)) for ((label, kinds) in mixes.filterKeys { it != "flick only" && it != "flash only" }) {
            val player = gainPlayer(0.0, variant.base)
            val results = parallelList(n) { run ->
                val metrics = (0 until rounds).map { r -> round(kinds[r % kinds.size], player, variant.cm360, 120_000L + run * 100 + r) }
                val fit = fitAccel(AccelAnalysis(movementSamples(metrics, TEST_DPI)))
                Triple(fit.reasonCode, fit.fits.maxOfOrNull { it.bicGain }, fit.recommended != null)
            }
            val gains = results.mapNotNull { it.second }.sorted()
            val reasons = AccelReason.entries.map { reason -> reason to results.count { it.first == reason } }.filter { it.second > 0 }.joinToString { "${it.first} ${it.second}" }
            out.appendLine(
                "  %-40s %2d rounds, %-46s false offers %d of %d | evaluated %3d, BIC gain p50 %.1f p95 %.1f p99 %.1f max %.1f | %s".format(
                    variant.name, rounds, label, results.count { it.third }, n, gains.size,
                    gains.getOrElse(gains.size / 2) { Double.NaN }, gains.getOrElse((gains.size * 0.95).toInt()) { Double.NaN }, gains.getOrElse((gains.size * 0.99).toInt()) { Double.NaN }, gains.lastOrNull() ?: Double.NaN, reasons,
                ),
            )
            write("flash-null.txt", out.toString())
        }
    }

    /** The blind strength search (docs/ALGORITHMS.md "Blind strength search") with and without flash rounds, realistic head-sized targets. */
    @Test
    fun strengthSearchWithFlash() {
        val n = maxOf(runs / 2, 12)
        val rounds = (System.getenv("AIMTUNE_ROUNDS") ?: "24").toInt()
        val out = StringBuilder("== Blind strength search, $rounds rounds, $n players per cell ==\n")
        val range = SearchRange(70.0, 145.0)
        fun curveOf(pseudo: Double): AccelCurve? = (pseudo / 100.0).let { r -> if (abs(r - 1.0) < 0.02) null else NaturalCurve(0.05, r) }
        val searchMixes = mixes.filterKeys { it in listOf("click mix (flick, switching, micro)", "click mix + flash (25 %)", "flick + flash (50 %, the default scenarios)") }
        for (variant in variants.take(2)) {
            out.appendLine("-- ${variant.name} --")
            for ((label, kinds) in searchMixes) {
                val cells = listOf(0.0, 0.15, 0.25, -0.15, -0.25).map { change ->
                    val player = gainPlayer(change, variant.base)
                    val rows = parallelList(n) { s ->
                        val test = playBlindTest(player, range, rounds, 700L + s, scenarioFor = { kinds[it % kinds.size] }, accelOf = ::curveOf, playedCm360 = variant.cm360, geometry = geometry)
                        val result = checkNotNull(test.result)
                        doubleArrayOf(result.optimumCm360 / 100.0, if (result.intervalLowCm360 > 100.0 || result.intervalHighCm360 < 100.0) 1.0 else 0.0, result.confidencePercent.toDouble())
                    }
                    val strengths = rows.map { it[0] }.sorted()
                    "%+4.0f%%: x%.2f (p25 %.2f p75 %.2f) excl %2d/%d conf %2.0f%%".format(change * 100, strengths[n / 2], strengths[n / 4], strengths[(n * 3) / 4], rows.count { it[1] > 0.5 }, n, rows.map { it[2] }.average())
                }
                out.appendLine("  %-46s %s".format(label, cells.joinToString(" | ")))
                write("flash-strength-search.txt", out.toString())
            }
        }
    }

    private val AIMER_OFFSET = 7L

    private val abMixes = linkedMapOf(
        "click mix (flick, switching, micro)" to listOf(ScenarioKind.FLICK, ScenarioKind.SWITCHING, ScenarioKind.MICRO),
        "flash in half the pairs" to listOf(ScenarioKind.FLICK, ScenarioKind.FLASH, ScenarioKind.SWITCHING, ScenarioKind.FLASH, ScenarioKind.MICRO, ScenarioKind.FLASH),
    )

    private fun abVerdict(player: SimulatedPlayer, cm360: Double, curve: AccelCurve?, kinds: List<ScenarioKind>, seed: Long): AbStatus {
        val plan = AccelAbPlan(kinds, seed)
        val outcomes = mutableListOf<AbOutcome>()
        var verdict = evaluateAb(emptyList(), MetricWeights.DEFAULT)
        for (index in 0 until 2 * plan.maxPairs) {
            val round = plan.round(index)
            val accelerated = round.condition == AbCondition.ACCEL
            // The targets of a pair are the same, the hand is not: the aimer of the accelerated round has its own noise.
            val recording = HeadlessRound.run(
                player, TEST_DPI, cm360, round.seed + if (accelerated) AIMER_OFFSET else 0L, 45, index * 45L * 1_000_000_000L,
                round.scenario.create(45L * 1_000_000_000L, round.seed, geometry), if (accelerated) curve else null,
            )
            outcomes += AbOutcome(round.pair, round.condition, computeRoundMetrics(recording).scoreInputs())
            if (index % 2 == 1) {
                verdict = evaluateAb(outcomes, MetricWeights.DEFAULT)
                if (verdict.status != AbStatus.NEED_MORE_ROUNDS) break
            }
        }
        return verdict.status
    }

    /** The blind A/B test with and without flash pairs: false verdicts when both arms are the same, and the verdict on a curve that really helps. */
    @Test
    fun blindTestWithFlash() {
        val out = StringBuilder()
        val n = maxOf(runs * 4, 120)
        for (variant in variants.take(2)) for ((label, kinds) in abMixes) {
            val flat = gainPlayer(0.0, variant.base)
            val nulls = parallelList(n) { abVerdict(flat, variant.cm360, null, kinds, 5_000L + it) }
            fun share(list: List<AbStatus>, status: AbStatus) = 100.0 * list.count { it == status } / list.size
            out.appendLine(
                "  %-40s %-38s same arms (n=%d): better %.1f%%, worse %.1f%%, no difference %.0f%%, undecided %.0f%%".format(
                    variant.name, label, n, share(nulls, AbStatus.ACCEL_BETTER), share(nulls, AbStatus.ACCEL_WORSE), share(nulls, AbStatus.NO_DIFFERENCE), share(nulls, AbStatus.NEED_MORE_ROUNDS),
                ),
            )
            for (change in listOf(-0.25, 0.25)) {
                val player = gainPlayer(change, variant.base)
                val curve = NaturalCurve(0.05, 1.0 + change)
                val real = parallelList(minOf(n, 60)) { abVerdict(player, variant.cm360, curve, kinds, 9_000L + it) }
                out.appendLine(
                    "      player wants %+.0f%%, curve of that strength: better %.0f%%, worse %.0f%%, no difference %.0f%%, undecided %.0f%% (n=%d)".format(
                        change * 100, share(real, AbStatus.ACCEL_BETTER), share(real, AbStatus.ACCEL_WORSE), share(real, AbStatus.NO_DIFFERENCE), share(real, AbStatus.NEED_MORE_ROUNDS), real.size,
                    ),
                )
            }
            write("flash-blind-test.txt", out.toString())
        }
    }

    /** The sensitivity finder with flicks and flash rounds in the mix, against the optimum of the blended score curves. */
    @Test
    fun sensitivityWithFlash() {
        val out = StringBuilder()
        val player = SimulatedPlayer.forHeadSizedTargets(50.0)
        val range = SearchRange(20.0, 140.0)
        val grid = geometricGrid(20.0, 140.0, 9)
        val enabled = setOf(ScenarioKind.FLICK, ScenarioKind.FLASH)
        val n = maxOf(runs / 2, 12)
        val perKind = enabled.associateWith { kind ->
            grid.mapIndexed { gi, cm -> parallelList(24) { r -> round(kind, player, cm, 150_000L + gi * 1_000 + r, r * 45_000_000_000L).scoreInputs() } }
        }
        for (importance in listOf(FlashImportance.NEVER, FlashImportance.SOMETIMES, FlashImportance.OFTEN)) {
            val weights = MetricWeights(MetricWeights.DEFAULT.values + (ScoreMetric.FLASH_DODGE to importance.weight))
            val schedule = ScenarioSchedule(enabled, weights)
            val blend = grid.indices.map { gi -> schedule.shares.entries.sumOf { (kind, share) -> share * meanScoresOf(perKind.getValue(kind), weights)[gi] } }
            val truth = parabolicOptimum(grid, blend)
            for (rounds in listOf(15, 30)) {
                val tests = parallelList(n) { s -> playBlindTest(player, range, rounds, 800L + s, scenarioFor = schedule::kindFor, weights = weights, geometry = geometry) }
                val errors = tests.map { errorFactorOf(checkNotNull(it.result).optimumCm360, truth) }.sorted()
                val signed = tests.map { checkNotNull(it.result).optimumCm360 / truth }.sorted()
                out.appendLine(
                    "  %-9s flash share %2.0f%%: blended truth %.1f cm/360 | %2d rounds: median found %.1f (x%.2f of truth), error factor median %.2f p90 %.2f (n=%d)".format(
                        importance.name.lowercase(), 100 * (schedule.shares[ScenarioKind.FLASH] ?: 0.0), truth, rounds, signed[n / 2] * truth, signed[n / 2], errors[n / 2], errors[(n * 9) / 10], n,
                    ),
                )
                write("flash-sensitivity.txt", out.toString())
            }
        }
    }

    private fun meanScoresOf(inputs: List<List<Map<ScoreMetric, Double>>>, weights: MetricWeights): List<Double> {
        val scores = ScoreCalculator(weights).scoreRounds(inputs.flatten())
        var i = 0
        return inputs.map { group -> group.indices.map { scores[i++] }.filterNotNull().average() }
    }
}
