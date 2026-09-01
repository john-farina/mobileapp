package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.util.CommonBuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reads the Stone channel server's branch listing.
 *
 * Every branch of the firmware fork publishes builds to a channel named after
 * it, so this is the list of branches that have ever produced an installable
 * build, newest build first. `stone` is the trunk; everything else is a feature
 * branch that `stone-cleanup.yml` retires once its work has landed -- so a
 * channel still being here means the branch is still live.
 *
 * Read-only on purpose. Moving a watch between channels needs the control
 * token, and that is not something to ship inside an app binary.
 */
class StoneChannels(private val httpClient: HttpClient) {
    private val logger = Logger.withTag("StoneChannels")

    val baseUrl: String? = CommonBuildKonfig.BUG_URL

    suspend fun channels(): Result<List<StoneChannel>> {
        val base = baseUrl ?: return Result.failure(IllegalStateException("No channel server configured"))
        return runCatching {
            val response = httpClient.get("$base/channels")
            if (response.status != HttpStatusCode.OK) {
                error("channel list failed: ${response.status}")
            }
            response.body<StoneChannelList>().channels.sortedWith(
                // trunk first, then most recently published
                compareByDescending<StoneChannel> { it.channel == TRUNK }
                    .thenByDescending { it.publishedAt ?: "" },
            )
        }.onFailure { logger.w(it) { "could not list channels: ${it.message}" } }
    }

    suspend fun builds(channel: String): Result<List<StoneBuild>> {
        val base = baseUrl ?: return Result.failure(IllegalStateException("No channel server configured"))
        return runCatching {
            // Channel names contain slashes (feat/thing), so they must be encoded
            // or the path structure changes meaning.
            val encoded = channel.split("/").joinToString("%2F") { it }
            val response = httpClient.get("$base/builds/$encoded")
            if (response.status != HttpStatusCode.OK) {
                error("build list failed: ${response.status}")
            }
            response.body<StoneBuildList>().builds
        }.onFailure { logger.w(it) { "could not list builds for $channel: ${it.message}" } }
    }

    companion object {
        const val TRUNK = "stone"
    }
}

@Serializable
data class StoneChannelList(val channels: List<StoneChannel> = emptyList())

@Serializable
data class StoneChannel(
    val channel: String,
    val version: String? = null,
    @SerialName("commit_short") val commitShort: String? = null,
    val base: String? = null,
    @SerialName("built_at") val builtAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
) {
    val isTrunk: Boolean get() = channel == StoneChannels.TRUNK
}

@Serializable
data class StoneBuildList(
    val channel: String? = null,
    val builds: List<StoneBuild> = emptyList(),
)

@Serializable
data class StoneBuild(
    val channel: String? = null,
    val version: String,
    @SerialName("commit_short") val commitShort: String? = null,
    val base: String? = null,
    val bundle: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val notes: List<String> = emptyList(),
    @SerialName("built_at") val builtAt: String? = null,
    @SerialName("run_url") val runUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
)
