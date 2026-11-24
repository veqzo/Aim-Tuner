package dev.veqzo.aimtune.core.metrics

import dev.veqzo.aimtune.core.recording.RoundRecording
import dev.veqzo.aimtune.core.scenario.TargetEvent

/** What a Range round adds to the common metrics: kills and how long each bot took to die. */
data class RangeMetrics(
    val kills: Int,
    /** Spawn to kill per bot, over the bots that were killed. */
    val medianTimeToKillMs: Double?,
    /** Clicks (head hits, body hits and misses) per kill; null without a kill. */
    val shotsPerKill: Double?,
    /** Bots that spawned outside the field of view, and the median time from their spawn until they were in view. */
    val offScreenSpawns: Int = 0,
    val medianSearchMs: Double? = null,
)

/**
 * Kills, time to kill per bot and shots per kill of a Range round, from its target events. A bot's time to kill starts when it
 * came into view ([appearedAt], see `summarize`), so the turn toward a bot that spawned beside or behind the player is
 * reported separately as the search time.
 */
fun computeRangeMetrics(recording: RoundRecording, appearedAt: Map<Int, Long> = emptyMap()): RangeMetrics {
    val spawns = HashMap<Int, Long>()
    val timesToKill = ArrayList<Double>()
    val searchTimes = ArrayList<Double>()
    var shots = 0
    for (event in recording.targetEvents) {
        when (event) {
            is TargetEvent.Spawned -> {
                spawns[event.target.id] = event.timeNanos
                appearedAt[event.target.id]?.takeIf { it > event.timeNanos }?.let { searchTimes += (it - event.timeNanos) / 1e6 }
            }
            is TargetEvent.Hit -> {
                shots++
                spawns[event.targetId]?.let { timesToKill += (event.timeNanos - (appearedAt[event.targetId] ?: it)) / 1e6 }
            }
            is TargetEvent.Miss, is TargetEvent.BodyHit -> shots++
            is TargetEvent.Waypoint, is TargetEvent.Despawned, is TargetEvent.FlashCue, is TargetEvent.FlashPop -> Unit
        }
    }
    return RangeMetrics(
        kills = timesToKill.size,
        medianTimeToKillMs = median(timesToKill),
        shotsPerKill = if (timesToKill.isEmpty()) null else shots.toDouble() / timesToKill.size,
        offScreenSpawns = searchTimes.size,
        medianSearchMs = median(searchTimes),
    )
}

private fun median(values: List<Double>): Double? {
    val sorted = values.sorted()
    return when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}
