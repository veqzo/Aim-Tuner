package dev.veqzo.aimtune.app.files

import dev.veqzo.aimtune.app.accel.sampleRefineText
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The import path of the open dialog, driven through [FakeFileDialogs] the way the import screen drives the real one. */
class FileDialogsTest {
    @TempDir
    lateinit var dir: Path

    private fun file(name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    /** What the screen does: ask the dialog, read the answer; null when cancelled. */
    private fun chooseAndRead(dialogs: FileDialogs): ImportFileResult? = dialogs.openJson("Open", dir)?.let(ImportFileReader::read)

    @Test
    fun `a valid Raw Accel file is read`() {
        val text = checkNotNull(sampleRefineText())
        val result = chooseAndRead(FakeFileDialogs(opens = listOf(file("settings.json", text))))
        assertEquals(ImportFileResult.Ok(text), result)
    }

    @Test
    fun `cancel gives null and shows nothing`() {
        val dialogs = FakeFileDialogs()
        assertNull(chooseAndRead(dialogs))
        assertEquals(listOf<Pair<String, Path?>>("Open" to dir), dialogs.openCalls)
    }

    @Test
    fun `a path that cannot be read is unreadable`() {
        assertIs<ImportFileResult.Unreadable>(ImportFileReader.read(dir.resolve("missing.json")))
        assertIs<ImportFileResult.Unreadable>(ImportFileReader.read(dir))
    }

    @Test
    fun `a file over one megabyte is too large and not read`() {
        val big = file("big.json", "{\"pad\": \"" + "x".repeat((ImportFileReader.MAX_BYTES + 1).toInt()) + "\"}")
        assertEquals(ImportFileResult.TooLarge, ImportFileReader.read(big))
    }

    @Test
    fun `text that is not JSON is refused`() {
        assertEquals(ImportFileResult.NotJson, ImportFileReader.read(file("notes.json", "this is not json {")))
    }

    @Test
    fun `JSON that is not a Raw Accel file is refused`() {
        assertIs<ImportFileResult.NotRawAccel>(ImportFileReader.read(file("list.json", "[1, 2, 3]")))
        assertIs<ImportFileResult.NotRawAccel>(ImportFileReader.read(file("other.json", "{\"hello\": \"world\"}")))
    }

    @Test
    fun `save dialog answers are replayed and recorded`() {
        val target = dir.resolve("out.json")
        val dialogs = FakeFileDialogs(saves = listOf(target, null))
        assertEquals(target, dialogs.saveJson("Save", dir.resolve("settings.json")))
        assertNull(dialogs.saveJson("Save", null))
        assertEquals(2, dialogs.saveCalls.size)
    }

    @Test
    fun `titles are made safe for tinyfd`() {
        assertEquals("Open Raw Accel settings.json", asciiSafe("Open \"Raw Accel\" settings.json"))
        assertEquals("Raw Accel settings.json oeffnen", asciiSafe("Raw Accel settings.json öffnen"))
        assertEquals("Ae Oe Ue ss", asciiSafe("Ä Ö Ü ß"))
        assertEquals("ab", asciiSafe("a…b"))
    }

    @Test
    fun `saving writes the text and creates missing folders`() {
        val target = dir.resolve("sub").resolve("settings.json")
        assertEquals(target, ExportFiles.saveText(target, "{}").getOrThrow())
        assertTrue(Files.readString(target) == "{}")
    }

    @Test
    fun `the suggested name of a patched copy follows the export folder naming`() {
        assertEquals("settings-default_AimTune.json", ExportFiles.patchedFileName("default (AimTune)"))
        assertEquals("settings-profile.json", ExportFiles.patchedFileName("???"))
    }
}
