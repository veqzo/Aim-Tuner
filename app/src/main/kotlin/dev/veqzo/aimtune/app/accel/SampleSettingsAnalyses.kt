package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.settings.PassiveResult
import dev.veqzo.aimtune.core.accel.settings.ReportSpeedSummary
import dev.veqzo.aimtune.core.accel.settings.SnapOutcome
import dev.veqzo.aimtune.core.accel.settings.SnapRow

/**
 * Made-up analyses for the screenshot tour only: a rotation and a Y/X recommendation, one setting without change, one without
 * enough data, and a snap replay. Never user data, and nothing here is a finding about a real hand.
 */
fun sampleSettingsAnalyses(): SettingsAnalyses {
    fun row(scenario: String, snap: Double, change: Double, n: Int) =
        SnapRow(scenario, snap, n, 6.0, 6.0 * (1 + change), change, change - 0.08, change + 0.08, passes = false)
    return SettingsAnalyses(
        rounds = 14,
        reaches = 168,
        rotation = PassiveResult.Recommend(-1.5, -2.3, -0.8, 112),
        yx = PassiveResult.Recommend(1.08, 1.03, 1.14, 96),
        lr = PassiveResult.NoChange(0.98, 0.93, 1.03, 80),
        ud = PassiveResult.NotEnoughData(8, 30),
        snap = SnapOutcome(
            PassiveResult.NoChange(0.0, 0.0, 0.0, 112),
            listOf(row("flick", 2.0, 0.01, 112), row("flick", 5.0, 0.12, 112), row("flick", 10.0, 0.46, 112), row("switching", 5.0, 0.09, 64), row("switching", 10.0, 0.31, 64)),
        ),
        speed = ReportSpeedSummary(
            reports = 213_000,
            speedPercentiles = mapOf(50 to 2.5, 90 to 11.3, 95 to 15.2, 99 to 23.9),
            intervalMsPercentiles = mapOf(50 to 1.0, 90 to 1.0, 95 to 3.0, 99 to 4.0),
            clampedShare = 0.004,
        ),
    )
}
