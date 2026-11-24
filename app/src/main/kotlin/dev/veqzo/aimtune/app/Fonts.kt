package dev.veqzo.aimtune.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.utils.Disposable
import dev.veqzo.aimtune.app.ui.Tokens
import kotlin.math.roundToInt

/**
 * Inter, Inter Display and JetBrains Mono rasterized at the exact pixel size of the framebuffer, then scaled back
 * so that text is laid out in design units (a 1080-high design space). Scaling a smaller bitmap
 * font up would blur; this stays sharp at every window size. Sizes come from the design tokens.
 * Call [dispose] before building a new instance.
 */
class Fonts(framebufferHeight: Int, tokens: Tokens) : Disposable {
    /** Framebuffer pixels per design unit. */
    val scale = framebufferHeight / tokens.screenHeight

    private val generators = mutableMapOf<String, FreeTypeFontGenerator>()
    private val all = mutableListOf<BitmapFont>()

    /** Field and section labels, sentence case (token `label`). */
    val label = create(INTER_MEDIUM, tokens.fontSize("label"))
    val caption = create(INTER_REGULAR, tokens.fontSize("caption"))
    val body = create(INTER_REGULAR, tokens.fontSize("body"))
    val bodyLg = create(INTER_REGULAR, tokens.fontSize("bodyLg"))
    val bodyMedium = create(INTER_MEDIUM, tokens.fontSize("body"))

    /** Titles, module names and the brand: Inter Display SemiBold, tight and sharp at large sizes. */
    val brand = create(INTER_DISPLAY_SEMIBOLD, BRAND_SIZE)
    val strong = create(INTER_DISPLAY_SEMIBOLD, tokens.fontSize("bodyLg"))
    val title = create(INTER_DISPLAY_SEMIBOLD, tokens.fontSize("title"))
    val heading = create(INTER_DISPLAY_SEMIBOLD, tokens.fontSize("heading"))

    /** Numbers, units, timers and the debug overlay. */
    val mono = create(JETBRAINS_MONO, tokens.fontSize("body"))
    val numberMd = create(JETBRAINS_MONO, tokens.fontSize("numberMd"))

    /** The readout of a rack module (token `display`). */
    val readout = create(JETBRAINS_MONO, tokens.fontSize("display"), NUMBER_CHARS)
    val numberLg = create(JETBRAINS_MONO, tokens.fontSize("numberLg"), NUMBER_CHARS)
    val numberXl = create(JETBRAINS_MONO, tokens.fontSize("numberXl"), NUMBER_CHARS)

    private fun create(path: String, designSize: Float, characters: String = FreeTypeFontGenerator.DEFAULT_CHARS + EXTRA_CHARS): BitmapFont {
        val generator = generators.getOrPut(path) { FreeTypeFontGenerator(Gdx.files.classpath(path)) }
        val parameter = FreeTypeFontParameter().apply {
            size = (designSize * scale).roundToInt().coerceAtLeast(MIN_PX)
            minFilter = Texture.TextureFilter.Linear
            magFilter = Texture.TextureFilter.Linear
            this.characters = characters
        }
        return generator.generateFont(parameter).also {
            // Glyphs exist at framebuffer resolution; draw them at design-unit size.
            it.data.setScale(1f / scale)
            it.setUseIntegerPositions(false)
            all += it
        }
    }

    override fun dispose() {
        all.forEach { it.dispose() }
        generators.values.forEach { it.dispose() }
    }

    private companion object {
        const val MIN_PX = 10
        const val BRAND_SIZE = 24f

        const val INTER_REGULAR = "assets/fonts/Inter-Regular.ttf"
        const val INTER_MEDIUM = "assets/fonts/Inter-Medium.ttf"
        const val INTER_DISPLAY_SEMIBOLD = "assets/fonts/InterDisplay-SemiBold.ttf"
        const val JETBRAINS_MONO = "assets/fonts/JetBrainsMono-Regular.ttf"

        // Degree sign, middle dot, dashes, arrows and German letters used by the UI texts.
        const val EXTRA_CHARS = "°·–—…→←↑↓×÷≈±✓ÄÖÜäöüß"

        // The very large number fonts only ever show digits and a few symbols.
        const val NUMBER_CHARS = "0123456789.,-+%:×–— "
    }
}
