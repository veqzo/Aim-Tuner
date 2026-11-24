package de.yuuto.aimtune.core.score

import de.yuuto.aimtune.core.scenario.TargetEvent
import kotlin.math.sqrt

/**
 * One reach at a target as a Valorant duel sees it (docs/VALORANT-WEIGHTS.md section 4.3). [firstShotNanos] is the time from
 * the start of the reach to the first click; [firstShotHit] says whether that click hit. [lost] marks a reach that loses the
 * duel whatever the aim did: the bot left unshot, or the player was blinded by a flash before it appeared. [reachNanos] is
 * the time from the start of the reach to its end (the hit, the despawn or the round end); null where it is not known.
 * [open] marks a reach that the round end cut off before a hit.
 */
data class Engagement(
    val firstShotNanos: Long?,
    val firstShotHit: Boolean,
    val lost: Boolean,
    val reachNanos: Long? = null,
    val open: Boolean = false,
)

/**
 * The engagements of a round, in order. A reach starts at the later of the previous hit and the spawn of the oldest target
 * still alive, as in [de.yuuto.aimtune.core.scenario.summarize]; clicks while no target is alive belong to no reach.
 * A reach still open at [roundEndNanos] is the last engagement, marked [Engagement.open]; the scorer decides whether the
 * player or the round timer ended it. Without [roundEndNanos] an open reach is left out.
 */
fun engagements(events: List<TargetEvent>, roundEndNanos: Long? = null): List<Engagement> {
    val out = mutableListOf<Engagement>()
    val alive = LinkedHashMap<Int, Long>()
    var previousHit = Long.MIN_VALUE
    var firstShot: Long? = null
    var firstHit = false
    var blinded = false
    fun reachStart() = maxOf(previousHit, alive.values.min())
    fun close(lost: Boolean, endNanos: Long, open: Boolean = false) {
        out += Engagement(firstShot, firstHit, lost || blinded, endNanos - reachStart(), open)
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
                close(lost = false, event.timeNanos)
                alive.remove(event.targetId)
                previousHit = event.timeNanos
            }
            is TargetEvent.Miss, is TargetEvent.BodyHit -> if (alive.isNotEmpty() && firstShot == null) {
                firstShot = event.timeNanos - reachStart()
                firstHit = false
            }
            is TargetEvent.Despawned -> if (event.targetId in alive) {
                close(lost = true, event.timeNanos)
                alive.remove(event.targetId)
            }
            is TargetEvent.Waypoint, is TargetEvent.FlashCue -> Unit
        }
    }
    if (roundEndNanos != null && alive.isNotEmpty()) close(lost = false, roundEndNanos, open = true)
    return out
}

/**
 * How a game's duels are decided (M23g4), from the profile's `duel` block: [missFollowUpNanos] is the time the follow-up
 * shots need to kill after a first shot that missed the head. A reach longer than [limitNanos] loses the duel (null: no
 * limit). A game with duel rules scores its click rounds with [DuelScorer].
 */
data class DuelRules(val missFollowUpNanos: Long, val verified: Boolean = false, val limitNanos: Long? = null) {
    init {
        require(missFollowUpNanos in 0..2_000_000_000L) { "the follow-up after a miss must be 0-2 s" }
        require(limitNanos == null || limitNanos in 500_000_000L..30_000_000_000L) { "the duel limit must be 0.5-30 s" }
    }

    fun scorer(): DuelScorer = DuelScorer(missFollowUpNanos, limitNanos)
}

/**
 * Duel-win score of rounds: each engagement is a duel against an opponent as fast as the player (kill time normal with the
 * median and spread of the player's own first-shot hits in that scenario). A first-shot hit at t wins with G(t), a first-shot
 * miss at t0 with G(t0 + [missDelayNanos]) (the follow-up body shots), a lost engagement with 0. The round score is
 * 100 x the mean win chance. [missDelayNanos] defaults to the Vandal's four body shots on 150 HP (3 shot intervals, 308 ms).
 *
 * A reach longer than [limitNanos] is a lost duel: the opponent has shot long before. This includes a reach that the round
 * end cut off after more than the limit, so a round in which the player gets stuck on one target scores 0 and does not
 * vanish. A reach that the round end cut off within the limit is left out: the round timer, not the player, ended it.
 * Without a limit every cut-off reach is left out.
 */
class DuelScorer(
    private val missDelayNanos: Long = VANDAL_FOLLOW_UP_NANOS,
    private val limitNanos: Long? = null,
    private val minCleanHits: Int = 3,
    private val minRounds: Int = 3,
) {
    init {
        require(missDelayNanos >= 0 && minCleanHits >= 2 && minRounds >= 2) { "invalid duel settings" }
        require(limitNanos == null || limitNanos > 0) { "the duel limit must be positive" }
    }

    /**
     * One score per round, or null when the round has no engagement or its scenario has too few rounds or first-shot hits for
     * a baseline. As in [ScoreCalculator], a baseline needs [minRounds] rounds: a single round scored against itself says
     * nothing about the sensitivity it was played at.
     */
    fun scoreRounds(rounds: List<List<Engagement>>, scenarios: List<String>): List<Double?> {
        require(scenarios.size == rounds.size) { "one scenario per round expected" }
        val judged = rounds.map(::judged)
        val opponents = judged.indices.groupBy { scenarios[it] }.mapValues { (_, indices) ->
            if (indices.size < minRounds) null else opponent(indices.flatMap { judged[it] })
        }
        return judged.indices.map { i ->
            val opponent = opponents[scenarios[i]]
            if (opponent == null || judged[i].isEmpty()) null else 100.0 * judged[i].map { win(it, opponent) }.average()
        }
    }

    /** The engagements as the duel sees them: a reach over the limit is lost, a cut-off reach within it is left out. */
    private fun judged(round: List<Engagement>): List<Engagement> = round.mapNotNull { e ->
        val tooLong = limitNanos != null && e.reachNanos != null && e.reachNanos > limitNanos
        when {
            tooLong -> e.copy(lost = true)
            e.open -> null
            else -> e
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
