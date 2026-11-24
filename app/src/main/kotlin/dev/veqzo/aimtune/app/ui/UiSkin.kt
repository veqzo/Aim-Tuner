package dev.veqzo.aimtune.app.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.List
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Disposable
import dev.veqzo.aimtune.app.Fonts

/** Names of the widget styles registered in [UiSkin.skin]. */
object Styles {
    // Scroll pane of the screen frame
    const val FRAME_SCROLL = "frame"

    // Label
    const val BODY = "body"
    const val BODY_MUTED = "bodyMuted"
    const val BODY_LG = "bodyLg"
    const val CAPTION_MUTED = "captionMuted"
    const val LABEL = "label"
    const val BRAND = "brand"
    const val TITLE = "title"
    const val HEADING = "heading"
    const val MONO = "mono"
    const val MONO_MUTED = "monoMuted"
    const val NUMBER_MD = "numberMd"
    const val NUMBER_LG = "numberLg"
    const val NUMBER_XL = "numberXl"
    const val READOUT = "readout"
    const val OK = "ok"
    const val CAUTION = "caution"

    // TextButton
    const val PRIMARY = "primary"
    const val SECONDARY = "secondary"
    const val TEXT = "text"
    const val TILE = "tile"

    /** Small rounded toggle: inline pre-checks at the start button, variant switches. */
    const val CHIP = "chip"

    /** Borderless text with icon slot: the utility bar and quiet actions. */
    const val GHOST = "ghost"

    // Button (container with its own labels) and cards
    const val CARD_BUTTON = "cardButton"

    /** A recessed readout that is also a button (the setup pill). */
    const val WELL_BUTTON = "wellButton"
    const val CARD = "card"

    /** The raised face of a rack module; [WELL] is the recessed display inside it. */
    const val MODULE = "module"
    const val WELL = "well"

    // TextField
    const val INPUT_LG = "inputLg"
    const val INPUT = "input"
}

/**
 * The Scene2D skin of the Rack design: colors, radii and type come from the design tokens, shapes are
 * generated at framebuffer resolution (see [DrawableFactory]). Sizes everywhere are design units.
 * Rebuild when the framebuffer height changes noticeably; dispose the old one afterwards.
 */
class UiSkin(val tokens: Tokens, framebufferHeight: Int) : Disposable {
    val fonts = Fonts(framebufferHeight, tokens)
    private val shapes = DrawableFactory(fonts.scale)
    val skin = Skin()

    /** Drawables that other code needs by name (logo, cards). */
    val logoRing: Drawable = shapes.roundedRect(LOGO_SIZE / 2, null, tokens.accent, LOGO_RING)
    val logoDot: Drawable = shapes.circle(LOGO_DOT, tokens.accent)
    val whiteSolid: Drawable = shapes.solid(Color.WHITE)

    /** White soft disc for LED glows and halos; tint it with the actor color. */
    val glow: Drawable = shapes.glow(GLOW_SIZE)

    /** The window behind modules: a dark scrim for overlays over the 3D room. */
    val scrim: Drawable = shapes.solid(Color(0f, 0f, 0f, SCRIM_ALPHA))

    private val icons = mutableMapOf<Pair<Icon, Float>, Drawable>()

    /** A white [icon] of [size] units, cached per skin; tint it with the actor color. */
    fun icon(icon: Icon, size: Float = ICON_SIZE): Drawable = icons.getOrPut(icon to size) { shapes.icon(icon, size) }

    private val dots = mutableMapOf<Pair<Int, Float>, Drawable>()

    /** A filled circle of [diameter] design units, cached per skin (chart dots, legend marks, LEDs). */
    fun dot(color: Color, diameter: Float): Drawable =
        dots.getOrPut(Color.rgba8888(color) to diameter) { shapes.circle(diameter, color) }

    /** Filled and empty segments of the step indicator. */
    val stepOn: Drawable = shapes.pill(STEP_HEIGHT, tokens.accent)
    val stepOff: Drawable = shapes.pill(STEP_HEIGHT, tokens.neutral(800))

    private val muted = tokens.neutral(400)
    private val faint = tokens.neutral(500)
    private val readoutColor = tokens.accent(300)

    init {
        addLabels()
        addButtons()
        addInputs()
        addSurfaces()
        addSlidersAndBars()
        addSelectBox()
    }

    private fun addLabels() {
        label(Styles.BODY, fonts.body, tokens.text)
        label(Styles.BODY_MUTED, fonts.body, muted)
        label(Styles.BODY_LG, fonts.bodyLg, tokens.text)
        label(Styles.CAPTION_MUTED, fonts.caption, faint)
        label(Styles.LABEL, fonts.label, muted)
        label(Styles.BRAND, fonts.brand, tokens.text)
        label(Styles.TITLE, fonts.title, tokens.text)
        label(Styles.HEADING, fonts.heading, tokens.text)
        label(Styles.MONO, fonts.mono, tokens.text)
        label(Styles.MONO_MUTED, fonts.mono, faint)
        label(Styles.NUMBER_MD, fonts.numberMd, tokens.text)
        label(Styles.NUMBER_LG, fonts.numberLg, readoutColor)
        label(Styles.NUMBER_XL, fonts.numberXl, readoutColor)
        label(Styles.READOUT, fonts.readout, readoutColor)
        label(Styles.OK, fonts.mono, tokens.ok)
        label(Styles.CAUTION, fonts.mono, tokens.caution)
    }

    private fun addButtons() {
        val r = tokens.radiusMd
        // Primary: the one amber key per screen. Filled, dark text, darker when pressed.
        skin.add(Styles.PRIMARY, TextButton.TextButtonStyle().apply {
            font = fonts.strong
            fontColor = tokens.onAccent
            overFontColor = tokens.onAccent
            downFontColor = tokens.onAccent
            disabledFontColor = tokens.neutral(600)
            up = shapes.bevel(r, tokens.accent, tokens.accent(300), tokens.accent(700))
            over = shapes.bevel(r, tokens.accent(400), tokens.accent(200), tokens.accent(600))
            down = shapes.bevel(r, tokens.accent(600), tokens.accent(700), tokens.accent(500))
            disabled = shapes.bevel(r, tokens.neutral(900), tokens.neutral(800), tokens.moduleLo)
        }, TextButton.TextButtonStyle::class.java)
        // Secondary: a raised graphite key.
        skin.add(Styles.SECONDARY, TextButton.TextButtonStyle().apply {
            font = fonts.bodyMedium
            fontColor = tokens.text
            overFontColor = tokens.text
            downFontColor = tokens.text
            disabledFontColor = tokens.neutral(600)
            up = keyUp(r)
            over = shapes.bevel(r, tokens.neutral(800), tokens.neutral(700), tokens.moduleLo)
            down = shapes.bevel(r, tokens.well, tokens.moduleLo, tokens.moduleHi)
            disabled = shapes.bevel(r, tokens.module, tokens.neutral(900), tokens.moduleLo)
        }, TextButton.TextButtonStyle::class.java)
        skin.add(Styles.TEXT, TextButton.TextButtonStyle().apply {
            font = fonts.bodyMedium
            fontColor = tokens.accent(400)
            overFontColor = tokens.accent(200)
            downFontColor = tokens.accent(200)
            disabledFontColor = tokens.neutral(700)
        }, TextButton.TextButtonStyle::class.java)
        skin.add(Styles.GHOST, TextButton.TextButtonStyle().apply {
            font = fonts.bodyMedium
            fontColor = muted
            overFontColor = tokens.text
            downFontColor = tokens.accent(300)
            checkedFontColor = tokens.text
            disabledFontColor = tokens.neutral(700)
            over = shapes.roundedRect(tokens.radiusSm, tokens.neutral(900))
            down = shapes.roundedRect(tokens.radiusSm, tokens.well)
        }, TextButton.TextButtonStyle::class.java)
        // Tiles and chips light up like a latched key: recessed, amber edge.
        val latched = shapes.bevel(r, tokens.accent(900), tokens.accent(700), tokens.accent(800), LATCH_BORDER)
        skin.add(Styles.TILE, TextButton.TextButtonStyle().apply {
            font = fonts.bodyLg
            fontColor = tokens.text
            disabledFontColor = tokens.neutral(600)
            up = keyUp(r)
            over = shapes.bevel(r, tokens.neutral(800), tokens.neutral(700), tokens.moduleLo)
            down = latched
            checked = latched
            checkedOver = latched
            checkedDown = latched
            checkedFontColor = tokens.accent(200)
        }, TextButton.TextButtonStyle::class.java)
        skin.add(Styles.CHIP, TextButton.TextButtonStyle().apply {
            font = fonts.caption
            fontColor = muted
            overFontColor = tokens.text
            checkedFontColor = tokens.text
            up = shapes.roundedRect(CHIP_RADIUS, tokens.well, tokens.neutral(800), HAIRLINE)
            over = shapes.roundedRect(CHIP_RADIUS, tokens.neutral(900), tokens.neutral(700), HAIRLINE)
            down = shapes.roundedRect(CHIP_RADIUS, tokens.well, tokens.accent(700), HAIRLINE)
            checked = shapes.roundedRect(CHIP_RADIUS, tokens.well, tokens.neutral(700), HAIRLINE)
            checkedOver = checked
        }, TextButton.TextButtonStyle::class.java)
        // A recessed readout that opens something: the setup pill in the top bar.
        skin.add(Styles.WELL_BUTTON, Button.ButtonStyle().apply {
            up = shapes.bevel(CHIP_RADIUS, tokens.well, tokens.moduleLo, tokens.neutral(900))
            over = shapes.roundedRect(CHIP_RADIUS, tokens.well, tokens.neutral(700), HAIRLINE)
            down = shapes.roundedRect(CHIP_RADIUS, tokens.well, tokens.accent(700), HAIRLINE)
            checked = shapes.roundedRect(CHIP_RADIUS, tokens.well, tokens.accent(600), HAIRLINE)
        }, Button.ButtonStyle::class.java)
        skin.add(Styles.CARD_BUTTON, Button.ButtonStyle().apply {
            up = keyUp(r)
            over = shapes.bevel(r, tokens.neutral(900), tokens.neutral(700), tokens.moduleLo)
            down = latched
            checked = latched
            checkedOver = latched
            checkedDown = latched
        }, Button.ButtonStyle::class.java)
    }

    private fun keyUp(r: Float) = shapes.bevel(r, tokens.neutral(900), tokens.moduleHi, tokens.moduleLo)

    private fun addInputs() {
        fun field(font: BitmapFont, height: Float) = TextField.TextFieldStyle().apply {
            this.font = font
            fontColor = tokens.text
            messageFont = font
            messageFontColor = tokens.neutral(600)
            // Inputs are recessed: shadow on top, light at the bottom edge.
            background = shapes.bevel(tokens.radiusMd, tokens.well, tokens.moduleLo, tokens.neutral(800))
            focusedBackground = shapes.roundedRect(tokens.radiusMd, tokens.well, tokens.accent, OUTLINE)
            disabledBackground = shapes.roundedRect(tokens.radiusMd, tokens.module, tokens.neutral(900), HAIRLINE)
            cursor = shapes.solid(tokens.accent(400)).also { it.minWidth = CURSOR_WIDTH }
            selection = shapes.solid(Color(tokens.accent.r, tokens.accent.g, tokens.accent.b, SELECTION_ALPHA))
            listOf(background, focusedBackground, disabledBackground).forEach {
                it.leftWidth = FIELD_PADDING
                it.rightWidth = FIELD_PADDING
                it.minHeight = height
            }
        }
        skin.add(Styles.INPUT_LG, field(fonts.numberMd, INPUT_LG_HEIGHT), TextField.TextFieldStyle::class.java)
        skin.add(Styles.INPUT, field(fonts.mono, INPUT_HEIGHT), TextField.TextFieldStyle::class.java)
    }

    private fun addSurfaces() {
        val module = shapes.bevel(tokens.radiusLg, tokens.module, tokens.moduleHi, tokens.moduleLo)
        skin.add(Styles.MODULE, module, Drawable::class.java)
        skin.add(Styles.CARD, shapes.bevel(tokens.radiusMd, tokens.module, tokens.moduleHi, tokens.moduleLo), Drawable::class.java)
        skin.add(Styles.WELL, shapes.bevel(tokens.radiusMd, tokens.well, tokens.moduleLo, tokens.neutral(900)), Drawable::class.java)
    }

    private fun addSlidersAndBars() {
        // A fader: recessed slot, amber travel, a flat cap that reads as a hardware knob.
        skin.add("default-horizontal", Slider.SliderStyle().apply {
            background = shapes.pill(TRACK_HEIGHT, tokens.well)
            knobBefore = shapes.pill(TRACK_HEIGHT, tokens.accent(600))
            knob = shapes.bevel(FADER_RADIUS, tokens.neutral(200), tokens.neutral(100), tokens.neutral(500)).also {
                it.minWidth = FADER_WIDTH
                it.minHeight = FADER_HEIGHT
            }
            knobOver = shapes.bevel(FADER_RADIUS, tokens.neutral(100), Color.WHITE, tokens.neutral(400)).also {
                it.minWidth = FADER_WIDTH
                it.minHeight = FADER_HEIGHT
            }
        }, Slider.SliderStyle::class.java)
        val checkOn = LayeredDrawable(
            shapes.roundedRect(tokens.radiusSm, tokens.accent),
            icon(Icon.CHECK, CHECK_SIZE * CHECK_ICON),
            tokens.onAccent,
        ).also { size(it, CHECK_SIZE) }
        skin.add("default", CheckBox.CheckBoxStyle().apply {
            font = fonts.body
            fontColor = tokens.text
            checkboxOff = shapes.bevel(tokens.radiusSm, tokens.well, tokens.moduleLo, tokens.neutral(700)).also { size(it, CHECK_SIZE) }
            checkboxOver = shapes.roundedRect(tokens.radiusSm, tokens.well, tokens.neutral(500), HAIRLINE).also { size(it, CHECK_SIZE) }
            checkboxOn = checkOn
            checkboxOnOver = checkOn
        }, CheckBox.CheckBoxStyle::class.java)
    }

    private fun addSelectBox() {
        val list = List.ListStyle().apply {
            font = fonts.body
            fontColorSelected = tokens.text
            fontColorUnselected = tokens.neutral(300)
            selection = shapes.roundedRect(tokens.radiusSm, tokens.accent(900)).also {
                it.leftWidth = LIST_PADDING
                it.rightWidth = LIST_PADDING
                it.topHeight = LIST_ITEM_PADDING
                it.bottomHeight = LIST_ITEM_PADDING
            }
            over = shapes.roundedRect(tokens.radiusSm, tokens.neutral(900)).also {
                it.leftWidth = LIST_PADDING
                it.rightWidth = LIST_PADDING
                it.topHeight = LIST_ITEM_PADDING
                it.bottomHeight = LIST_ITEM_PADDING
            }
        }
        // The page scroll of the screen frame: no background, a thin knob that shows there is more below.
        skin.add(Styles.FRAME_SCROLL, ScrollPane.ScrollPaneStyle().apply {
            vScrollKnob = shapes.roundedRect(tokens.radiusSm, tokens.neutral(700)).also { it.minWidth = FRAME_SCROLL_KNOB }
        })
        val scroll = ScrollPane.ScrollPaneStyle().apply {
            background = shapes.roundedRect(tokens.radiusMd, tokens.neutral(900), tokens.neutral(700), HAIRLINE)
        }
        val chevron = icon(Icon.CHEVRON_DOWN, CHEVRON_SIZE)
        fun box(fill: Color, top: Color, bottom: Color, border: Float = 1f) =
            LayeredDrawable(shapes.bevel(tokens.radiusMd, fill, top, bottom, border), chevron, muted, iconRight = FIELD_PADDING).also {
                it.leftWidth = FIELD_PADDING
                it.rightWidth = FIELD_PADDING + CHEVRON_SIZE + CHEVRON_GAP
                it.minHeight = INPUT_HEIGHT
            }
        skin.add("default", SelectBox.SelectBoxStyle().apply {
            font = fonts.body
            fontColor = tokens.text
            background = box(tokens.neutral(900), tokens.moduleHi, tokens.moduleLo)
            backgroundOver = box(tokens.neutral(800), tokens.neutral(700), tokens.moduleLo)
            backgroundOpen = box(tokens.well, tokens.accent(600), tokens.accent(700), OUTLINE)
            scrollStyle = scroll
            listStyle = list
        }, SelectBox.SelectBoxStyle::class.java)
    }

    private fun size(drawable: Drawable, size: Float) {
        drawable.minWidth = size
        drawable.minHeight = size
    }

    private fun label(name: String, font: BitmapFont, color: Color) {
        skin.add(name, Label.LabelStyle(font, color), Label.LabelStyle::class.java)
    }

    override fun dispose() {
        fonts.dispose()
        shapes.dispose()
    }

    companion object {
        const val ICON_SIZE = 20f
        private const val LOGO_SIZE = 28f
        private const val LOGO_RING = 2.5f
        private const val LOGO_DOT = 8f
        private const val GLOW_SIZE = 64f
        private const val SCRIM_ALPHA = 0.62f
        private const val OUTLINE = 2f
        private const val HAIRLINE = 1f
        private const val LATCH_BORDER = 1.5f
        private const val CHIP_RADIUS = 18f
        private const val FRAME_SCROLL_KNOB = 6f
        private const val FIELD_PADDING = 22f
        private const val CURSOR_WIDTH = 2f
        private const val SELECTION_ALPHA = 0.35f
        private const val INPUT_HEIGHT = 54f
        private const val INPUT_LG_HEIGHT = 84f
        private const val TRACK_HEIGHT = 6f
        private const val STEP_HEIGHT = 4f
        private const val FADER_WIDTH = 16f
        private const val FADER_HEIGHT = 28f
        private const val FADER_RADIUS = 4f
        private const val CHECK_SIZE = 26f
        private const val CHECK_ICON = 0.8f
        private const val CHEVRON_SIZE = 18f
        private const val CHEVRON_GAP = 10f
        private const val LIST_PADDING = 16f
        private const val LIST_ITEM_PADDING = 10f
    }
}
