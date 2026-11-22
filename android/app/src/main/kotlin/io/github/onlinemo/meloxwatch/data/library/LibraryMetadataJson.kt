package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBoolean
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import org.json.JSONException

/** 直接验证原始存储JSON，避免JSONObject先丢重复键或改变数字的类型表达。 */
internal object LibraryMetadataJson {
    const val METADATA_BYTES = 4 * 1024 * 1024
    private const val OUTCOME_BYTES = 8 * 1024 * 1024
    private val metadataFields = setOf("schema", "title", "artist", "artists", "album", "albumArtist", "durationMs",
        "discNumber", "trackNumber", "compilation", "mimeType", "parserVersion", "codecParameters")
    private val outcomeFields = setOf("schema", "objectId", "metadata", "issue", "verifiedContentToken")

    fun metadata(value: String): LocalAudioMetadata = checked {
        val record = parse(value, JsonBounds(METADATA_BYTES, depth = 3, nodes = 160, stringChars = 4096, members = 128))
        require(record.fields.keys.all { it in metadataFields } && integer(record["schema"]) == 1)
        val artists = (record["artists"] as? JsonArray)?.values ?: malformed()
        require(artists.size <= 128)
        val parameters = when (val raw = record["codecParameters"]) {
            null, JsonNull -> null
            is JsonObject -> {
                // StrictJson保留数字原词法：2.0/2e0不会在到达codec验证器前变成2。
                val bytes = StrictJson.encode(raw, LocalCodecParametersCodec.MAX_BYTES)
                LocalCodecParametersCodec.decode(String(bytes, Charsets.UTF_8))
            }
            else -> malformed()
        }
        LocalAudioMetadata(
            title = optionalText(record["title"]), artist = optionalText(record["artist"]),
            artists = artists.map { text(it) }, album = optionalText(record["album"]),
            albumArtist = optionalText(record["albumArtist"]), durationMs = optionalLong(record["durationMs"]),
            discNumber = optionalInt(record["discNumber"]), trackNumber = optionalInt(record["trackNumber"]),
            compilation = (record["compilation"] as? JsonBoolean)?.value ?: malformed(),
            mimeType = optionalText(record["mimeType"]), parserVersion = integer(record["parserVersion"]), codecParameters = parameters,
        )
    }

    fun outcome(value: String): MetadataOutcome = checked {
        val record = parse(value, JsonBounds(OUTCOME_BYTES, depth = 1, nodes = 6, stringChars = METADATA_BYTES, members = 5))
        require(record.fields.keys.all { it in outcomeFields } && integer(record["schema"]) == 1)
        val rawMetadata = optionalText(record["metadata"], METADATA_BYTES)
        MetadataOutcome(text(record["objectId"], LocalInputLimits.URI_CHARACTERS), rawMetadata?.let(::metadata),
            optionalText(record["issue"])?.let(SourceReadIssue::valueOf), optionalText(record["verifiedContentToken"]))
    }

    private fun parse(value: String, bounds: JsonBounds): JsonObject {
        require(value.length <= bounds.bytes)
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(buffer.remaining() <= bounds.bytes)
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return StrictJson.decode(bytes, bounds) as? JsonObject ?: malformed()
    }

    private fun text(value: JsonValue?, maximum: Int = 4096): String {
        val result = (value as? JsonText)?.value ?: malformed()
        require(result.length <= maximum)
        return result
    }

    private fun optionalText(value: JsonValue?, maximum: Int = 4096): String? = when (value) {
        null, JsonNull -> null
        else -> text(value, maximum)
    }

    private fun integer(value: JsonValue?): Int = (value as? JsonNumber)?.value?.toIntOrNull() ?: malformed()
    private fun optionalInt(value: JsonValue?): Int? = when (value) {
        null, JsonNull -> null
        else -> integer(value)
    }

    private fun optionalLong(value: JsonValue?): Long? = when (value) {
        null, JsonNull -> null
        else -> (value as? JsonNumber)?.value?.toLongOrNull() ?: malformed()
    }

    private fun malformed(): Nothing = throw JSONException("Invalid library metadata record")

    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (failure: NetworkException) { throw JSONException("Invalid library metadata JSON").also { it.initCause(failure) } }
    catch (failure: CharacterCodingException) { throw JSONException("Invalid library metadata encoding").also { it.initCause(failure) } }
    catch (failure: IllegalArgumentException) { throw JSONException("Invalid library metadata value").also { it.initCause(failure) } }
}
