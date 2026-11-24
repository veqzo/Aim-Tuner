package dev.veqzo.aimtune.app.screens

import dev.veqzo.aimtune.app.AppContext
import dev.veqzo.aimtune.app.pretest.CheckLevel
import dev.veqzo.aimtune.app.pretest.CheckRow
import dev.veqzo.aimtune.app.pretest.PreTestChecks
import dev.veqzo.aimtune.app.ui.CheckChip
import dev.veqzo.aimtune.app.ui.Disclosure
import dev.veqzo.aimtune.app.ui.LedState
import dev.veqzo.aimtune.app.ui.UiSkin

/** The LED of a check: green when it is fine, amber when it needs the player, off when it cannot be read. */
internal fun CheckLevel.led(): LedState = when (this) {
    CheckLevel.OK -> LedState.OK
    CheckLevel.ATTENTION -> LedState.CAUTION
    CheckLevel.UNKNOWN -> LedState.OFF
}

/**
 * A check as a chip at a start button. Raw Accel cannot be detected, so its click is the player's word that it is off or
 * linear (a second click takes it back); the pointer chip reads the Windows setting again through [onPointer].
 */
internal fun AppContext.checkChip(row: CheckRow, onPointer: () -> Unit, rebuild: () -> Unit): CheckChip {
    val text = i18n.t("dash.check.${row.id}") + ": " + i18n.t(row.state)
    val action: (() -> Unit)? = when (row.id) {
        PreTestChecks.RAW_ACCEL -> { { rawAccelConfirmed = !rawAccelConfirmed; rebuild() } }
        PreTestChecks.POINTER -> { { onPointer(); rebuild() } }
        else -> null
    }
    return CheckChip(text, row.level.led(), action)
}

/** The note on the first check that needs the player, its long text behind "Learn more"; null when every check is fine. */
internal fun AppContext.checkDetail(ui: UiSkin, rows: List<CheckRow>, width: Float, open: Boolean, onToggle: (Boolean) -> Unit): Disclosure? {
    val row = rows.firstOrNull { it.level == CheckLevel.ATTENTION } ?: return null
    val short = i18n.t("dash.check.${row.id}.short", row.detailArg)
    val more = if (row.detailArg == null) i18n.t(row.detail) else i18n.t(row.detail, row.detailArg)
    return Disclosure(ui, short, more, i18n.t("dash.more") to i18n.t("dash.less"), width, open, onToggle)
}
