package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.text.BreakIterator
import java.util.Locale

/** Public protocol identifier and bounded work limits; none is device acceptance evidence. */
object BluetoothLyricsLimits {
    val SERVICE_UUID: UUID = UUID.fromString("3e46c562-d08d-4a4b-9676-d8c306a4dd2d")
    const val SCHEMA = 1
    const val HELLO_BYTES = 256
    const val HEADER_BYTES = 16
    const val PLAINTEXT_BYTES = 8192
    const val TAG_BYTES = 16
    const val FRAME_BYTES = HEADER_BYTES + PLAINTEXT_BYTES + TAG_BYTES
    const val TITLE_BYTES = 512
    const val ARTIST_BYTES = 512
    const val ORIGINAL_BYTES = 4096
    const val TRANSLATION_BYTES = 2048
    const val MIN_WRITE_INTERVAL_MS = 200L
    const val HEARTBEAT_MS = 3000L
    const val SILENCE_MS = 10_000L
    const val CONNECT_MS = 10_000L
    const val COMPARE_MS = 120_000L
    const val WRITE_MS = 2000L
    const val SESSION_MS = 60 * 60 * 1000L
    const val RECEIVED_FRAMES_PER_SECOND = 16
}

enum class BluetoothLyricsIssue {
    CLOSED, BUSY, UNAVAILABLE, BLUETOOTH_OFF, NOT_BONDED, PERMISSION_DENIED,
    WRONG_PEER, NOT_CONFIRMED, EXPIRED, CONNECTION_LOST, TRANSPORT_FAILED,
    INVALID_FRAME, INCOMPATIBLE_SCHEMA, AUTHENTICATION_FAILED, CRYPTO_UNAVAILABLE,
    RATE_LIMITED, RETIREMENT_REQUIRED, STORAGE_UNAVAILABLE, SOURCE_UNAVAILABLE,
}

/** Native exceptions may include connection details; only a stable code leaves the owner. */
class BluetoothLyricsException(val issue: BluetoothLyricsIssue) : Exception(issue.name)

enum class BluetoothLyricsRole(val wire: Int) { WATCH(1), PHONE(2) }

enum class BluetoothLyricsPlayback(val wire: Int) {
    NO_TRACK(0), LOADING(1), PLAYING(2), PAUSED(3), ENDED(4),
    NO_LYRICS(5), SOURCE_UNAVAILABLE(6), PLAIN_TEXT(7),
}

/** Content is local display data, never a diagnostic, SavedStateHandle value or disk record. */
class BluetoothLyricsPayload(
    val trackEpoch: Long,
    val mediaPositionMs: Long,
    val lineMediaTimeMs: Long,
    val playback: BluetoothLyricsPlayback,
    val title: String,
    val artist: String,
    val original: String,
    val translation: String,
    val shortened: Boolean = false,
    val isPlaying: Boolean = playback == BluetoothLyricsPlayback.PLAYING,
) {
    init {
        require(trackEpoch > 0L && mediaPositionMs >= 0L && lineMediaTimeMs >= -1L)
        require(playback != BluetoothLyricsPlayback.PLAYING || isPlaying)
        require(!isPlaying || (playback != BluetoothLyricsPlayback.PAUSED &&
            playback != BluetoothLyricsPlayback.NO_TRACK && playback != BluetoothLyricsPlayback.ENDED))
        boundedLyricsUtf8(title, BluetoothLyricsLimits.TITLE_BYTES)
        boundedLyricsUtf8(artist, BluetoothLyricsLimits.ARTIST_BYTES)
        boundedLyricsUtf8(original, BluetoothLyricsLimits.ORIGINAL_BYTES)
        boundedLyricsUtf8(translation, BluetoothLyricsLimits.TRANSLATION_BYTES)
    }

    override fun toString(): String = "<local-bluetooth-lyrics>"
}

class BluetoothLyricsText(val value: String, val shortened: Boolean) {
    override fun toString(): String = "<bounded-local-text>"

    companion object {
        /** Clipping never splits a surrogate pair or creates malformed UTF-8. */
        fun clip(value: CharSequence, maximumBytes: Int): BluetoothLyricsText {
            require(maximumBytes in 1..BluetoothLyricsLimits.PLAINTEXT_BYTES)
            val result = StringBuilder(minOf(value.length, maximumBytes))
            var bytes = 0
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (Character.isLowSurrogate(character) ||
                    Character.isHighSurrogate(character) &&
                    (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))) {
                    throw BluetoothLyricsException(BluetoothLyricsIssue.INVALID_FRAME)
                }
                val codePoint = Character.codePointAt(value, index)
                if (codePoint == 0) throw BluetoothLyricsException(BluetoothLyricsIssue.INVALID_FRAME)
                val count = when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
                if (bytes + count > maximumBytes) break
                result.appendCodePoint(codePoint)
                bytes += count
                index += Character.charCount(codePoint)
            }
            if (index < value.length && index > 0) {
                val next = Character.codePointAt(value, index)
                val probeEnd = index + Character.charCount(next)
                val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
                breaks.setText(value.subSequence(0, probeEnd).toString())
                if (!breaks.isBoundary(index)) {
                    val complete = breaks.preceding(index)
                    result.setLength(complete.coerceAtLeast(0))
                }
            }
            return BluetoothLyricsText(result.toString(), index < value.length)
        }
    }
}

internal fun boundedLyricsUtf8(value: String, maximum: Int): ByteArray {
    if (value.length > maximum || value.any { it == '\u0000' }) {
        throw BluetoothLyricsException(BluetoothLyricsIssue.INVALID_FRAME)
    }
    return try {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        if (buffer.remaining() > maximum) throw BluetoothLyricsException(BluetoothLyricsIssue.INVALID_FRAME)
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (error: BluetoothLyricsException) {
        throw error
    } catch (_: Exception) {
        throw BluetoothLyricsException(BluetoothLyricsIssue.INVALID_FRAME)
    }
}
