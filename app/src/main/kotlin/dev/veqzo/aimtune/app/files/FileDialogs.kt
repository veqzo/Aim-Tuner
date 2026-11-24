package dev.veqzo.aimtune.app.files

import java.nio.file.Path

/** Native open and save dialogs for `.json` files. Both return null when the player cancels, which shows nothing. */
interface FileDialogs {
    /** @param defaultDir the folder the dialog starts in, or null for the system's choice. */
    fun openJson(title: String, defaultDir: Path?): Path?

    /** @param defaultPath the suggested folder and file name, or null for the system's choice. */
    fun saveJson(title: String, defaultPath: Path?): Path?
}

/** Answers with queued paths instead of opening a window: for tests and the screenshot tour. Null answers (cancel) when the queue is empty. */
class FakeFileDialogs(opens: List<Path?> = emptyList(), saves: List<Path?> = emptyList()) : FileDialogs {
    private val openAnswers = ArrayDeque(opens)
    private val saveAnswers = ArrayDeque(saves)

    /** The titles and default paths the dialogs were asked with, in call order. */
    val openCalls = mutableListOf<Pair<String, Path?>>()
    val saveCalls = mutableListOf<Pair<String, Path?>>()

    override fun openJson(title: String, defaultDir: Path?): Path? {
        openCalls += title to defaultDir
        return openAnswers.removeFirstOrNull()
    }

    override fun saveJson(title: String, defaultPath: Path?): Path? {
        saveCalls += title to defaultPath
        return saveAnswers.removeFirstOrNull()
    }
}
