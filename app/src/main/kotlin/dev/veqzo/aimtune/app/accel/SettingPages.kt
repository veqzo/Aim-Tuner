package dev.veqzo.aimtune.app.accel

import dev.veqzo.aimtune.core.accel.RawAccelProfile
import dev.veqzo.aimtune.core.accel.settings.PassiveResult
import dev.veqzo.aimtune.core.accel.settings.ReportSpeedProfile
import dev.veqzo.aimtune.core.accel.settings.ReportSpeedSummary
import dev.veqzo.aimtune.core.accel.settings.SnapRow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A text of the UI by key; the arguments replace `{0}`, `{1}`, ... */
typealias Translate = (String, Array<out Any?>) -> String

/** A line of the hub's "Other settings" list. */
data class SettingRow(val setting: RawAccelSetting, val name: String, val badge: String, val state: String)

/** Everything the generic setting screen shows; the texts are already in the player's language. */
data class SettingPage(
    val setting: RawAccelSetting,
    val name: String,
    val badge: String,
    val what: String,
    val formula: String,
    /** The value in the imported profile, or a note that there is none. */
    val current: String,
    val recommended: String,
    val why: String,
    /** The numbers behind the recommendation (analysis) or the measured numbers (info); empty while the data are still being computed. */
    val result: List<String>,
    /** Why the app does not measure the setting; null for analysis rows. */
    val notMeasured: String?,
    /** The recommended value when the player may add it to the patched copy; null otherwise. */
    val exportable: Double?,
)

/**
 * The texts of the Raw Accel hub: one row and one page per [RawAccelSetting], from the passive [analyses] (null while they are
 * computed) and the player's imported [profile] (null without one). Pure, so a test can read every text.
 */
class SettingPages(private val tr: Translate, private val analyses: SettingsAnalyses?, private val profile: RawAccelProfile?) {
    private fun t(key: String, vararg args: Any?) = tr(key, args)

    private fun name(s: RawAccelSetting) = t("hub.set.${s.id}.name")

    private fun badge(s: RawAccelSetting) = t(if (s.kind == SettingKind.ANALYSIS) "hub.badge.analysis" else "hub.badge.info")

    fun rows(): List<SettingRow> = RawAccelSetting.entries.map { SettingRow(it, name(it), badge(it), state(it)) }

    /** The one line under the name in the hub. */
    fun state(s: RawAccelSetting): String {
        val a = analyses ?: return t("hub.state.computing")
        val result = a.result(s)
        return when (s) {
            RawAccelSetting.ROTATION, RawAccelSetting.Y_X, RawAccelSetting.L_R -> analysisState(checkNotNull(result), s)
            RawAccelSetting.U_D -> when (result) {
                is PassiveResult.NotEnoughData -> notEnough(result)
                else -> t("hub.state.measured", number(s, estimateOf(result)))
            }
            RawAccelSetting.SNAP -> snapState(a)
            RawAccelSetting.SPEED_CAP -> a.speed
                ?.let { t("hub.state.speed_cap", t("hub.set.speed_cap.recommended"), one(it.speedPercentiles.getValue(P99))) }
                ?: t("hub.state.recommended", t("hub.set.speed_cap.recommended"))
            else -> t("hub.state.recommended", t("hub.set.${s.id}.recommended"))
        }
    }

    private fun snapState(a: SettingsAnalyses): String {
        val recommended = t("hub.set.snap.recommended")
        val reaches = a.snap.rows.firstOrNull()?.n
        return if (a.snap.result is PassiveResult.NotEnoughData || reaches == null) t("hub.state.recommended", recommended) else t("hub.state.rec_replay", recommended, reaches)
    }

    private fun analysisState(result: PassiveResult, s: RawAccelSetting): String = when (result) {
        is PassiveResult.Recommend -> t("hub.state.recommend", number(s, result.value), result.n)
        is PassiveResult.NoChange -> t("hub.state.no_change", result.n)
        is PassiveResult.NotEnoughData -> notEnough(result)
    }

    private fun notEnough(result: PassiveResult.NotEnoughData) = t("hub.result.not_enough", result.n, result.needed)

    fun page(s: RawAccelSetting): SettingPage {
        val result = analyses?.result(s)
        val exportable = (result as? PassiveResult.Recommend)?.takeIf { s.passive && profile != null }?.value
        return SettingPage(
            s, name(s), badge(s), t("hub.set.${s.id}.what"), t("hub.set.${s.id}.formula"), current(s), recommended(s, result),
            t("hub.set.${s.id}.why"), resultLines(s, result),
            notMeasured = if (s.kind == SettingKind.INFO) t("hub.set.${s.id}.not_measured") else null,
            exportable = exportable,
        )
    }

    private fun recommended(s: RawAccelSetting, result: PassiveResult?): String = when {
        s.kind == SettingKind.INFO -> t("hub.set.${s.id}.recommended")
        result is PassiveResult.Recommend -> number(s, result.value)
        result is PassiveResult.NoChange -> t("hub.rec.neutral", neutral(s))
        result is PassiveResult.NotEnoughData -> t("hub.rec.pending", neutral(s))
        else -> t("hub.state.computing")
    }

    private fun resultLines(s: RawAccelSetting, result: PassiveResult?): List<String> = when (s) {
        RawAccelSetting.SNAP -> analyses?.snap?.let { snapLines(it.result, it.rows) }.orEmpty()
        RawAccelSetting.SPEED_CAP -> analyses?.let { speedLines(it.speed) }.orEmpty()
        RawAccelSetting.POLLING -> analyses?.speed?.let(::intervalLines).orEmpty()
        else -> if (result == null) emptyList() else listOf(passiveLine(s, result))
    }

    private fun passiveLine(s: RawAccelSetting, result: PassiveResult): String = when (result) {
        is PassiveResult.Recommend -> t("hub.result.recommend", number(s, result.value), number(s, result.low), number(s, result.high), result.n)
        is PassiveResult.NoChange -> t("hub.result.no_change", number(s, result.estimate), number(s, result.low), number(s, result.high), result.n)
        is PassiveResult.NotEnoughData -> notEnough(result)
    }

    private fun snapLines(result: PassiveResult, rows: List<SnapRow>): List<String> {
        if (result is PassiveResult.NotEnoughData) return listOf(notEnough(result))
        return listOf(t("hub.result.snap_head")) + rows.map {
            t("hub.result.snap_row", t("scenario.${it.scenarioId}"), one(it.snap), signedPercent(it.relativeChange), it.n)
        }
    }

    private fun speedLines(speed: ReportSpeedSummary?): List<String> {
        if (speed == null) return listOf(t("hub.result.speed_missing", ReportSpeedProfile.MIN_REPORTS))
        val shares = ReportSpeedProfile.PERCENTILES.joinToString("   ") { "p$it ${one(speed.speedPercentiles.getValue(it))}" }
        return listOf(t("hub.result.speed", speed.reports), shares)
    }

    private fun intervalLines(speed: ReportSpeedSummary) = listOf(
        t("hub.result.interval", one(speed.intervalMsPercentiles.getValue(P50)), one(speed.intervalMsPercentiles.getValue(P95)), (speed.clampedShare * PERCENT).roundToInt()),
    )

    /** The value in the player's file, formatted like the recommendation; a note when no file was imported. */
    private fun current(s: RawAccelSetting): String {
        val p = profile ?: return t("hub.current.none")
        return when (s) {
            RawAccelSetting.ROTATION -> degrees(p.rotationDegrees)
            RawAccelSetting.Y_X -> two(p.yxRatio)
            RawAccelSetting.L_R -> two(p.lrRatio)
            RawAccelSetting.U_D -> two(p.udRatio)
            RawAccelSetting.SNAP -> if (p.snapDegrees == 0.0) t("hub.value.off") else degrees(p.snapDegrees)
            RawAccelSetting.SPEED_CAP -> if (p.speedCap == 0.0) t("hub.value.off") else "${one(p.speedCap)} in/s"
            RawAccelSetting.SMOOTHING -> t("hub.value.smoothing", one(p.speed.inputHalfLife), one(p.speed.scaleHalfLife), one(p.speed.outputHalfLife))
            RawAccelSetting.STRETCH -> t("hub.value.stretch", two(p.domain.x), two(p.domain.y), two(p.range.x), two(p.range.y))
            RawAccelSetting.COMPONENT -> t(if (p.speed.whole) "hub.value.whole" else "hub.value.by_component", one(p.speed.lpNorm))
            RawAccelSetting.POLLING ->
                if (p.device.pollingRate == 0) t("hub.value.automatic") else t("hub.value.polling", p.device.pollingRate, t(if (p.device.pollTimeLock) "hub.value.yes" else "hub.value.no"))
        }
    }

    private fun neutral(s: RawAccelSetting) = if (s == RawAccelSetting.ROTATION) degrees(0.0) else two(1.0)

    private fun estimateOf(result: PassiveResult?) = when (result) {
        is PassiveResult.Recommend -> result.value
        is PassiveResult.NoChange -> result.estimate
        else -> 1.0
    }

    private fun number(s: RawAccelSetting, v: Double) = if (s == RawAccelSetting.ROTATION) degrees(v) else two(v)

    private fun degrees(v: Double): String {
        val text = "%.2f".format(Locale.ROOT, if (abs(v) < ZERO_DEGREES) 0.0 else v).trimEnd('0').trimEnd('.')
        return "${text.ifEmpty { "0" }}°"
    }

    private fun signedPercent(v: Double) = "%+d %%".format(Locale.ROOT, (v * PERCENT).roundToInt())

    private fun one(v: Double) = "%.1f".format(Locale.ROOT, v)

    private fun two(v: Double) = "%.2f".format(Locale.ROOT, v)

    private companion object {
        const val ZERO_DEGREES = 0.005
        const val PERCENT = 100
        const val P50 = 50
        const val P95 = 95
        const val P99 = 99
    }
}
