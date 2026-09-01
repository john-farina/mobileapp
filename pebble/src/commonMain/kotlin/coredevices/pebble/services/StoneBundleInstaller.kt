package coredevices.pebble.services

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import okio.ByteString.Companion.toByteString

/**
 * Downloads a firmware bundle from the channel server and verifies it before it
 * is allowed anywhere near a watch.
 *
 * The verification is not ceremony. A truncated or corrupted image flashed to
 * the watch is exactly the failure that costs a PRF recovery, and the server
 * already publishes a sha256 for every build -- so refusing to install anything
 * whose bytes do not match is close to free.
 */
class StoneBundleInstaller(
    private val httpClient: HttpClient,
    private val channels: StoneChannels,
) {
    private val logger = Logger.withTag("StoneBundle")

    /** The public, unauthenticated URL a bundle is served from. */
    fun bundleUrl(channel: String, bundle: String): String? {
        val base = channels.baseUrl ?: return null
        val encodedChannel = channel.split("/").joinToString("%2F")
        return "$base/bundles/$encodedChannel/$bundle"
    }

    /**
     * Fetches [build] to [destination] and checks its digest.
     *
     * Fails rather than installing when the bundle publishes no sha256: an
     * unverifiable firmware image is not safer for being convenient.
     */
    suspend fun download(
        channel: String,
        build: StoneBuild,
        destination: Path,
    ): Result<Path> = runCatching {
        val bundle = build.bundle ?: error("build has no bundle to download")
        val url = bundleUrl(channel, bundle) ?: error("no channel server configured")
        val expected = build.sha256 ?: error("build publishes no sha256; refusing to install")

        // The bundle is executable code for the watch, so plaintext is not an option.
        require(url.startsWith("https://")) { "refusing to download firmware over a non-TLS URL" }

        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) error("download failed: ${response.status}")

        val bytes: ByteArray = response.body()

        build.size?.let { declared ->
            require(bytes.size.toLong() == declared) {
                "size mismatch: got ${bytes.size} bytes, expected $declared"
            }
        }

        val actual = bytes.toByteString().sha256().hex()
        require(actual.equals(expected, ignoreCase = true)) {
            "sha256 mismatch, refusing to install (expected $expected, got $actual)"
        }

        SystemFileSystem.sink(destination).buffered().use { it.write(bytes) }
        logger.i { "verified ${build.version}: ${bytes.size} bytes, sha256 ok" }
        destination
    }.onFailure { logger.w(it) { "bundle download failed: ${it.message}" } }
}
