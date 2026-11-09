package de.yuuto.aimtune.app.screens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import de.yuuto.aimtune.app.AppContext
import de.yuuto.aimtune.app.audio.FlashSoundEvent
import de.yuuto.aimtune.app.ui.Styles
import de.yuuto.aimtune.app.ui.addButton
import de.yuuto.aimtune.app.ui.button
import de.yuuto.aimtune.app.ui.frame
import de.yuuto.aimtune.app.ui.label
import de.yuuto.aimtune.app.ui.onChange
import de.yuuto.aimtune.storage.AppSettings

private const val LABEL_WIDTH = 360f
private const val SLIDER_WIDTH = 520f
private const val VALUE_WIDTH = 100f
private const val ROW_GAP = 36f
private const val PLAY_WIDTH = 180f
private const val CONTROL_HEIGHT = 56f
private const val HELP_WIDTH = 1000f
private const val SWATCH_WIDTH = 180f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val VOLUME_STEP = 5f

/**
 * Volume of the flash and hit sounds and the brightness of the white-out of a flash, each with a way to try it. A pop at
 * full volume and a plain white screen for 1.5 s were too much (the player said it hurts); the values are kept when the
 * screen is left.
 */
class SoundSettingsScreen(ctx: AppContext) : UiScreen(ctx) {
    override val utility = Utility.SETTINGS

    private val current = ctx.settings.current
    private var flashVolume = current.flashVolumePercent
    private var hitVolume = current.hitVolumePercent
    private var brightness = current.flashBrightnessPercent
    private var swatch: Image? = null
    private var message: String? = null

    override fun build(): Actor {
        val content = Table()
        content.add(ui.label(t("sound.title"), Styles.HEADING)).left().padBottom(16f).row()
        content.add(ui.label(t("sound.help"), Styles.BODY_MUTED, wrap = true)).left().width(HELP_WIDTH).padBottom(ROW_GAP + 8f).row()
        content.add(volumeRow(t("sound.flash_volume"), flashVolume, { flashVolume = it }) { ctx.flashSounds.play(FlashSoundEvent.POP, AppSettings.fraction(flashVolume)) }).left().padBottom(ROW_GAP).row()
        content.add(volumeRow(t("sound.hit_volume"), hitVolume, { hitVolume = it }) { ctx.hitSounds.preview(AppSettings.fraction(hitVolume)) }).left().padBottom(ROW_GAP).row()
        content.add(brightnessRow()).left().padBottom(12f).row()
        content.add(ui.label(t("sound.brightness_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(HELP_WIDTH).row()

        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { leave() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        message?.let { footer.add(ui.label(it, Styles.OK)).right().padRight(32f) }
        footer.addButton(ui.button(t("settings.reset"), Styles.SECONDARY) { reset() }, minWidth = 280f)
        return frame(content, footer)
    }

    override fun onEscape() = leave()

    /** Keeps the values and goes back to the settings. */
    private fun leave() {
        ctx.settings.save(
            ctx.settings.current.copy(flashVolumePercent = flashVolume, hitVolumePercent = hitVolume, flashBrightnessPercent = brightness),
        )
        ctx.showSettings(ctx.settingsReturn)
    }

    private fun reset() {
        flashVolume = AppSettings.DEFAULT_FLASH_VOLUME_PERCENT
        hitVolume = AppSettings.DEFAULT_HIT_VOLUME_PERCENT
        brightness = AppSettings.DEFAULT_FLASH_BRIGHTNESS_PERCENT
        message = t("settings.reset_done")
        rebuild()
    }

    private fun volumeRow(name: String, value: Int, onValue: (Int) -> Unit, play: () -> Unit): Table {
        val row = Table()
        val shown = ui.label("$value %", Styles.MONO)
        row.add(ui.label(name, Styles.BODY_MUTED)).left().width(LABEL_WIDTH)
        row.add(percentSlider(0f, value, shown, onValue)).width(SLIDER_WIDTH)
        row.add(shown).right().width(VALUE_WIDTH).padLeft(16f)
        row.add(ui.button(t("sound.play"), Styles.SECONDARY) { play() }).width(PLAY_WIDTH).height(CONTROL_HEIGHT).padLeft(24f)
        return row
    }

    private fun brightnessRow(): Table {
        val row = Table()
        val shown = ui.label("$brightness %", Styles.MONO)
        val preview = Image(ui.whiteSolid).also { it.color = level(brightness); swatch = it }
        row.add(ui.label(t("sound.flash_brightness"), Styles.BODY_MUTED)).left().width(LABEL_WIDTH)
        row.add(percentSlider(AppSettings.MIN_FLASH_BRIGHTNESS_PERCENT.toFloat(), brightness, shown) { brightness = it; swatch?.color = level(it) }).width(SLIDER_WIDTH)
        row.add(shown).right().width(VALUE_WIDTH).padLeft(16f)
        row.add(preview).width(SWATCH_WIDTH).height(CONTROL_HEIGHT).padLeft(24f)
        return row
    }

    private fun percentSlider(min: Float, value: Int, shown: Label, onValue: (Int) -> Unit): Slider {
        val slider = Slider(min, 100f, VOLUME_STEP, false, ui.skin)
        slider.value = value.toFloat()
        slider.onChange {
            val percent = slider.value.toInt()
            onValue(percent)
            shown.setText("$percent %")
            message = null
        }
        return slider
    }

    private fun level(percent: Int): Color = AppSettings.fraction(percent).let { Color(it, it, it, 1f) }
}
