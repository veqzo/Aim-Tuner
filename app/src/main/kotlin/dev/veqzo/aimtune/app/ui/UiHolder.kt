package dev.veqzo.aimtune.app.ui

import kotlin.math.abs

/**
 * Owns the current [UiSkin]. The skin is rendered for one framebuffer height, so it is rebuilt when
 * the window size changes noticeably (many small resize events during a drag are ignored).
 */
class UiHolder(val tokens: Tokens, framebufferHeight: Int) {
    var skin: UiSkin = UiSkin(tokens, framebufferHeight)
        private set
    private var builtFor = framebufferHeight

    /**
     * Rebuilds the skin if [framebufferHeight] differs enough from the current one. Returns the
     * replaced skin, which the caller must dispose after screens have been rebuilt with the new one,
     * or null if nothing changed.
     */
    fun updateFor(framebufferHeight: Int): UiSkin? {
        if (!needsRebuild(builtFor, framebufferHeight)) return null
        val old = skin
        skin = UiSkin(tokens, framebufferHeight)
        builtFor = framebufferHeight
        return old
    }

    fun dispose() = skin.dispose()

    companion object {
        private const val REBUILD_THRESHOLD = 0.08f

        /** A zero height (minimized window) never triggers a rebuild. */
        fun needsRebuild(builtFor: Int, now: Int): Boolean =
            now > 0 && abs(now - builtFor).toFloat() / builtFor > REBUILD_THRESHOLD
    }
}
