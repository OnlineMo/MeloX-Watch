package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal object ExchangeCueReferenceCodec {
    fun encode(reference: LocalCueReference): String {
        if (reference.isBound) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        val text = CueReferenceCodec.encode(reference)
        if (text.toByteArray(Charsets.UTF_8).size > PlaylistExchangeLimits.CUE_REFERENCE_BYTES) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        return text
    }
    fun decode(text: String): LocalCueReference {
        if (text.length > PlaylistExchangeLimits.CUE_REFERENCE_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > PlaylistExchangeLimits.CUE_REFERENCE_BYTES) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        return try { CueReferenceCodec.decode(text, portableOnly = true) }
        catch (failure: Exception) { throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_VALUE, failure) }
    }
    fun base64(reference: LocalCueReference): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(encode(reference).toByteArray(Charsets.UTF_8))
    fun fromBase64(value: String): LocalCueReference {
        if (value.isEmpty() || value.length > 5462 || !value.matches(Regex("[A-Za-z0-9_-]+"))) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        val bytes = try { Base64.getUrlDecoder().decode(value) }
        catch (failure: IllegalArgumentException) { throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_VALUE, failure) }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (failure: Exception) { throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_ENCODING, failure) }
        return decode(text)
    }
}
