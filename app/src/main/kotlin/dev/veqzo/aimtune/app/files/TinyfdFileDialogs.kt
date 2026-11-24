package dev.veqzo.aimtune.app.files

import com.badlogic.gdx.Gdx
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.io.File
import java.nio.file.Path

private const val FILTER_DESCRIPTION = "JSON files (*.json)"

/**
 * The system's file dialogs through tinyfd (bundled with LWJGL). The call blocks the render thread until the dialog closes, which
 * is fine on a menu screen. A caught cursor is released first, because a dialog opened under a hidden, locked cursor cannot be used.
 */
class TinyfdFileDialogs : FileDialogs {
    override fun openJson(title: String, defaultDir: Path?): Path? = modal {
        val start = defaultDir?.toString()?.let { if (it.endsWith(File.separator)) it else it + File.separator }
        MemoryStack.stackPush().use { stack ->
            TinyFileDialogs.tinyfd_openFileDialog(asciiSafe(title), start, jsonFilter(stack), FILTER_DESCRIPTION, false)
        }
    }

    override fun saveJson(title: String, defaultPath: Path?): Path? = modal {
        MemoryStack.stackPush().use { stack ->
            TinyFileDialogs.tinyfd_saveFileDialog(asciiSafe(title), defaultPath?.toString(), jsonFilter(stack), FILTER_DESCRIPTION)
        }
    }

    private fun jsonFilter(stack: MemoryStack) = stack.mallocPointer(1).apply {
        put(stack.UTF8("*.json"))
        flip()
    }

    /** Runs [dialog] with the cursor free and returns the chosen path; tinyfd returns null on cancel. */
    private fun modal(dialog: () -> String?): Path? {
        val caught = Gdx.input.isCursorCatched
        if (caught) Gdx.input.isCursorCatched = false
        try {
            return dialog()?.let { Path.of(it) }
        } finally {
            if (caught) Gdx.input.isCursorCatched = true
        }
    }
}

/**
 * [text] reduced to what tinyfd shows reliably on Windows: quotes break its title handling and non-ASCII characters can turn into
 * garbage. German umlauts are spelled out, anything else non-ASCII is dropped.
 */
fun asciiSafe(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '"', '\'', '`' -> {}
            'ä' -> append("ae")
            'ö' -> append("oe")
            'ü' -> append("ue")
            'Ä' -> append("Ae")
            'Ö' -> append("Oe")
            'Ü' -> append("Ue")
            'ß' -> append("ss")
            else -> if (c.code in 0x20..0x7e) append(c)
        }
    }
}
