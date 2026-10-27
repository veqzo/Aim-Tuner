package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.core.accel.AccelFit
import de.yuuto.aimtune.core.accel.AccelSpec
import de.yuuto.aimtune.core.accel.CurveStrength
import de.yuuto.aimtune.core.accel.NaturalCurve
import de.yuuto.aimtune.core.accel.NoAccel
import de.yuuto.aimtune.core.accel.strengthAccel
import de.yuuto.aimtune.core.scenario.ScenarioKind
import de.yuuto.aimtune.core.score.MetricWeights
import de.yuuto.aimtune.storage.RoundRow
import de.yuuto.aimtune.storage.SessionKind
import de.yuuto.aimtune.storage.scoreInputs
import kotlin.math.abs

/** What the stored blind strength searches at one setup found together, computed again from their rounds. [offer] is the curve to test, and only when the search clearly excluded "no curve". */
data class LastSearch(
    val dpi: Double,
    val cm360: Double,
    val rounds: Int,
    val sessions: Int,
    val result: StrengthResult?,
    /** The curve the searches were played on top of, or null for the plain mouse's natural curves. */
    val base: SearchBase? = null,
) {
    /** The curve of the best strength when the search is [clear]; an unclear result is explained, not offered. */
    val offer: AccelFit? get() = result?.takeIf { clear }?.let { base?.strengths?.offer(it.strength) ?: strengthAccel(it.strength) }

    /** True when the range of the best strength excludes "no curve" (a falling one only below the falling limit), i.e. the search says clearly that a curve scored better. */
    val clear: Boolean get() = result?.excludesPlain == true
}

/**
 * Finds the rounds of a blind strength search among stored rounds and runs the search's analysis on them again. A round
 * stores the curve it was played with, and the search's curves are natural curves with a fixed decay whose limit is the
 * strength, so the strength can be read back from the stored round.
 */
object StoredSearches {
    private const val DECAY_TOLERANCE = 1e-9
    private const val SAME_STRENGTH = 1e-9
    private const val MIN_DISTINCT_STRENGTHS = 4

    /**
     * Share by which the sensitivity of an earlier search may differ from the new one's (5 %). A strength is a gain
     * relative to the slow speeds, which depends less on the sensitivity than the way first movements miss does, so this is
     * wider than for the calibration; the recommended sensitivity moved by 4.5 % between two days in the author's data.
     */
    const val CM360_TOLERANCE = 0.05

    /** The newest rounds that are analysed; the optimizer's cost grows with them. */
    const val MAX_ROUNDS = 120

    /**
     * The strength a round was played with, or null when its curve is not one of the search's (e.g. a fitted curve of the error
     * analysis). With a [base] the search was played on top of the player's own curve: the curve itself is strength 1 and a scaled
     * table of the base is its strength; everything else, a plain round included, is not a round of that search.
     */
    fun strengthOf(accel: String?, base: SearchBase? = null): Double? {
        if (base != null) return ownStrengthOf(accel, base)
        if (accel == null) return CurveStrength.NEUTRAL
        if (AccelSpec.isProfile(accel)) return null // a whole Raw Accel profile (refine round) is no finder strength
        val curve = try {
            AccelSpec.decode(accel)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return when {
            curve === NoAccel -> CurveStrength.NEUTRAL
            curve is NaturalCurve && abs(curve.decayRate - CurveStrength.DECAY) < DECAY_TOLERANCE && curve.inputOffset == 0.0 -> curve.limit
            else -> null
        }
    }

    private fun ownStrengthOf(accel: String?, base: SearchBase): Double? {
        if (accel == null) return null
        if (accel.trim() == base.spec) return CurveStrength.NEUTRAL
        if (AccelSpec.isProfile(accel)) return null
        val curve = try {
            AccelSpec.decode(accel)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return base.strengths.strengthOf(curve)
    }

    /**
     * The search rounds of one stored session, oldest first. A session of the search kind is all search. Older sessions of
     * kind `accel_ab` hold the search and the blind test that followed it in one list: the test is the end of the session
     * where rounds come in pairs of one plain and one curve round with the same strength (the search varies the strength),
     * and what is before it is the search, if it has at least [StrengthSearch.MIN_ROUNDS] rounds and several strengths.
     */
    fun searchRounds(kind: SessionKind, rounds: List<RoundRow>): List<RoundRow> = when (kind) {
        SessionKind.ACCEL_SEARCH -> rounds
        SessionKind.ACCEL_AB -> legacySearchRounds(rounds)
        SessionKind.SENSITIVITY, SessionKind.ACCEL_REFINE -> emptyList()
    }

    private fun legacySearchRounds(rounds: List<RoundRow>): List<RoundRow> {
        val strengths = rounds.map { strengthOf(it.accel) }
        val usable = strengths.indexOfFirst { it == null }.let { if (it < 0) rounds.size else it }
        val values = strengths.take(usable).map { checkNotNull(it) }
        // A test that was stopped inside a pair leaves one round over, so the pairs may start one round before the end.
        val oddTail = testStart(values, values.size - 1).takeIf { it < values.size - 1 } ?: values.size
        val searchEnd = minOf(testStart(values, values.size), oddTail)
        val search = rounds.take(searchEnd)
        val distinct = values.take(searchEnd).filter(CurveStrength::isCurve).map { Math.round(it * 1000) }.toSet().size
        return if (search.size >= StrengthSearch.MIN_ROUNDS && distinct >= MIN_DISTINCT_STRENGTHS) search else emptyList()
    }

    /** Where the pairs of one plain and one curve round with one strength, going back from [end], begin. */
    private fun testStart(strengths: List<Double>, end: Int): Int {
        var i = end
        var curve: Double? = null
        while (i >= 2) {
            val a = strengths[i - 2]
            val b = strengths[i - 1]
            if (CurveStrength.isCurve(a) == CurveStrength.isCurve(b)) break
            val strength = if (CurveStrength.isCurve(a)) a else b
            if (curve != null && abs(strength - curve) > SAME_STRENGTH) break
            curve = strength
            i -= 2
        }
        return i
    }

    /** One stored search (non-empty [rounds] from [searchRounds]), its session and the DPI it was played with. */
    class SearchSession(val sessionId: Long, val dpi: Double, val rounds: List<RoundRow>) {
        val cm360: Double get() = rounds.first().cm360
    }

    /**
     * The rounds of the searches at this DPI and a sensitivity within [CM360_TOLERANCE] of [cm360] ([sessions] oldest first),
     * at most the newest [MAX_ROUNDS], each with its session and its position in it, so the optimizer can learn a better or
     * worse day per session. The strengths are all played at the same sensitivity, which is what lets the rounds of
     * several days answer the same question.
     */
    fun priorRounds(sessions: List<SearchSession>, dpi: Double, cm360: Double, base: SearchBase? = null): List<PriorSearchRound> =
        sessions.filter { abs(it.dpi - dpi) < 0.5 && abs(it.cm360 / cm360 - 1.0) <= CM360_TOLERANCE }
            .mapNotNull { session ->
                // A session played on top of another curve (or on a plain mouse) is another question; it is left out, not guessed at.
                val strengths = session.rounds.map { strengthOf(it.accel, base) }
                if (strengths.any { it == null }) null else session to strengths.map { checkNotNull(it) }
            }
            .flatMap { (session, strengths) ->
                session.rounds.mapIndexed { step, round -> PriorSearchRound(session.sessionId, step, strengths[step], round.scenario, round.scoreInputs()) }
            }
            .takeLast(MAX_ROUNDS)

    /** The newest search together with the earlier ones at its setup, analysed together; null without a search. */
    fun pool(sessions: List<SearchSession>, weights: MetricWeights, base: SearchBase? = null): LastSearch? {
        val newest = sessions.lastOrNull() ?: return null
        val rounds = priorRounds(sessions, newest.dpi, newest.cm360, base)
        if (rounds.isEmpty()) return null
        // Searches with flash rounds were played over the wider strength range, so they are analysed over it too.
        val flash = rounds.any { it.scenarioId == ScenarioKind.FLASH.id }
        val result = StrengthSearch(weights, seed = 0L, plannedRounds = rounds.size, prior = rounds, flash = flash, base = base).result()
        return LastSearch(newest.dpi, newest.cm360, rounds.size, rounds.map { it.sessionKey }.distinct().size, result, base)
    }
}
