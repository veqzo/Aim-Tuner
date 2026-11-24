package dev.veqzo.aimtune.core.scenario

/** Basic outcome of a round, derived from its target events. Richer metrics come with milestone M3. */
data class RoundSummary(
    val hits: Int,
    val misses: Int,
    val meanTimeToHitNanos: Long?,
    /** Clicks that were the first since the round began or since the last hit, and how many of them hit. */
    val firstShots: Int = 0,
    val firstShotHits: Int = 0,
) {
    val shots: Int get() = hits + misses

    /**
     * Share of reaches whose first click hit, or null when nothing was fired. Valorant rewards the first
     * shot on the head: a second click after a miss costs time but did not make the first one good.
     */
    val firstShotAccuracy: Double? get() = if (firstShots == 0) null else firstShotHits.toDouble() / firstShots

    /** Hits per shot in [0, 1]; 0 when no shot was fired. */
    val accuracy: Double get() = if (shots == 0) 0.0 else hits.toDouble() / shots
}

/**
 * Time to hit is measured from the later of the target's spawn and the previous hit to the hit on
 * it. With one target at a time that is the spawn; with several visible at once (switching) it is
 * the time the player needed to get from the last hit to this one. [appearedAt] says when a target came into view where that is
 * later than its spawn (a Range bot beside or behind the player): the time to hit starts there, and a click before it, at
 * nothing, is neither a shot nor a miss.
 */
fun summarize(events: List<TargetEvent>, appearedAt: Map<Int, Long> = emptyMap()): RoundSummary {
    val spawnTimes = HashMap<Int, Long>()
    var hits = 0
    var misses = 0
    var totalHitTime = 0L
    var previousHitNanos = Long.MIN_VALUE
    var firstShots = 0
    var firstShotHits = 0
    var awaitingFirstShot = true
    var visibleFrom = Long.MIN_VALUE
    for (event in events) {
        when (event) {
            is TargetEvent.Spawned -> {
                spawnTimes[event.target.id] = event.timeNanos
                visibleFrom = appearedAt[event.target.id] ?: event.timeNanos
            }
            is TargetEvent.Hit -> {
                if (awaitingFirstShot) {
                    firstShots++
                    firstShotHits++
                }
                awaitingFirstShot = true
                hits++
                val spawn = checkNotNull(spawnTimes[event.targetId]) { "hit without spawn: ${event.targetId}" }
                totalHitTime += event.timeNanos - maxOf(appearedAt[event.targetId] ?: spawn, previousHitNanos)
                previousHitNanos = event.timeNanos
            }
            is TargetEvent.Miss, is TargetEvent.BodyHit -> if (event.timeNanos >= visibleFrom) {
                misses++
                if (awaitingFirstShot) firstShots++
                awaitingFirstShot = false
            }
            // A bot that left unshot was a first shot that did not hit; the next bot starts a new reach.
            is TargetEvent.Despawned -> {
                if (awaitingFirstShot) firstShots++
                awaitingFirstShot = true
            }
            is TargetEvent.Waypoint, is TargetEvent.FlashCue, is TargetEvent.FlashPop -> Unit
        }
    }
    return RoundSummary(hits, misses, if (hits == 0) null else totalHitTime / hits, firstShots, firstShotHits)
}
