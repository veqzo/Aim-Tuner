package dev.veqzo.aimtune.app.audio

import dev.veqzo.aimtune.core.scenario.FlashState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashSoundTest {
    private val second = 1_000_000_000L

    private fun flash(id: Int, popNanos: Long, popped: Boolean = false, blindUntil: Long = 0L) =
        FlashState(id, 20.0, 0.0, 10.0, popNanos, 61.5, popped, blindUntil)

    @Test
    fun `every sound is audible, never clips and the whine and the pop end quietly`() {
        for (event in FlashSoundEvent.entries) {
            val samples = FlashSoundSynth.samples(event)
            val peak = samples.maxOf { abs(it.toInt()) }
            assertTrue(peak in 20_000..Short.MAX_VALUE.toInt(), "$event peak $peak")
            assertTrue(samples.size / FlashSoundSynth.SAMPLE_RATE.toDouble() in 0.05..1.6, "$event length ${samples.size}")
        }
        assertEquals((FlashSoundSynth.WHINE_SECONDS * FlashSoundSynth.SAMPLE_RATE).toInt(), FlashSoundSynth.samples(FlashSoundEvent.WHINE).size)
        assertTrue(abs(FlashSoundSynth.samples(FlashSoundEvent.WHINE).last().toInt()) < 3_000, "the whine is faded out before the pop")
        assertTrue(abs(FlashSoundSynth.samples(FlashSoundEvent.POP).last().toInt()) < 2_000, "the pop has died away")
    }

    @Test
    fun `the wav file has a valid header for the samples`() {
        val samples = FlashSoundSynth.samples(FlashSoundEvent.ALERT)
        val wav = FlashSoundSynth.toWav(samples)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(44 + samples.size * 2, wav.size)
    }

    @Test
    fun `a flash gives an alert, the whine shortly before the pop, the pop at its time and no ring when dodged`() {
        val tracker = FlashCueTracker()
        val pop = 2 * second
        assertEquals(listOf(FlashSoundEvent.ALERT), tracker.update(flash(0, pop), 1 * second))
        assertEquals(emptyList(), tracker.update(flash(0, pop), 1 * second + 100_000_000L))
        assertEquals(listOf(FlashSoundEvent.WHINE), tracker.update(flash(0, pop), pop - 440_000_000L))
        assertEquals(emptyList(), tracker.update(flash(0, pop), pop - 10_000_000L))
        assertEquals(listOf(FlashSoundEvent.POP), tracker.update(null, pop + 5_000_000L), "a dodged flash vanishes from the state, the clock says it popped")
        assertEquals(emptyList(), tracker.update(null, pop + 600_000_000L))
    }

    @Test
    fun `a blinded player hears the ringing once and the next flash starts over`() {
        val tracker = FlashCueTracker()
        tracker.update(flash(0, 2 * second), second)
        val blinded = flash(0, 2 * second, popped = true, blindUntil = 3_500_000_000L)
        val atPop = tracker.update(blinded, 2 * second + 1_000_000L)
        assertTrue(FlashSoundEvent.POP in atPop && FlashSoundEvent.RING in atPop)
        assertEquals(emptyList(), tracker.update(blinded, 2 * second + 500_000_000L))
        assertEquals(listOf(FlashSoundEvent.ALERT), tracker.update(flash(1, 6 * second), 5 * second))
    }

    @Test
    fun `a flash that is already about to pop when first seen whines at once and a new round starts clean`() {
        val tracker = FlashCueTracker()
        assertEquals(listOf(FlashSoundEvent.ALERT, FlashSoundEvent.WHINE), tracker.update(flash(0, second + 300_000_000L), second))
        tracker.reset()
        assertEquals(FlashSoundEvent.ALERT, tracker.update(flash(0, 9 * second), 8 * second + 800_000_000L).first())
    }
}
