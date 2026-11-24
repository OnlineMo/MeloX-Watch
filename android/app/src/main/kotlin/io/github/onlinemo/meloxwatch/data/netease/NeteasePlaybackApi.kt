/*
 * NetEase song_url_v1 endpoint and HTTPS promotion adapted narrowly from youshen2/MeloX,
 * commit 09713edb847a0f0a3515a34f779cc630d4fe5985,
 * MeloXWatch/Networking/WatchNeteaseAPI.swift. GPL-3.0; see LICENSE.
 * Android changes: one stable string ID, bounded response, actual server fields and credential isolation.
 */
package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.data.network.jsonObject
import io.github.onlinemo.meloxwatch.data.network.jsonString
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class NeteaseResolvedSource(
    val target: MediaHttpTarget,
    val reportedQuality: StreamingQuality?,
    val reportedBitrate: Long?,
    val completeness: ResourceCompleteness,
    val expiresElapsedMs: Long?,
) { override fun toString(): String = "NeteaseResolvedSource(redacted)" }

internal class NeteasePlaybackApi(private val eapi: NeteaseEapi) {
    suspend fun resolve(
        trackId: String,
        quality: StreamingQuality,
        credential: SessionCredential,
        budget: PlaybackResolutionBudget,
    ): NeteaseResolvedSource {
        if (!canonicalId(trackId)) invalid()
        budget.claimNativeRequest()
        return try {
            budget.within {
                val requestedAt = budget.elapsedNowMs()
                val response = eapi.post("/api/song/enhance/player/url/v1", mapOf(
                    "ids" to JsonText("[$trackId]"),
                    "level" to JsonText(if (quality == StreamingQuality.HIGH) "exhigh" else "standard"),
                    "encodeType" to JsonText("flac"),
                ), NeteaseCookie.fromCredential(credential), budget.metadataCall()).json
                checkCode(response["code"])
                val records = (response["data"] as? JsonArray)?.values ?: invalid()
                if (records.size != 1) invalid()
                val record = records.single().jsonObject()
                if (id(record["id"]) != trackId) invalid()
                checkCode(record["code"])
                val value = record["url"]
                if (value == null || value === JsonNull || value is JsonText && value.value.isEmpty()) unavailable()
                val rawUrl = value.jsonString()
                val target = neteaseTarget(rawUrl)
                val level = record["level"].takeUnless { it == null || it === JsonNull }?.jsonString()
                val reportedQuality = when (level) {
                    "standard" -> StreamingQuality.STANDARD
                    "exhigh" -> StreamingQuality.HIGH
                    else -> null
                }
                val bitrate = record["br"].takeUnless { it == null || it === JsonNull }?.jsonLong()?.also {
                    if (it !in 1..100_000_000L) invalid()
                }
                val trial = record["freeTrialInfo"]
                val completeness = when {
                    trial == null -> ResourceCompleteness.UNKNOWN
                    trial === JsonNull -> ResourceCompleteness.FULL_REPORTED
                    else -> {
                        val fields = trial.jsonObject()
                        val start = fields["start"].jsonLong()
                        val end = fields["end"].jsonLong()
                        if (start < 0L || end <= start) invalid()
                        ResourceCompleteness.PREVIEW_REPORTED
                    }
                }
                val lifetime = record["expi"].takeUnless { it == null || it === JsonNull }?.jsonLong()?.also {
                    if (it !in 1..86_400L) invalid()
                }
                val expires = lifetime?.let {
                    val milliseconds = it * 1_000L
                    if (requestedAt < 0L || requestedAt > Long.MAX_VALUE - milliseconds) invalid()
                    // A relative server lifetime is conservatively anchored before the request, not after reading.
                    requestedAt + milliseconds
                }
                NeteaseResolvedSource(target, reportedQuality, bitrate, completeness, expires)
            }
        } catch (_: NetworkException) { invalid() }
        catch (error: MediaTransportException) { throw QueuePlaybackException(QueuePlaybackFailure(error.issue)) }
    }

    private fun neteaseTarget(value: String): MediaHttpTarget {
        if (value.length !in 1..2048) invalid()
        val parsed = value.toHttpUrlOrNull() ?: invalid()
        // The pinned native route promotes ordinary HTTP CDN URLs. No plaintext request is sent.
        // Custom HTTP ports are rejected; Meting's arbitrary HTTP-origin feature remains a separate gate.
        val secure = if (parsed.scheme == "http" && parsed.port == 80) parsed.newBuilder().scheme("https").port(443).build()
            else parsed
        return MediaHttpTarget.https(secure.toString())
    }

    private fun checkCode(value: JsonValue?) {
        val code = value.jsonLong()
        when (code) {
            200L -> Unit
            301L, 401L, 403L -> throw AuthException(AuthIssue.LOGIN_REQUIRED)
            429L -> throw AuthException(AuthIssue.RATE_LIMITED)
            404L, -110L -> unavailable()
            else -> invalid()
        }
    }
    private fun id(value: JsonValue?): String {
        val text = when (value) {
            is JsonNumber -> value.value
            is JsonText -> value.value
            else -> invalid()
        }
        if (!canonicalId(text)) invalid()
        return text
    }
    private fun canonicalId(value: String) = value.length in 1..32 && value.first() in '1'..'9' && value.all { it in '0'..'9' }
    private fun invalid(): Nothing = throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_INVALID))
    private fun unavailable(): Nothing = throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_UNAVAILABLE))
}
