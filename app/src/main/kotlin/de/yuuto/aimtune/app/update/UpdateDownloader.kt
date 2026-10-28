package de.yuuto.aimtune.app.update

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/** Why a download did not give a file the app may run. */
class UpdateDownloadException(message: String) : IOException(message)

/**
 * Downloads one [UpdateAsset] into [dir] and only keeps it when it is exactly what GitHub reported: an https answer from GitHub's own
 * hosts, as many bytes as announced (never more: the stream is cut at the announced size) and the announced SHA-256. The file is
 * written under a temporary name and moved into place at the end, so a half or wrong file never has the name of an update.
 */
class UpdateDownloader(
    private val dir: Path,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val trustedHost: (String) -> Boolean = ::isGitHubHost,
    private val requireHttps: Boolean = true,
) {
    /** Returns the verified file. [onProgress] gets the bytes so far and the announced total; [cancelled] is asked between reads. */
    fun download(asset: UpdateAsset, cancelled: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> }): Path {
        Files.createDirectories(dir)
        clearOld()
        // A fresh folder with a name nobody can guess holds this one file, so nothing else can sit next to it (a library an installer would load, an older file).
        val work = Files.createTempDirectory(dir, "download-")
        val target = work.resolve(asset.name)
        val partial = work.resolve(asset.name + ".part")
        var kept = false
        try {
            val request = HttpRequest.newBuilder(URI.create(asset.url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "AimTune/${AppVersion.current}").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                if (response.statusCode() != 200) throw UpdateDownloadException("HTTP ${response.statusCode()}")
                val finalUri = response.uri()
                if (requireHttps && finalUri.scheme != "https") throw UpdateDownloadException("the download is not served over https")
                if (!trustedHost(finalUri.host.orEmpty().lowercase())) throw UpdateDownloadException("the download comes from an unexpected host")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                Files.newOutputStream(partial).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (cancelled()) throw UpdateDownloadException("cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > asset.size) throw UpdateDownloadException("the file is larger than announced")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        onProgress(total, asset.size)
                    }
                }
                if (total != asset.size) throw UpdateDownloadException("the file is smaller than announced")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != asset.sha256) throw UpdateDownloadException("the checksum does not match")
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            kept = true
            return target
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UpdateDownloadException("interrupted")
        } finally {
            if (!kept) deleteTree(work)
        }
    }

    /** Checks [file] against [asset] again, right before it is run: the folder is the player's, so the check at the end of the download is not the last word. */
    fun verify(file: Path, asset: UpdateAsset) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (Files.size(file) != asset.size || digest.digest().joinToString("") { "%02x".format(it) } != asset.sha256) throw UpdateDownloadException("the file changed after it was checked")
    }

    /** Removes what earlier downloads left in [dir]. */
    private fun clearOld() {
        Files.newDirectoryStream(dir, "download-*").use { entries -> entries.forEach { deleteTree(it) } }
    }

    private fun deleteTree(path: Path) {
        runCatching { Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** GitHub serves release files from github.com and redirects to its own content hosts. */
        fun isGitHubHost(host: String): Boolean = host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }
}
