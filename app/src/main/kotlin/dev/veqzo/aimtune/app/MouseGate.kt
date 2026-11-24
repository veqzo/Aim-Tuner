package dev.veqzo.aimtune.app

import dev.veqzo.aimtune.input.RawMouseSource

/**
 * Decides when the mouse source may listen. Hard rule: only while our window is focused, and in
 * addition only while a screen that needs raw mouse movement (the trainer) is showing, so menus
 * never read the mouse.
 */
class MouseGate(private val source: RawMouseSource) {
    /** Whether our window has the focus; a round may only start without a click while it has. */
    var windowFocused = false
        private set
    private var allowed = false

    fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
        apply()
    }

    fun setAllowed(allowed: Boolean) {
        this.allowed = allowed
        apply()
    }

    private fun apply() = source.setWindowFocused(windowFocused && allowed)
}
