package dev.veqzo.aimtune.core.harness.review

import dev.veqzo.aimtune.core.accel.AbCondition
import dev.veqzo.aimtune.core.accel.AbOutcome
import dev.veqzo.aimtune.core.accel.AbStatus
import dev.veqzo.aimtune.core.accel.AccelAbPlan
import dev.veqzo.aimtune.core.accel.evaluateAb
import dev.veqzo.aimtune.core.harness.TEST_DPI
import dev.veqzo.aimtune.core.metrics.computeRoundMetrics
import dev.veqzo.aimtune.core.score.MetricWeights
import dev.veqzo.aimtune.core.score.scoreInputs
import dev.veqzo.aimtune.core.sim.HeadlessRound
import dev.veqzo.aimtune.core.sim.SimulatedPlayer

/**
 * Section N, A/B null calibration: both arms plain, 12 pairs of the real plan, looked at after every pair. The rounds are
 * played once per run and judged twice: with per-scenario baselines (as the app does) and pooled. The pair members get
 * different seeds, otherwise identical arms would give identical rounds.
 */
fun gateNull(cfg: GateConfig, out: StringBuilder) {
    out.appendLine("-- N A/B null: both arms plain, 12 pairs, FLICK/SWITCHING/MICRO in turn; verdict per run grouped by scenario (app) and pooled")
    out.appendLine("model      runs | grouped: better worse nodiff | pooled: better worse nodiff")
    val pairs = 12
    for (model in cfg.models.filter { it.name == "DEFAULT" || it.name == "ADAPTIVE" }) {
        val n = cfg.n(if (model.name == "DEFAULT") 500 else 200)
        val runs = parallelList(n) { s ->
            val player = model.apply(SimulatedPlayer())
            val plan = AccelAbPlan(CLICK_KINDS, 5_000L + s, pairs)
            val all = (0 until 2 * pairs).map { index ->
                val round = plan.round(index)
                val seed = round.seed + if (round.condition == AbCondition.ACCEL) 5_000 else 0
                val rec = HeadlessRound.of(
                    round.scenario, player, TEST_DPI, CALIBRATION_BASE_CM360, seed, ROUND_SECONDS,
                    sessionOffsetNanos = index * ROUND_SECONDS * NANOS_PER_SECOND,
                )
                AbOutcome(round.pair, round.condition, computeRoundMetrics(rec).scoreInputs(), round.scenario.id)
            }
            fun verdict(grouped: Boolean): AbStatus {
                for (p in 1..pairs) {
                    val v = evaluateAb(all.take(2 * p), MetricWeights.DEFAULT, groupByScenario = grouped)
                    if (v.status != AbStatus.NEED_MORE_ROUNDS) return v.status
                }
                return AbStatus.NO_DIFFERENCE
            }
            verdict(true) to verdict(false)
        }
        val g = runs.map { it.first }
        val p = runs.map { it.second }
        out.appendLine(
            "%-10s %4d |          %3d    %3d    %3d |         %3d    %3d    %3d".format(
                model.name, n,
                g.count { it == AbStatus.ACCEL_BETTER }, g.count { it == AbStatus.ACCEL_WORSE }, g.count { it == AbStatus.NO_DIFFERENCE },
                p.count { it == AbStatus.ACCEL_BETTER }, p.count { it == AbStatus.ACCEL_WORSE }, p.count { it == AbStatus.NO_DIFFERENCE },
            ),
        )
    }
}

/**
 * Section V, A/B variants: the same rounds judged under each of [abVariants] (alpha per look, grouped or pooled).
 * Part 1: null calibration with 1000 DEFAULT runs. Part 2: power at 15 calibration rounds per model and preference.
 */
fun gateVariants(cfg: GateConfig, out: StringBuilder) {
    val default = cfg.models.first { it.name == "DEFAULT" }
    val n = cfg.n(1000)
    out.appendLine("-- V1 A/B null by variant: DEFAULT, identical arms, 12 pairs, $n runs (better / worse / no difference)")
    val outcomes = parallelList(n) { s -> playAbRounds(default.apply(SimulatedPlayer()), CALIBRATION_BASE_CM360, null, 1.0, 5_000L + s) }
    for (v in abVariants) {
        val st = outcomes.map { judgeAb(it, v.alpha, v.grouped) }
        out.appendLine("%-12s %4d / %4d / %4d".format(v.label, st.count { it == AbStatus.ACCEL_BETTER }, st.count { it == AbStatus.ACCEL_WORSE }, st.count { it == AbStatus.NO_DIFFERENCE }))
    }
    val seeds = cfg.n(30)
    out.appendLine("-- V2 power at 15 calibration rounds, $seeds seeds per cell: offered, then A/B better/worse/nodiff of the offered curves per variant")
    out.appendLine("model      pref  offered | " + abVariants.joinToString(" | ") { "%-13s".format(it.label) })
    for (model in cfg.models) for (change in listOf(-0.30, -0.15, 0.15, 0.30)) {
        val runs = parallelList(seeds) { s ->
            val player = model.apply(gainPlayer(change))
            val fit = cfg.curve.recommend(cfg.curve.playCalibration(player, 60_000L + s, 15), TEST_DPI)
            fit?.let { playAbRounds(player, CALIBRATION_BASE_CM360, it.curve, it.sensitivityFactor, 800L + s) }
        }.filterNotNull()
        val cols = abVariants.map { v ->
            val st = runs.map { judgeAb(it, v.alpha, v.grouped) }
            "%3d/%3d/%3d  ".format(st.count { it == AbStatus.ACCEL_BETTER }, st.count { it == AbStatus.ACCEL_WORSE }, st.count { it == AbStatus.NO_DIFFERENCE })
        }
        out.appendLine("%-10s %+4.0f%% %5d/%-2d | %s".format(model.name, change * 100, runs.size, seeds, cols.joinToString(" | ")))
    }
}
