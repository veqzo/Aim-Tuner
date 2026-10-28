package de.yuuto.aimtune.app.update

import de.yuuto.aimtune.core.update.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A file of a release the app may download itself: only a file of this repository's releases, with the size and the SHA-256 that
 * GitHub reports for it (the app checks the download against both before it runs anything).
 */
data class UpdateAsset(val name: String, val url: String, val size: Long, val sha256: String)

/** The newest published release: its version, the page to download it from, and the files the app could install it with. */
data class ReleaseInfo(val version: Version, val url: String, val assets: List<UpdateAsset> = emptyList()) {
    /** The installer of this release, when it has one the app may use. */
    val installer: UpdateAsset? get() = assets.firstOrNull { it.name.endsWith(INSTALLER_SUFFIX) }

    /** The portable zip of this release, when it has one the app may use. */
    val portableZip: UpdateAsset? get() = assets.firstOrNull { it.name.endsWith(ZIP_SUFFIX) }

    companion object {
        const val INSTALLER_SUFFIX = "-setup.exe"
        const val ZIP_SUFFIX = "-windows.zip"
    }
}

/**
 * Asks GitHub for the newest release of the repository. It tells the player about it, nothing more: no download, no
 * install. Any failure (no network, rate limit, a private repository, which GitHub answers with 404, odd JSON) gives
 * null, so the check can never get in the way of the app.
 */
class UpdateChecker(
    private val client: HttpClient = defaultClient(),
    private val apiBase: String = API_BASE,
    private val repo: String = REPO,
    private val userAgent: String = "AimTune/${AppVersion.current}",
) {
    private val log = System.getLogger("aimtune.update")

    fun latest(): ReleaseInfo? = try {
        val request = HttpRequest.newBuilder(URI.create("$apiBase/repos/$repo/releases/latest"))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) parse(response.body()) else null.also { log.log(System.Logger.Level.DEBUG, "update check: HTTP ${response.statusCode()}") }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: Exception) {
        log.log(System.Logger.Level.DEBUG, "update check failed: ${e.javaClass.simpleName}")
        null
    }

    internal fun parse(body: String): ReleaseInfo? {
        val release: JsonObject = Json.parseToJsonElement(body).jsonObject
        if (release["draft"]?.jsonPrimitive?.contentOrNull == "true" || release["prerelease"]?.jsonPrimitive?.contentOrNull == "true") return null
        val version = release["tag_name"]?.jsonPrimitive?.contentOrNull?.let(Version::parse) ?: return null
        // The link is opened in the browser: only ever a page of the repository itself.
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://github.com/$repo/") } ?: return null
        return ReleaseInfo(version, url, assetsOf(release))
    }

    /** The downloadable files that pass every check; a file that does not is left out, so a release without any is only shown, never installed. */
    private fun assetsOf(release: JsonObject): List<UpdateAsset> {
        val prefix = "https://github.com/$repo/releases/download/"
        val items = (release["assets"] as? JsonArray) ?: return emptyList()
        return items.mapNotNull { item ->
            val asset = item as? JsonObject ?: return@mapNotNull null
            val name = asset["name"]?.jsonPrimitive?.contentOrNull?.takeIf { SAFE_NAME.matches(it) } ?: return@mapNotNull null
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith(prefix) && it.endsWith("/$name") } ?: return@mapNotNull null
            val size = asset["size"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..MAX_ASSET_BYTES } ?: return@mapNotNull null
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.takeIf { SHA256.matches(it) } ?: return@mapNotNull null
            UpdateAsset(name, url, size, digest.lowercase())
        }
    }

    companion object {
        const val REPO = "Yuutokata/Aim-Tuner"
        const val API_BASE = "https://api.github.com"
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private const val MAX_ASSET_BYTES = 500L * 1024 * 1024
        private val CONNECT_TIMEOUT = Duration.ofSeconds(3)
        private val REQUEST_TIMEOUT = Duration.ofSeconds(5)

        fun defaultClient(): HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}
