package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.data.network.HttpTransportGate
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpGetLimits
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.network.ServiceHttpContext
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 初始 Meting song 数组协议；不猜 url_id/签名、不按曲名挑第一首，也不推断完整性或音质。 */
internal class MetingProtocolApi(private val http: BoundedHttpGetClient) {
    suspend fun resolve(trackId: String, use: MetingConfigurationUse, budget: PlaybackResolutionBudget): MetingResolvedSong {
        requireMetingTrackId(trackId)
        val context = use.contextFor(trackId)
        var protocolCompatible = false
        try {
            return budget.within {
                var target = use.configuration.endpoint.request(trackId)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    use.checkCurrent()
                    val remaining = budget.remainingMs()
                    val response = http.get(target, HttpGetLimits(remaining, minOf(3_000L, remaining), MetingLimits.JSON_BYTES), context)
                    if (response.location != null) {
                        budget.claimMetadataRedirect()
                        target = HttpTransportGate.redirect(response.url, response.location, context)
                        continue
                    }
                    val caller = currentCoroutineContext()
                    val json = StrictJson.decode(response.bytes,
                        JsonBounds(bytes = MetingLimits.JSON_BYTES, depth = 8, nodes = 2048,
                            stringChars = MetingLimits.URL_CHARACTERS, members = 64)) {
                        caller.ensureActive()
                        use.checkCurrent()
                    } as? JsonArray ?: throw MetingException(MetingIssue.PROTOCOL_INVALID)
                    protocolCompatible = true
                    if (json.values.isEmpty()) throw MetingException(MetingIssue.EMPTY_RESOURCE)
                    if (json.values.size > MetingLimits.RESULTS) throw MetingException(MetingIssue.MULTIPLE_RESULTS)
                    val rows = json.values.map { it as? JsonObject ?: throw MetingException(MetingIssue.PROTOCOL_INVALID) }
                    val selected: JsonObject
                    val match: MetingTrackMatch
                    if (rows.size == 1) {
                        selected = rows.single()
                        val id = reportedId(selected)
                        val source = reportedSource(selected)
                        if (id != null && id != trackId || source != null && source != "netease") {
                            throw MetingException(MetingIssue.CONFLICTING_TRACK)
                        }
                        match = if (id == trackId && source == "netease") MetingTrackMatch.EXACT_SERVICE_DECLARATION
                            else MetingTrackMatch.SINGLE_COMPATIBLE_UNVERIFIED
                    } else {
                        val exact = rows.filter { reportedId(it) == trackId && reportedSource(it) == "netease" }
                        if (exact.size != 1) throw MetingException(MetingIssue.MULTIPLE_RESULTS)
                        selected = exact.single()
                        match = MetingTrackMatch.EXACT_SERVICE_DECLARATION
                    }
                    val url = requiredUrl(selected, "url")
                    val artwork = optionalTarget(selected, "pic", response.url, use, context)
                    val lyrics = optionalTarget(selected, "lrc", response.url, use, context)
                    val song = MetingResolvedSong(
                        MediaHttpTarget.serviceReference(url, response.url, context),
                        artwork.first, lyrics.first, match, display(selected, "name"), display(selected, "artist"),
                        completeness(selected), artwork.second, lyrics.second)
                    currentCoroutineContext().ensureActive()
                    use.checkCurrent()
                    budget.remainingMs()
                    return@within song
                }
                @Suppress("UNREACHABLE_CODE")
                throw MetingException(MetingIssue.PROTOCOL_INVALID)
            }
        } catch (error: MetingException) {
            throw MetingException(error.issue, protocolCompatible && error.issue != MetingIssue.PROTOCOL_INVALID)
        } catch (error: NetworkException) { throw MetingException(metingNetworkIssue(error)) }
        catch (error: MediaTransportException) {
            use.checkCurrent()
            throw MetingException(if (error.issue == QueuePlaybackIssue.RESOURCE_EXPIRED) MetingIssue.CONFIGURATION_CHANGED else MetingIssue.RESOURCE_INVALID,
                protocolCompatible)
        } catch (error: QueuePlaybackException) { throw MetingException(when (error.failure.issue) {
            QueuePlaybackIssue.NETWORK_TIMEOUT -> MetingIssue.NETWORK_TIMEOUT
            QueuePlaybackIssue.RESOURCE_REDIRECT -> MetingIssue.REDIRECT_LIMIT
            else -> MetingIssue.PROTOCOL_INVALID
        }) }
    }

    private fun reportedId(row: JsonObject): String? = when (val id = row["id"]) {
        null, JsonNull -> null
        is JsonText -> checkedReportedId(id.value)
        is JsonNumber -> checkedReportedId(id.value)
        else -> throw MetingException(MetingIssue.PROTOCOL_INVALID)
    }
    private fun checkedReportedId(value: String): String = try { requireMetingTrackId(value) }
    catch (_: MetingException) { throw MetingException(MetingIssue.PROTOCOL_INVALID) }

    private fun reportedSource(row: JsonObject): String? = when (val source = row["server"]) {
        null, JsonNull -> null
        is JsonText -> source.value.takeIf { it.length in 1..32 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }
            ?: throw MetingException(MetingIssue.PROTOCOL_INVALID)
        else -> throw MetingException(MetingIssue.PROTOCOL_INVALID)
    }

    private fun requiredUrl(row: JsonObject, name: String): String = optionalUrl(row, name)
        ?: throw MetingException(MetingIssue.EMPTY_RESOURCE)
    private fun optionalUrl(row: JsonObject, name: String): String? = when (val value = row[name]) {
        null, JsonNull -> null
        is JsonText -> if (value.value.isEmpty()) null else value.value.takeIf {
            it.length <= MetingLimits.URL_CHARACTERS && it == it.trim()
        } ?: throw MetingException(MetingIssue.RESOURCE_INVALID)
        else -> throw MetingException(MetingIssue.PROTOCOL_INVALID)
    }
    private fun display(row: JsonObject, name: String): String? = when (val value = row[name]) {
        null, JsonNull -> null
        is JsonText -> value.value.takeIf { it.isNotBlank() && it.length <= MetingLimits.DISPLAY_CHARACTERS }
        else -> throw MetingException(MetingIssue.PROTOCOL_INVALID)
    }

    private fun optionalTarget(row: JsonObject, name: String, responseUrl: okhttp3.HttpUrl, use: MetingConfigurationUse,
        context: ServiceHttpContext): Pair<MediaHttpTarget?, MetingIssue?> {
        return try {
            val value = optionalUrl(row, name)
            (value?.let { MediaHttpTarget.serviceReference(it, responseUrl, context) }) to null
        } catch (error: MetingException) { null to error.issue }
        catch (_: MediaTransportException) {
            use.checkCurrent()
            null to MetingIssue.RESOURCE_INVALID
        }
    }

    // 初始数组未提供可证实完整性；可选 preview 布尔声明只确认试听，false 不能冒充完整版。
    private fun completeness(row: JsonObject): ResourceCompleteness = when (val preview = row["preview"]) {
        null, JsonNull -> ResourceCompleteness.UNKNOWN
        is JsonBoolean -> if (preview.value) ResourceCompleteness.PREVIEW_REPORTED else ResourceCompleteness.UNKNOWN
        else -> throw MetingException(MetingIssue.PROTOCOL_INVALID)
    }
}

internal fun metingNetworkIssue(error: NetworkException): MetingIssue = when (error.issue) {
    NetworkIssue.BUSY -> MetingIssue.BUSY
    NetworkIssue.CLOSED -> MetingIssue.CLOSED
    NetworkIssue.UNAVAILABLE -> MetingIssue.NETWORK_UNAVAILABLE
    NetworkIssue.TIMEOUT -> MetingIssue.NETWORK_TIMEOUT
    NetworkIssue.CERTIFICATE -> MetingIssue.CERTIFICATE
    NetworkIssue.REDIRECT -> MetingIssue.REDIRECT_LIMIT
    NetworkIssue.RESPONSE_TOO_LARGE -> MetingIssue.RESPONSE_TOO_LARGE
    NetworkIssue.INVALID_REQUEST -> MetingIssue.INVALID_ADDRESS
    NetworkIssue.INVALID_RESPONSE -> MetingIssue.PROTOCOL_INVALID
    NetworkIssue.HTTP -> when (error.statusCode) {
        401, 403 -> MetingIssue.AUTH_REQUIRED
        429 -> MetingIssue.RATE_LIMITED
        404, 410 -> MetingIssue.EMPTY_RESOURCE
        else -> MetingIssue.PROTOCOL_INVALID
    }
}
