package dev.veqzo.aimtune.app.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The sound of a hit, made from scratch: a short, bright "pluck" in three layers that together feel crisp and rewarding.
 * A click for the instant of the hit, a bell-like ping that rings out briefly, and a soft low thump with a falling pitch
 * that gives it weight. It is short on purpose: in a fast round the next hit comes before this one has to end.
 */
object HitSoundSynth {
    const val SECONDS = 0.28
    private const val TWO_PI = 2.0 * PI
    private const val CLICK_SEED = 7_321L
    private const val PING_HZ = 1568.0

    /** A second partial that is not a whole multiple, which is what makes a struck bell sound like glass instead of a flute. */
    private const val PING_OVERTONE = 2.76

    fun samples(): ShortArray {
        val random = Random(CLICK_SEED)
        var previousNoise = 0.0
        var thumpPhase = 0.0
        return FlashSoundSynth.render(SECONDS) { t ->
            // Differencing white noise leaves only the highs: a tick, not a hiss.
            val noise = random.nextDouble() * 2.0 - 1.0
            val tick = (noise - previousNoise) * exp(-t / 0.0018)
            previousNoise = noise
            val attack = (t / 0.0008).coerceAtMost(1.0)
            val ping = attack * exp(-t / 0.085) * (sin(TWO_PI * PING_HZ * t) + 0.35 * exp(-t / 0.04) * sin(TWO_PI * PING_HZ * PING_OVERTONE * t))
            thumpPhase += TWO_PI * (90.0 + 130.0 * exp(-t / 0.02)) / FlashSoundSynth.SAMPLE_RATE
            val thump = exp(-t / 0.05) * sin(thumpPhase)
            val fadeOut = ((SECONDS - t) / 0.02).coerceAtMost(1.0)
            fadeOut * (0.45 * tick + 0.8 * ping + 0.6 * thump)
        }
    }
}
