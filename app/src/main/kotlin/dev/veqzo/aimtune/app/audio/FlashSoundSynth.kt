package dev.veqzo.aimtune.app.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The sounds around a flash; each tells the player something (docs/ALGORITHMS.md "Flash turn-away"). */
enum class FlashSoundEvent {
    /** A flash became visible. */
    ALERT,

    /** The flash is about to pop: a rising whine that ends with the pop. */
    WHINE,

    /** The flash popped: it is safe to turn back. */
    POP,

    /** The player was blinded: a ringing that lasts about as long as the white-out. */
    RING,
}

/**
 * The flash sounds, made from scratch (mono, 16 bit): short and clear, in the spirit of the cues of the game
 * (a charging whine, a bang, a ringing after a hit) without using any of its audio.
 */
object FlashSoundSynth {
    const val SAMPLE_RATE = 44_100
    const val WHINE_SECONDS = 0.45
    const val RING_SECONDS = 1.5
    private const val ALERT_SECONDS = 0.09
    private const val POP_SECONDS = 0.55
    private const val TWO_PI = 2.0 * PI
    private const val PEAK = 0.8
    private const val POP_SEED = 20_241L
    private const val WAV_HEADER_BYTES = 44

    fun samples(event: FlashSoundEvent): ShortArray = when (event) {
        FlashSoundEvent.ALERT -> alert()
        FlashSoundEvent.WHINE -> whine()
        FlashSoundEvent.POP -> pop()
        FlashSoundEvent.RING -> ring()
    }

    private fun alert(): ShortArray = render(ALERT_SECONDS) { t ->
        val attack = (t / 0.003).coerceAtMost(1.0)
        attack * exp(-t / 0.03) * (sin(TWO_PI * 1760.0 * t) + 0.5 * sin(TWO_PI * 2640.0 * t))
    }

    /** A sine that sweeps from 500 to 3000 Hz while it gets louder, cut at its peak by the pop that follows. */
    private fun whine(): ShortArray {
        var phase = 0.0
        return render(WHINE_SECONDS) { t ->
            val x = t / WHINE_SECONDS
            phase += TWO_PI * (500.0 + 2500.0 * x * x) / SAMPLE_RATE
            val fadeOut = ((WHINE_SECONDS - t) / 0.006).coerceAtMost(1.0)
            fadeOut * (0.25 + 0.75 * x) * (sin(phase) + 0.3 * sin(2.0 * phase))
        }
    }

    /** A noise burst with a low thump and a high ring that dies away. */
    private fun pop(): ShortArray {
        val random = Random(POP_SEED)
        var low = 0.0
        return render(POP_SECONDS) { t ->
            low += 0.45 * ((random.nextDouble() * 2.0 - 1.0) - low)
            exp(-t / 0.05) * low * 1.6 + exp(-t / 0.12) * sin(TWO_PI * 70.0 * t) * 0.7 + exp(-t / 0.35) * sin(TWO_PI * 3300.0 * t) * 0.25
        }
    }

    /** Two close high tones that fade out, the ringing of ears after a bang. */
    private fun ring(): ShortArray = render(RING_SECONDS) { t ->
        val attack = (t / 0.02).coerceAtMost(1.0)
        attack * (1.0 - t / RING_SECONDS).pow(1.5) * 0.5 * (sin(TWO_PI * 4000.0 * t) + sin(TWO_PI * 4150.0 * t))
    }

    /** Samples of [seconds] from [value] at each time, scaled so the peak is [PEAK] of full scale. */
    internal fun render(seconds: Double, value: (Double) -> Double): ShortArray {
        val raw = DoubleArray((seconds * SAMPLE_RATE).toInt()) { value(it.toDouble() / SAMPLE_RATE) }
        val scale = PEAK / max(raw.maxOf { abs(it) }, 1e-9)
        return ShortArray(raw.size) { (raw[it] * scale * Short.MAX_VALUE).toInt().toShort() }
    }

    /** The samples as a WAV file (RIFF, PCM, mono, 16 bit). */
    fun toWav(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putShort(it) } }.array()
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.size)
        return ByteArrayOutputStream().also { it.write(header.array()); it.write(data) }.toByteArray()
    }
}
