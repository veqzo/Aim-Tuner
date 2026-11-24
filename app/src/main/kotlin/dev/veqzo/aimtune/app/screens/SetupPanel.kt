package dev.veqzo.aimtune.app.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.setup.GAME_CUSTOM
import dev.veqzo.aimtune.app.setup.PadPreset
import dev.veqzo.aimtune.app.setup.SetupDraft
import dev.veqzo.aimtune.app.setup.SetupStep
import dev.veqzo.aimtune.app.ui.Icon
import dev.veqzo.aimtune.app.ui.Reveal
import dev.veqzo.aimtune.app.ui.Styles
import dev.veqzo.aimtune.app.ui.UiSkin
import dev.veqzo.aimtune.app.ui.button
import dev.veqzo.aimtune.app.ui.caps
import dev.veqzo.aimtune.app.ui.iconButton
import dev.veqzo.aimtune.app.ui.label
import dev.veqzo.aimtune.app.ui.onChange
import dev.veqzo.aimtune.core.score.FlashImportance
import dev.veqzo.aimtune.core.units.AimStyle
import java.util.Locale

private const val PANEL_WIDTH = 720f
private const val PANEL_PADDING = 40f
private const val INNER_WIDTH = PANEL_WIDTH - 2 * PANEL_PADDING
private const val FIELD_GAP = 16f
private const val HALF_WIDTH = (INNER_WIDTH - FIELD_GAP) / 2
private const val CONTROL_HEIGHT = 52f
private const val CAPTION_GAP = 8f
private const val GROUP_GAP = 24f
private const val TITLE_GAP = 28f
private const val TILE_GAP = 8f
private const val PANEL_TOP_GAP = 12f
private const val BUTTON_HEIGHT = 60f
private const val SCROLL_GUTTER = 20f
private const val LIVE_PADDING_X = 24f
private const val LIVE_PADDING_Y = 16f
private const val LIVE_UNIT_LIFT = 6f

/** The open setup editor of a screen: the draft as typed and the last problem found on save. Kept outside the widgets. */
internal class SetupEditor(var draft: SetupDraft) {
    var message: String? = null

    /** False until the panel was shown once, so it drops in on opening and not on every rebuild. */
    var shown = false
}

/**
 * The setup panel: game, DPI and sensitivity with the live cm/360°, aim style, mousepad and flash
 * importance. It drops from the setup pill over the dimmed screen; a click on the dim, Cancel or
 * Escape closes it, Save or Enter keeps the values. The draft and its checks are [SetupDraft]'s.
 */
internal class SetupPanel(
    private val ctx: AppContext,
    private val ui: UiSkin,
    private val editor: SetupEditor,
    private val translate: (String, Array<out Any?>) -> String,
    private val rebuild: () -> Unit,
    private val save: () -> Unit,
    private val cancel: () -> Unit,
) {
    private var liveWell: Table? = null

    private fun t(key: String, vararg args: Any?): String = translate(key, args)

    private var draft: SetupDraft
        get() = editor.draft
        set(value) {
            editor.draft = value
        }

    /** The scrim and the panel, to lay over the whole stage. */
    fun overlay(): Actor {
        val scrim = Image(ui.scrim)
        scrim.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) = cancel()
        })
        val layer = Table()
        layer.top().right()
        val panel = panel()
        val dropped = if (editor.shown) panel else Reveal(panel).play(ui.tokens.motionBase)
        editor.shown = true
        layer.add(dropped).width(PANEL_WIDTH).padTop(ui.tokens.headerHeight + PANEL_TOP_GAP).padRight(ui.tokens.margin).padBottom(ui.tokens.margin)
        val stack = Stack(scrim, layer)
        stack.setFillParent(true)
        return stack
    }

    private fun panel(): Table {
        val body = Table().top().left()
        val head = Table()
        head.add(ui.label(t("setup.panel.title"), Styles.TITLE)).left().expandX()
        head.add(ui.iconButton("", Icon.CLOSE) { cancel() }).size(CONTROL_HEIGHT)
        body.add(head).width(INNER_WIDTH).padBottom(TITLE_GAP).row()
        group(body, t("settings.game"), gameSelect())
        if (draft.gameId == GAME_CUSTOM) {
            group(body, null, pair(t("settings.yaw"), field(draft.customYawText) { draft = draft.copy(customYawText = it) }, t("settings.fov"), field(draft.customFovText) { draft = draft.copy(customFovText = it) }))
        }
        group(body, null, pair(t("settings.dpi"), field(draft.dpiText) { draft = draft.copy(dpiText = it) }, t("settings.sensitivity"), field(draft.sensitivityText) { draft = draft.copy(sensitivityText = it) }))
        body.add(liveReadout()).width(INNER_WIDTH).padBottom(GROUP_GAP).row()
        group(body, t("setup.panel.style"), styleTiles())
        group(body, t("onboarding.pad.title"), padTiles())
        if (draft.padPreset == PadPreset.CUSTOM) {
            group(body, null, pair(t("setup.panel.pad_width"), field(draft.customPadWidthText) { draft = draft.copy(customPadWidthText = it) }, t("setup.panel.pad_height"), field(draft.customPadHeightText) { draft = draft.copy(customPadHeightText = it) }))
        }
        group(body, t("settings.flash"), flashTiles())
        editor.message?.let { body.add(ui.label(it, Styles.CAUTION, wrap = true)).width(INNER_WIDTH).left().padBottom(FIELD_GAP).row() }
        val actions = Table()
        actions.add().expandX()
        actions.add(ui.button(t("setup.panel.cancel"), Styles.TEXT) { cancel() }).height(BUTTON_HEIGHT).padRight(FIELD_GAP)
        actions.add(ui.button(t("settings.save"), Styles.PRIMARY) { save() }).height(BUTTON_HEIGHT).minWidth(200f)

        body.padRight(SCROLL_GUTTER)
        val scroll = ScrollPane(body, ui.skin, Styles.FRAME_SCROLL)
        scroll.setScrollingDisabled(true, false)
        scroll.setOverscroll(false, false)
        scroll.setFadeScrollBars(false)
        val panel = Table()
        panel.background(ui.skin.getDrawable(Styles.MODULE))
        // The right padding is smaller by the scroll knob, so the knob sits in the margin and never covers a field.
        panel.pad(PANEL_PADDING, PANEL_PADDING, PANEL_PADDING, PANEL_PADDING - SCROLL_GUTTER)
        panel.add(scroll).grow().row()
        // Save and Cancel stay in view below the scrolling part.
        panel.add(actions).width(INNER_WIDTH).left().padTop(FIELD_GAP)
        // A click inside the panel must not reach the scrim below.
        panel.touchable = Touchable.enabled
        return panel
    }

    private fun group(body: Table, caption: String?, control: Actor) {
        if (caption != null) body.add(ui.caps(caption)).left().padBottom(CAPTION_GAP).row()
        body.add(control).left().width(INNER_WIDTH).padBottom(GROUP_GAP).row()
    }

    /** Two captioned fields side by side. */
    private fun pair(leftCaption: String, left: Actor, rightCaption: String, right: Actor): Table {
        val table = Table()
        table.add(ui.caps(leftCaption)).left().width(HALF_WIDTH).padBottom(CAPTION_GAP)
        table.add(ui.caps(rightCaption)).left().width(HALF_WIDTH).padLeft(FIELD_GAP).padBottom(CAPTION_GAP).row()
        table.add(left).width(HALF_WIDTH).height(CONTROL_HEIGHT)
        table.add(right).width(HALF_WIDTH).height(CONTROL_HEIGHT).padLeft(FIELD_GAP)
        return table
    }

    private fun field(text: String, onText: (String) -> Unit): TextField = ui.numberField(text) {
        editor.message = null
        onText(it)
        updateLive()
    }

    private fun liveReadout(): Table {
        val holder = Table()
        liveWell = holder
        fillLive(holder)
        return holder
    }

    /** The readout is rebuilt in place while typing; the panel keeps its fields and their focus. */
    private fun updateLive() {
        liveWell?.let(::fillLive)
    }

    private fun fillLive(holder: Table) {
        holder.clearChildren()
        // One compact row: the panel must fit 1080 high without scrolling; the big readout is the onboarding's.
        val well = Table()
        well.background(ui.skin.getDrawable(Styles.WELL))
        well.pad(LIVE_PADDING_Y, LIVE_PADDING_X, LIVE_PADDING_Y, LIVE_PADDING_X)
        well.add(ui.label(t("setup.panel.live"), Styles.LABEL)).left().expandX()
        val value = draft.currentCm360(ctx.profiles)
        well.add(ui.label(value?.let { "%.1f".format(Locale.ROOT, it) } ?: "--", Styles.NUMBER_MD)).right().bottom()
        well.add(ui.label("cm/360°", Styles.MONO_MUTED)).right().bottom().padLeft(CAPTION_GAP).padBottom(LIVE_UNIT_LIFT)
        holder.add(well).growX()
    }

    private fun gameSelect(): SelectBox<String> {
        val select = SelectBox<String>(ui.skin)
        val options = ctx.profiles.profiles.map { it.id to it.name } + (GAME_CUSTOM to t("onboarding.game.custom"))
        select.setItems(*options.map { it.second }.toTypedArray())
        select.selected = options.firstOrNull { it.first == draft.gameId }?.second
        select.onChange {
            draft = draft.copy(gameId = options.first { it.second == select.selected }.first)
            editor.message = null
            rebuild()
        }
        return select
    }

    private fun styleTiles(): Table = tiles(AimStyle.entries, { it == draft.aimStyle }, { t("onboarding.style.${it.name.lowercase()}") }) {
        draft = draft.copy(aimStyle = it)
    }

    private fun padTiles(): Table = tiles(PadPreset.entries, { it == draft.padPreset }, { t("onboarding.pad.${it.name.lowercase()}") }) {
        draft = draft.copy(padPreset = it)
    }

    private fun flashTiles(): Table = tiles(FlashImportance.entries, { it == draft.flashImportance }, { t("flash.importance.${it.name.lowercase()}") }) {
        draft = draft.copy(flashImportance = it)
    }

    /** One latched tile per entry in a row that fills the panel width; a pick rebuilds the panel. */
    private fun <T> tiles(entries: List<T>, selected: (T) -> Boolean, name: (T) -> String, pick: (T) -> Unit): Table =
        ui.choiceTiles(entries, INNER_WIDTH, CONTROL_HEIGHT, TILE_GAP, selected, name) {
            pick(it)
            editor.message = null
            rebuild()
        }

    companion object {
        /** The first problem of [draft] in any step as text, or null when it can be saved. */
        fun problem(ctx: AppContext, draft: SetupDraft, t: (String) -> String): String? =
            SetupStep.entries.firstNotNullOfOrNull { draft.issue(it, ctx.profiles) }?.let { t(it.key) }
    }
}
