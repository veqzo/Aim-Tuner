package dev.veqzo.aimtune.app.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.utils.Disposable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plays the flash sounds through LibGDX. The sounds are synthesized once, written as WAV files into a temporary
 * folder (LibGDX loads sounds from files) and loaded; without an audio device nothing plays and the app goes on.
 */
class FlashSounds : Disposable {
    private val log = System.getLogger("aimtune.audio")
    private var folder: Path? = null
    private val sounds: Map<FlashSoundEvent, Sound> = load()

    private fun load(): Map<FlashSoundEvent, Sound> = try {
        val dir = Files.createTempDirectory("aimtune-sounds").also { folder = it }
        FlashSoundEvent.entries.associateWith { event ->
            val file = dir.resolve("${event.name.lowercase()}.wav")
            Files.write(file, FlashSoundSynth.toWav(FlashSoundSynth.samples(event)))
            Gdx.audio.newSound(Gdx.files.absolute(file.toString()))
        }
    } catch (e: Exception) {
        log.log(System.Logger.Level.WARNING, "flash sounds are not available: ${e.message}")
        emptyMap()
    }

    /** Plays [event] at its built-in volume times [gain] (0..1, the player's volume setting). */
    fun play(event: FlashSoundEvent, gain: Float = 1f) {
        sounds[event]?.play((VOLUMES.getValue(event) * gain).coerceIn(0f, 1f))
    }

    override fun dispose() {
        sounds.values.forEach { it.dispose() }
        folder?.let { dir -> runCatching { Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) } }
    }

    private companion object {
        val VOLUMES = mapOf(FlashSoundEvent.ALERT to 0.5f, FlashSoundEvent.WHINE to 0.6f, FlashSoundEvent.POP to 1.0f, FlashSoundEvent.RING to 0.5f)
    }
}
