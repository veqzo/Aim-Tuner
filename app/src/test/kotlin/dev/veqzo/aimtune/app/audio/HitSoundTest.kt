package dev.veqzo.aimtune.app.audio

import dev.veqzo.aimtune.core.scenario.ShotResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HitSoundTest {
    @Test
    fun `hits in a row climb the scale and stay at the top`() {
        val combo = HitCombo()
        val pitches = List(8) { combo.onShot(ShotResult.HIT)!! }
        assertEquals(1f, pitches.first())
        assertTrue(pitches.zipWithNext().take(HitCombo.STEPS.size - 1).all { (a, b) -> b > a })
        assertEquals(2f, pitches.last(), 1e-6f)
        assertEquals(pitches[HitCombo.STEPS.lastIndex], pitches.last())
    }

    @Test
    fun `a miss ends the streak and plays nothing`() {
        val combo = HitCombo()
        repeat(3) { combo.onShot(ShotResult.HIT) }
        assertNull(combo.onShot(ShotResult.MISS))
        assertEquals(0, combo.streak)
        assertEquals(1f, combo.onShot(ShotResult.HIT))
        assertNull(combo.onShot(ShotResult.IGNORED))
        assertEquals(1, combo.streak)
    }

    @Test
    fun `the hit sound is short, audible and does not clip`() {
        val samples = HitSoundSynth.samples()
        val peak = samples.maxOf { abs(it.toInt()) }
        assertEquals((HitSoundSynth.SECONDS * FlashSoundSynth.SAMPLE_RATE).toInt(), samples.size)
        assertTrue(peak in 20_000..Short.MAX_VALUE, "peak $peak")
        // It has died away by the end, so it never ends with a click.
        assertTrue(samples.takeLast(200).all { abs(it.toInt()) < 300 })
    }
}
