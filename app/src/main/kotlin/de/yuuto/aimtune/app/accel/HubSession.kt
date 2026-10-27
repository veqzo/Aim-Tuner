package de.yuuto.aimtune.app.accel

import de.yuuto.aimtune.app.SettingsHolder
import de.yuuto.aimtune.app.files.ImportFileReader
import de.yuuto.aimtune.app.files.ImportFileResult
import de.yuuto.aimtune.core.accel.RawAccelProfile
import de.yuuto.aimtune.core.accel.settings.PassiveResult
import de.yuuto.aimtune.storage.PassiveChanges
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** A refine run that ended with a clearly better curve: the file it started from, the profile before and the refined one. */
class RefineOutcome(val source: RefineSource, val profile: RawAccelProfile, val candidate: RawAccelProfile)

/**
 * What the Raw Accel hub keeps while the app runs: the imported profile (its path also in the settings), the last refine result,
 * the passive recommendations the player ticked for the patched copy ("kept for the session", off by default) and the passive
 * analyses, which are read again only when new rounds were stored. Screens never hold this state themselves.
 */
class HubSession(
    private val settings: SettingsHolder,
    private val loadAnalyses: () -> CompletableFuture<SettingsAnalyses>,
    private val storedRounds: () -> Int,
) {
    var imported: ImportedProfile? = null
        private set
    var refine: RefineOutcome? = null
        private set

    /** Stage 1 of the draft the player chose to export; it belongs to the imported profile it was made on. */
    var stage: DraftStage? = null
        private set
    private val ticks = mutableSetOf<RawAccelSetting>()
    private var analyses: CompletableFuture<SettingsAnalyses>? = null
    private var analysedAt = -1

    /** Remembers [profile] as the imported one and its file in the settings; a refine result of another file or profile is dropped. */
    fun setImported(profile: ImportedProfile) {
        imported = profile
        refine = refine?.takeIf { it.source === profile.source && it.profile.name == profile.profileName }
        stage = stage?.takeIf { it.source === profile.source && it.profileName == profile.profileName }
        val path = profile.path?.toString()
        if (settings.current.lastImportedProfilePath != path) settings.save(settings.current.copy(lastImportedProfilePath = path))
    }

    /** The imported profile; after a start the file named in the settings is read again (first profile), nothing if it is gone or unreadable. */
    fun restoreImported(): ImportedProfile? {
        if (imported != null) return imported
        val path = settings.current.lastImportedProfilePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        val text = (ImportFileReader.read(path) as? ImportFileResult.Ok)?.text ?: return null
        val source = RefineImport.parse(text, path.fileName.toString()).getOrNull() ?: return null
        val name = source.file.profileNames.firstOrNull() ?: return null
        imported = ImportedProfile(source, name, path)
        return imported
    }

    /** Shows [profile] without touching the settings: the screenshot tour. */
    fun useSample(profile: ImportedProfile?, outcome: RefineOutcome? = null) {
        imported = profile
        refine = outcome
    }

    fun rememberStage(stage: DraftStage) {
        this.stage = stage
    }

    fun rememberRefine(outcome: RefineOutcome) {
        refine = outcome
    }

    /** The analyses of the stored plain rounds; computed in the background, and again only after new rounds were stored. */
    fun analyses(): CompletableFuture<SettingsAnalyses> {
        val rounds = storedRounds()
        return analyses?.takeIf { analysedAt == rounds && !it.isCompletedExceptionally } ?: loadAnalyses().also {
            analyses = it
            analysedAt = rounds
        }
    }

    fun isTicked(setting: RawAccelSetting): Boolean = setting in ticks

    fun setTicked(setting: RawAccelSetting, on: Boolean) {
        require(setting.passive) { "${setting.id} cannot go into the patched copy" }
        if (on) ticks += setting else ticks -= setting
    }

    /** The ticked recommendations with their values; one whose analysis no longer recommends anything is left out. */
    fun passiveChanges(results: SettingsAnalyses?): PassiveChanges {
        fun value(setting: RawAccelSetting) =
            if (setting in ticks) (results?.result(setting) as? PassiveResult.Recommend)?.value else null
        return PassiveChanges(value(RawAccelSetting.ROTATION), value(RawAccelSetting.Y_X), value(RawAccelSetting.L_R))
    }

    /**
     * The patched copy for the export button: the refine result (when it belongs to the imported profile) plus the ticked
     * recommendations; null without an imported profile Raw Accel itself would accept.
     */
    fun patchedCopy(dpi: Double, results: SettingsAnalyses?): PatchedCopy? {
        val outcome = refine
        val current = imported
        val chosen = stage
        if (chosen != null && current != null && chosen.source === current.source && chosen.profileName == current.profileName) {
            val profile = current.profile(dpi) ?: return null
            return PatchedCopy(current.source, profile, null, passiveChanges(results), chosen)
        }
        if (outcome != null) return PatchedCopy(outcome.source, outcome.profile, outcome.candidate, passiveChanges(results))
        val profile = current?.profile(dpi) ?: return null
        return PatchedCopy(current.source, profile, null, passiveChanges(results))
    }
}
