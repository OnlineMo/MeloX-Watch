/* Protocol reference: youshen2/MeloX, GPL-3.0,
 * 09713edb847a0f0a3515a34f779cc630d4fe5985, NeteaseAPI.swift. Android bounds/parsing are original. */
package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionCredential
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.MetadataCallBudget
import io.github.onlinemo.meloxwatch.data.network.jsonLong
import io.github.onlinemo.meloxwatch.model.onlineCanonicalId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The authenticated owner supplies the credential; no lyric text or credential enters diagnostics. */
internal class NativeLyricsText(val primary: ByteArray?, val translation: ByteArray?,
    val translationIssue: NeteaseLyricsIssue? = null) {
    override fun toString(): String = "NativeLyricsText(redacted)"
}

internal enum class NeteaseLyricsIssue { INVALID_RESPONSE, INPUT_TOO_LARGE, MEMORY_LIMIT }
internal class NeteaseLyricsException(val issue: NeteaseLyricsIssue) : Exception(issue.name)

/** One native request. This class cannot invoke Meting or start audio when lyrics are unavailable. */
internal class NeteaseLyricsApi(private val eapi: NeteaseEapi) {
    suspend fun fetch(trackId: String, credential: SessionCredential,
        maxAccountedBytes: Long = LyricsLimits.ACCOUNTED_BYTES): NativeLyricsText = withContext(Dispatchers.Default) {
        if (!onlineCanonicalId(trackId)) invalid()
        require(maxAccountedBytes in 1L..LyricsLimits.ACCOUNTED_BYTES)
        val allowed = (maxAccountedBytes - RESPONSE_ENVELOPE_BYTES) / 6L
        if (allowed <= 0L) throw NeteaseLyricsException(NeteaseLyricsIssue.MEMORY_LIMIT)
        val budget = MetadataCallBudget(responseBytes = minOf(LyricsLimits.INPUT_BYTES.toLong(), allowed).toInt())
        currentCoroutineContext().ensureActive()
        val response = try { eapi.post("/api/song/lyric", mapOf(
            "id" to JsonText(trackId), "lv" to JsonNumber("-1"), "kv" to JsonNumber("-1"),
            "tv" to JsonNumber("-1"), "rv" to JsonNumber("-1"),
        ), NeteaseCookie.fromCredential(credential), budget,
            JsonBounds(bytes = budget.responseBytes, nodes = 512, members = 64, stringChars = budget.responseBytes)) }
        catch (error: AuthException) {
            if (error.issue == AuthIssue.INVALID_RESPONSE) invalid()
            throw error
        }
        currentCoroutineContext().ensureActive()
        when (response.json["code"].jsonLong()) {
            200L -> Unit
            301L, 401L, 403L -> throw AuthException(AuthIssue.LOGIN_REQUIRED)
            429L -> throw AuthException(AuthIssue.RATE_LIMITED)
            else -> invalid()
        }
        val primary = text(response.json["lrc"])
        var translationIssue: NeteaseLyricsIssue? = null
        val translation = try { text(response.json["tlyric"]) }
        catch (error: NeteaseLyricsException) { translationIssue = error.issue; null }
        if ((primary?.size ?: 0).toLong() + (translation?.size ?: 0) > budget.responseBytes) oversized()
        currentCoroutineContext().ensureActive()
        NativeLyricsText(primary, translation, translationIssue)
    }

    private fun text(section: JsonValue?): ByteArray? {
        if (section == null || section === JsonNull) return null
        val fields = section as? JsonObject ?: invalid()
        val value = fields["lyric"]
        if (value == null || value === JsonNull) return null
        val text = (value as? JsonText)?.value ?: invalid()
        if (text.isBlank()) return null
        if (text.length > LyricsLimits.INPUT_BYTES) oversized()
        // UTF-8 size, rather than character count alone, is the frozen single-document bound.
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > LyricsLimits.INPUT_BYTES) oversized()
        return bytes
    }

    private fun invalid(): Nothing = throw NeteaseLyricsException(NeteaseLyricsIssue.INVALID_RESPONSE)
    private fun oversized(): Nothing = throw NeteaseLyricsException(NeteaseLyricsIssue.INPUT_TOO_LARGE)
    private companion object { const val RESPONSE_ENVELOPE_BYTES = 196_608L }
}
