package dev.veqzo.aimtune.app.audio

import dev.veqzo.aimtune.core.scenario.FlashState

/**
 * Decides which flash sound is due, from the flash the trainer reports and the clock. The scenario only
 * reports a flash until it pops (and while a blinded player is in the white-out), so the pop is taken from the
 * clock: a dodged flash simply disappears from the state. Call [update] every frame; each sound is returned once
 * per flash.
 */
class FlashCueTracker(private val whineLeadNanos: Long = (FlashSoundSynth.WHINE_SECONDS * 1e9).toLong()) {
    private var flashId = NONE
    private var popNanos = 0L
    private var whined = false
    private var popped = false
    private var ringId = NONE

    fun reset() {
        flashId = NONE
        ringId = NONE
    }

    fun update(flash: FlashState?, nowNanos: Long): List<FlashSoundEvent> {
        val due = mutableListOf<FlashSoundEvent>()
        if (flash != null && flash.id != flashId) {
            flashId = flash.id
            popNanos = flash.popNanos
            whined = false
            popped = false
            due += FlashSoundEvent.ALERT
        }
        if (flashId != NONE && !whined && nowNanos >= popNanos - whineLeadNanos && nowNanos < popNanos) {
            whined = true
            due += FlashSoundEvent.WHINE
        }
        if (flashId != NONE && !popped && nowNanos >= popNanos) {
            popped = true
            whined = true
            due += FlashSoundEvent.POP
        }
        if (flash?.popped == true && ringId != flash.id) {
            ringId = flash.id
            due += FlashSoundEvent.RING
        }
        return due
    }

    private companion object {
        const val NONE = -1
    }
}
