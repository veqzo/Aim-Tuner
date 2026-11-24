package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.audio.FlashSoundEvent
import dev.veqzo.aimtune.app.i18n.Language
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.ModuleStatus
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.addButton
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.modulePanel
import dev.veqzo.aimtune.app.ui.onChange
import dev.veqzo.aimtune.app.update.AppVersion
import dev.veqzo.aimtune.storage.AppSettings

private const val LEFT_WIDTH = 940f
private const val RIGHT_WIDTH = 640f
private const val COLUMN_GAP = 72f
private const val SECTION_GAP = 44f
private const val CAPTION_GAP = 12f
private const val ROW_GAP = 16f
private const val CONTROL_HEIGHT = 56f
private const val NAME_WIDTH = 240f
private const val SLIDER_WIDTH = 380f
private const val VALUE_WIDTH = 90f
private const val PLAY_WIDTH = 150f
private const val CELL_GAP = 16f
private const val SELECT_WIDTH = 380f
private const val BUTTON_WIDTH = 520f
private const val FOOTER_BUTTON_HEIGHT = 64f
private const val VOLUME_STEP = 5f

/**
 * The app options, as one opened module: language, sound and flash (switches, volumes with a way to try them, the
 * brightness of the white-out), updates, and the stored rounds (share for research, start over). Every change is saved
 * at once, as on the back of the SENS module. The setup lives in the setup panel, the scoring on the SENS back.
 * A pop at full volume and a plain white screen for 1.5 s were too much (the player said it hurts), hence the levels.
 */
class SettingsScreen(ctx: AppContext) : UiScreen(ctx) {
    override val utility = Utility.SETTINGS

    private var message: Pair<String, Boolean>? = null
    private var updateStatus: String? = null
    private var startOver = StartOverState.IDLE
    private var swatch: Image? = null

    override fun build(): Actor {
        val current = ctx.settings.current
        val left = Table().top().left()
        left.section(t("settings.language"), first = true)
        left.add(languageSelect(current)).left().width(SELECT_WIDTH).height(CONTROL_HEIGHT).row()
        left.section(t("settings.sounds"))
        left.add(ui.label(t("sound.help"), Styles.CAPTION_MUTED, wrap = true)).left().width(LEFT_WIDTH).padBottom(ROW_GAP).row()
        left.add(soundRow(t("settings.flash_sound"), current.flashSound, current.flashVolumePercent, { s, on -> s.copy(flashSound = on) }, { s, v -> s.copy(flashVolumePercent = v) }) {
            ctx.flashSounds.play(FlashSoundEvent.POP, AppSettings.fraction(ctx.settings.current.flashVolumePercent))
        }).left().padBottom(ROW_GAP).row()
        left.add(soundRow(t("settings.hit_sound"), current.hitSound, current.hitVolumePercent, { s, on -> s.copy(hitSound = on) }, { s, v -> s.copy(hitVolumePercent = v) }) {
            ctx.hitSounds.preview(AppSettings.fraction(ctx.settings.current.hitVolumePercent))
        }).left().padBottom(ROW_GAP).row()
        left.add(brightnessRow(current)).left().row()
        left.add(ui.label(t("sound.brightness_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(LEFT_WIDTH).padTop(CAPTION_GAP).row()

        val right = Table().top().left()
        right.section(t("settings.updates"), first = true)
        right.add(ui.label(t("settings.version", AppVersion.current), Styles.MONO)).left().padBottom(ROW_GAP).row()
        right.add(updateToggle(current)).left().width(BUTTON_WIDTH).height(CONTROL_HEIGHT).padBottom(ROW_GAP).row()
        // The result is the label of the button, so the column keeps its height.
        right.add(ui.button(updateStatus ?: t("settings.update_now"), Styles.SECONDARY) { checkUpdateNow() }).left().width(BUTTON_WIDTH).height(CONTROL_HEIGHT).row()
        right.section(t("settings.data"))
        right.add(ui.label(t("settings.share_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(RIGHT_WIDTH).padBottom(ROW_GAP).row()
        right.add(ui.button(t("share.title"), Styles.SECONDARY) { ctx.showShareData(back = { ctx.showSettings() }) }).left().width(BUTTON_WIDTH).height(CONTROL_HEIGHT).padBottom(SECTION_GAP / 2).row()
        right.add(ui.label(t("settings.start_over_help"), Styles.CAPTION_MUTED, wrap = true)).left().width(RIGHT_WIDTH).padBottom(ROW_GAP).row()
        right.add(startOverButton()).left().width(BUTTON_WIDTH).height(CONTROL_HEIGHT).row()

        val body = Table().top().left()
        body.add(left).top().left().width(LEFT_WIDTH)
        body.add(right).top().left().width(RIGHT_WIDTH).padLeft(COLUMN_GAP)
        body.add().expandX()
        val panel = ui.modulePanel(t("settings.title"), ModuleStatus(LedState.OFF, t("settings.saved_at_once")), showLed = false)
        panel.add(body).grow()
        val content = Table()
        content.add(panel).grow()
        return frame(content, footer())
    }

    override fun onEscape() = ctx.showMenu()

    private fun footer(): Table {
        val footer = Table()
        footer.add(ui.button(t("common.back"), Styles.TEXT) { ctx.showMenu() }).left().height(FOOTER_BUTTON_HEIGHT).padLeft(8f)
        footer.add().expandX()
        message?.let { (text, ok) -> footer.add(ui.label(text, if (ok) Styles.OK else Styles.CAUTION)).right().padRight(32f) }
        footer.addButton(ui.button(t("settings.reset"), Styles.SECONDARY) { reset() }, minWidth = 280f)
        return footer
    }

    /** A caps title with space above it, except for the first section of a column. */
    private fun Table.section(title: String, first: Boolean = false) {
        add(ui.caps(title)).left().padTop(if (first) 0f else SECTION_GAP).padBottom(CAPTION_GAP).row()
    }

    /** Saves [next]; [refresh] rebuilds the screen, which a slider must not do while it is dragged. */
    private fun save(next: AppSettings, refresh: Boolean = true) {
        message = ctx.settings.save(next)?.let { it to false }
        if (refresh || message != null) rebuild()
    }

    private fun languageSelect(current: AppSettings): SelectBox<String> {
        val select = SelectBox<String>(ui.skin)
        select.setItems(*Language.entries.map { it.displayName }.toTypedArray())
        select.selected = Language.fromCode(current.language).displayName
        // The texts follow the saved language, so the rebuild after saving shows the screen in it.
        select.onChange { save(ctx.settings.current.copy(language = Language.entries.first { it.displayName == select.selected }.code)) }
        return select
    }

    /** One sound: the switch with its name, the volume and a button that plays it at that volume. */
    private fun soundRow(
        name: String,
        on: Boolean,
        volume: Int,
        withOn: (AppSettings, Boolean) -> AppSettings,
        withVolume: (AppSettings, Int) -> AppSettings,
        play: () -> Unit,
    ): Table {
        val row = Table()
        val toggle: TextButton = ui.button(name, Styles.TILE) { save(withOn(ctx.settings.current, !on)) }
        toggle.setProgrammaticChangeEvents(false)
        toggle.isChecked = on
        row.add(toggle).width(NAME_WIDTH).height(CONTROL_HEIGHT)
        val shown = ui.label("$volume %", Styles.MONO)
        row.add(percentSlider(0f, volume, shown) { save(withVolume(ctx.settings.current, it), refresh = false) }).width(SLIDER_WIDTH).padLeft(CELL_GAP * 2)
        row.add(shown).right().width(VALUE_WIDTH).padLeft(CELL_GAP)
        row.add(ui.button(t("sound.play"), Styles.SECONDARY) { play() }).width(PLAY_WIDTH).height(CONTROL_HEIGHT).padLeft(CELL_GAP)
        return row
    }

    /** The brightness of the flash white-out, with a box that shows it. */
    private fun brightnessRow(current: AppSettings): Table {
        val row = Table()
        val value = current.flashBrightnessPercent
        val shown = ui.label("$value %", Styles.MONO)
        val preview = Image(ui.whiteSolid).also { it.color = level(value); swatch = it }
        row.add(ui.label(t("sound.flash_brightness"), Styles.BODY_MUTED)).left().width(NAME_WIDTH)
        row.add(percentSlider(AppSettings.MIN_FLASH_BRIGHTNESS_PERCENT.toFloat(), value, shown) {
            swatch?.color = level(it)
            save(ctx.settings.current.copy(flashBrightnessPercent = it), refresh = false)
        }).width(SLIDER_WIDTH).padLeft(CELL_GAP * 2)
        row.add(shown).right().width(VALUE_WIDTH).padLeft(CELL_GAP)
        row.add(preview).width(PLAY_WIDTH).height(CONTROL_HEIGHT).padLeft(CELL_GAP)
        return row
    }

    private fun percentSlider(min: Float, value: Int, shown: Label, onValue: (Int) -> Unit): Slider {
        val slider = Slider(min, 100f, VOLUME_STEP, false, ui.skin)
        slider.value = value.toFloat()
        slider.onChange {
            val percent = slider.value.toInt()
            shown.setText("$percent %")
            onValue(percent)
        }
        return slider
    }

    private fun level(percent: Int): Color = AppSettings.fraction(percent).let { Color(it, it, it, 1f) }

    /** The switch for the daily update check. */
    private fun updateToggle(current: AppSettings): TextButton {
        val on = current.checkForUpdates
        val button: TextButton = ui.button(t("settings.update_check", t(if (on) "settings.on" else "settings.off")), Styles.TILE) {
            save(ctx.settings.current.copy(checkForUpdates = !on))
        }
        button.setProgrammaticChangeEvents(false)
        button.isChecked = on
        return button
    }

    /** Asks GitHub now and says on the button what came back; a new version is then also offered on the dashboard. */
    private fun checkUpdateNow() {
        updateStatus = t("settings.update_checking")
        rebuild()
        ctx.checkForUpdateNow { release ->
            updateStatus = when {
                release == null -> t("settings.update_failed")
                release.version > AppVersion.version -> t("settings.update_found", release.version.toString())
                else -> t("settings.update_none", AppVersion.current)
            }
            if (ctx.currentScreen === this) rebuild()
        }
    }

    /**
     * Deletes all rounds after a backup; the settings stay. It takes two clicks: the first only arms it and says what will happen.
     * The outcome is the label of the button.
     */
    private fun startOverButton(): TextButton {
        val text = when (startOver) {
            StartOverState.IDLE -> t("settings.start_over")
            StartOverState.ARMED -> t("settings.start_over_confirm")
            StartOverState.RUNNING -> t("settings.start_over_running")
            StartOverState.DONE -> t("settings.start_over_done")
            StartOverState.FAILED -> t("settings.start_over_failed")
        }
        return ui.button(text, Styles.SECONDARY) {
            when (startOver) {
                StartOverState.RUNNING -> Unit
                StartOverState.ARMED -> {
                    startOver = StartOverState.RUNNING
                    ctx.startOver { result ->
                        startOver = if (result.isSuccess) StartOverState.DONE else StartOverState.FAILED
                        if (ctx.currentScreen === this) rebuild()
                    }
                }
                else -> startOver = StartOverState.ARMED
            }
            rebuild()
        }
    }

    /** Back to the default sounds, levels and update check; the language stays. */
    private fun reset() {
        save(
            ctx.settings.current.copy(
                flashSound = true,
                hitSound = true,
                flashVolumePercent = AppSettings.DEFAULT_FLASH_VOLUME_PERCENT,
                hitVolumePercent = AppSettings.DEFAULT_HIT_VOLUME_PERCENT,
                flashBrightnessPercent = AppSettings.DEFAULT_FLASH_BRIGHTNESS_PERCENT,
                checkForUpdates = true,
            ),
        )
        if (message == null) {
            message = t("settings.reset_done") to true
            rebuild()
        }
    }

    private enum class StartOverState { IDLE, ARMED, RUNNING, DONE, FAILED }
}
