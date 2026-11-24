package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.settings.AxisRatio
import dev.veqzo.aimtune.core.accel.settings.AxisRatioAnalysis
import dev.veqzo.aimtune.core.accel.settings.PassiveResult
import dev.veqzo.aimtune.core.accel.settings.ReportSpeedProfile
import dev.veqzo.aimtune.core.accel.settings.ReportSpeedSummary
import dev.veqzo.aimtune.core.accel.settings.RotationAnalysis
import dev.veqzo.aimtune.core.accel.settings.SnapOutcome
import dev.veqzo.aimtune.core.accel.settings.SnapReplay
import dev.veqzo.aimtune.core.accel.settings.firstReaches
import dev.veqzo.aimtune.core.recording.RoundRecording

/**
 * What the passive setting analyses (M22e, docs/ALGORITHMS.md "Passive setting analyses") say about the stored plain rounds.
 * [rounds] is the number of plain rounds used, [reaches] the usable first movements in them.
 */
data class SettingsAnalyses(
    val rounds: Int,
    val reaches: Int,
    val rotation: PassiveResult,
    val yx: PassiveResult,
    val lr: PassiveResult,
    val ud: PassiveResult,
    val snap: SnapOutcome,
    val speed: ReportSpeedSummary?,
) {
    /** The result of a passive analysis row; null for rows that have none (they are info pages). */
    fun result(setting: RawAccelSetting): PassiveResult? = when (setting) {
        RawAccelSetting.ROTATION -> rotation
        RawAccelSetting.Y_X -> yx
        RawAccelSetting.L_R -> lr
        RawAccelSetting.U_D -> ud
        else -> null
    }

    companion object {
        /** Runs every analysis on [recordings]; rounds played with a curve are skipped by the analyses themselves. */
        fun compute(recordings: List<RoundRecording>): SettingsAnalyses {
            val plain = recordings.filter { it.accel == null }
            val reaches = firstReaches(plain)
            return SettingsAnalyses(
                rounds = plain.size,
                reaches = reaches.size,
                rotation = RotationAnalysis.analyze(reaches),
                yx = AxisRatioAnalysis.analyze(reaches, AxisRatio.Y_X),
                lr = AxisRatioAnalysis.analyze(reaches, AxisRatio.L_R),
                ud = AxisRatioAnalysis.analyze(reaches, AxisRatio.U_D),
                snap = SnapReplay.analyze(plain),
                speed = ReportSpeedProfile.summarize(plain),
            )
        }
    }
}
