package de.yuuto.aimtune.core.score

import de.yuuto.aimtune.core.scenario.TargetEvent
import kotlin.math.sqrt

/**
 * One reach at a target as a Valorant duel sees it (docs/VALORANT-WEIGHTS.md section 4.3). [firstShotNanos] is the time from
 * the start of the reach to the first click; [firstShotHit] says whether that click hit. [lost] marks a reach that loses the
 * duel whatever the aim did: the bot left unshot, or the player was blinded by a flash before it appeared.
 */
data class Engagement(val firstShotNanos: Long?, val firstShotHit: Boolean, val lost: Boolean)

/**
 * The engagements of a round, in order. A reach starts at the later of the previous hit and the spawn of the oldest target
 * still alive, as in [de.yuuto.aimtune.core.scenario.summarize]; clicks while no target is alive belong to no reach.
 * A reach still open when the round ends is left out: the round timer, not the player, ended it.
 */
fun engagements(events: List<TargetEvent>): List<Engagement> {
    val out = mutableListOf<Engagement>()
    val alive = LinkedHashMap<Int, Long>()
    var previousHit = Long.MIN_VALUE
    var firstShot: Long? = null
    var firstHit = false
    var blinded = false
    fun reachStart() = maxOf(previousHit, alive.values.min())
    fun close(lost: Boolean) {
        out += Engagement(firstShot, firstHit, lost || blinded)
        firstShot = null
        firstHit = false
        blinded = false
    }
    for (event in events) {
        when (event) {
            is TargetEvent.Spawned -> alive[event.target.id] = event.timeNanos
            is TargetEvent.FlashPop -> if (!event.dodged) blinded = true
            is TargetEvent.Hit -> {
                if (alive.isEmpty()) continue
                if (firstShot == null) {
                    firstShot = event.timeNanos - reachStart()
                    firstHit = true
                }
                alive.remove(event.targetId)
                previousHit = event.timeNanos
                close(lost = false)
            }
            is TargetEvent.Miss, is TargetEvent.BodyHit -> if (alive.isNotEmpty() && firstShot == null) {
                firstShot = event.timeNanos - reachStart()
                firstHit = false
            }
            is TargetEvent.Despawned -> if (alive.remove(event.targetId) != null) close(lost = true)
            is TargetEvent.Waypoint, is TargetEvent.FlashCue -> Unit
        }
    }
    return out
}

/**
 * Duel-win score of rounds: each engagement is a duel against an opponent as fast as the player (kill time normal with the
 * median and spread of the player's own first-shot hits in that scenario). A first-shot hit at t wins with G(t), a first-shot
 * miss at t0 with G(t0 + [missDelayNanos]) (the follow-up body shots), a lost engagement with 0. The round score is
 * 100 x the mean win chance. [missDelayNanos] defaults to the Vandal's four body shots on 150 HP (3 shot intervals, 308 ms).
 */
class DuelScorer(
    private val missDelayNanos: Long = VANDAL_FOLLOW_UP_NANOS,
    private val minCleanHits: Int = 3,
    private val minRounds: Int = 3,
) {
    init {
        require(missDelayNanos >= 0 && minCleanHits >= 2 && minRounds >= 2) { "invalid duel settings" }
    }

    /**
     * One score per round, or null when the round has no engagement or its scenario has too few rounds or first-shot hits for
     * a baseline. As in [ScoreCalculator], a baseline needs [minRounds] rounds: a single round scored against itself says
     * nothing about the sensitivity it was played at.
     */
    fun scoreRounds(rounds: List<List<Engagement>>, scenarios: List<String>): List<Double?> {
        require(scenarios.size == rounds.size) { "one scenario per round expected" }
        val opponents = rounds.indices.groupBy { scenarios[it] }.mapValues { (_, indices) ->
            if (indices.size < minRounds) null else opponent(indices.flatMap { rounds[it] })
        }
        return rounds.indices.map { i ->
            val opponent = opponents[scenarios[i]]
            if (opponent == null || rounds[i].isEmpty()) null else 100.0 * rounds[i].map { win(it, opponent) }.average()
        }
    }

    private fun opponent(all: List<Engagement>): Opponent? {
        val clean = all.filter { it.firstShotHit && !it.lost }.mapNotNull { it.firstShotNanos?.toDouble() }.sorted()
        if (clean.size < minCleanHits) return null
        val mean = clean.average()
        val sd = sqrt(clean.sumOf { (it - mean) * (it - mean) } / (clean.size - 1))
        return if (sd <= 0.0) null else Opponent(median(clean), sd)
    }

    private fun win(e: Engagement, o: Opponent): Double {
        val shot = e.firstShotNanos
        if (e.lost || shot == null) return 0.0
        val killAt = if (e.firstShotHit) shot.toDouble() else (shot + missDelayNanos).toDouble()
        return 1.0 - normalCdf((killAt - o.medianNanos) / o.sdNanos)
    }

    private fun median(sorted: List<Double>): Double =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    private data class Opponent(val medianNanos: Double, val sdNanos: Double)

    companion object {
        /** Vandal: 9.75 rounds/s, four body shots kill 150 HP, so three more shot intervals after a missed head shot. */
        const val VANDAL_FOLLOW_UP_NANOS = 308_000_000L
    }
}
