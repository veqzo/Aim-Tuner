package dev.veqzo.aimtune.app.i18n

import dev.veqzo.aimtune.core.scenario.ScenarioKind

/** A text of the UI that is produced away from the screens (a HUD label) and translated where it is shown. */
data class UiText(val key: String, val args: List<Any?> = emptyList()) {
    constructor(key: String, vararg args: Any?) : this(key, args.toList())
}

fun I18n.t(text: UiText): String = t(text.key, *text.args.toTypedArray())

/** "Flick", "Target switching": the scenario as a name. */
fun I18n.scenarioName(kind: ScenarioKind): String = t("scenario.${kind.id}")

/** "flick round": the scenario as a kind of round, lower case in English. */
fun I18n.scenarioRound(kind: ScenarioKind): String = t("scenario.${kind.id}.round")
