package io.github.onlinemo.meloxwatch.data.metadata

import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.onlineCanonicalId

internal enum class MetadataPurpose(val inputBytes: Long) {
    LYRICS(LyricsLimits.INPUT_BYTES.toLong()), ARTWORK(ArtworkLimits.INPUT_BYTES.toLong()),
}

internal enum class MetadataStoreIssue {
    INVALID_INPUT, INVALID_RECORD, TRACK_MISSING, BUSY, SOURCE_CHANGED, CONFLICT,
    INPUT_LIMIT, LOW_SPACE, LOW_MEMORY, IO,
}

internal class MetadataStoreException(val issue: MetadataStoreIssue, cause: Throwable? = null) : Exception(issue.name, cause)

/** This is a source and rights notice, not evidence that an accessible URL grants a redistribution license. */
internal data class MetadataProvenance(
    val songId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
) {
    init {
        require(onlineCanonicalId(songId))
        require(validText(title, 256) && validText(artist, 512) && validText(album, 256))
    }

    fun encode(): String = StrictJson.encode(JsonObject(mapOf(
        "schema" to JsonNumber("1"), "provider" to JsonText("netease"), "songId" to JsonText(songId),
        "title" to cell(title), "artist" to cell(artist), "album" to cell(album),
        "rightsNotice" to JsonText(RIGHTS_NOTICE),
    )), PROVENANCE_BYTES).toString(Charsets.UTF_8)

    override fun toString(): String = "MetadataProvenance(redacted)"

    companion object {
        const val PROVENANCE_BYTES = 8192
        const val RIGHTS_NOTICE = "source-terms-no-redistribution-v1"
        private val FIELDS = setOf("schema", "provider", "songId", "title", "artist", "album", "rightsNotice")

        fun decode(raw: String): MetadataProvenance {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.size <= PROVENANCE_BYTES)
            val json = StrictJson.decode(bytes, JsonBounds(PROVENANCE_BYTES, depth = 1,
                nodes = 16, stringChars = 1024, members = 8)) as? JsonObject ?: invalidRecord()
            require(json.fields.keys == FIELDS && (json["schema"] as? JsonNumber)?.value == "1" &&
                text(json["provider"]) == "netease" && text(json["rightsNotice"]) == RIGHTS_NOTICE)
            return MetadataProvenance(text(json["songId"]) ?: invalidRecord(),
                text(json["title"]), text(json["artist"]), text(json["album"]))
        }

        private fun cell(value: String?): JsonValue = value?.let(::JsonText) ?: JsonNull
        private fun text(value: JsonValue?): String? = when (value) {
            JsonNull -> null
            is JsonText -> value.value
            else -> invalidRecord()
        }
        private fun validText(value: String?, limit: Int): Boolean = value == null ||
            value.isNotBlank() && value.length <= limit && value.none(Char::isISOControl)
    }
}

internal data class MetadataAssociation(
    val trackKey: TrackKey,
    val purpose: MetadataPurpose,
    val revision: Long = 0L,
    val assetId: String? = null,
    val sha256: String? = null,
    val byteCount: Long? = null,
    val provenance: MetadataProvenance? = null,
) {
    init {
        require(trackKey.providerId == TrackKey.LOCAL_PROVIDER && trackKey.trackId.isNotBlank() &&
            trackKey.trackId.length <= 128 && trackKey.trackId.none(Char::isISOControl) && revision >= 0L)
        if (sha256 == null) require(assetId == null && byteCount == null && provenance == null)
        else require((assetId == null || validMetadataAssetId(assetId)) && sha256.matches(Regex("[0-9a-f]{64}")) &&
            byteCount != null && byteCount in 1..purpose.inputBytes && provenance != null)
    }

    fun entity(): LocalMetadataAssociationEntity = LocalMetadataAssociationEntity(trackKey.trackId, purpose.name,
        1, revision, assetId, sha256, byteCount, provenance?.encode())

    override fun toString(): String = "MetadataAssociation(redacted)"
}

internal fun LocalMetadataAssociationRow.decode(trackKey: TrackKey, purpose: MetadataPurpose): MetadataAssociation = try {
    if (!validTypes || schemaVersion != 1L) invalidRecord()
    MetadataAssociation(trackKey, purpose, revision, assetId, sha256, byteCount, provenance?.let(MetadataProvenance::decode))
} catch (failure: MetadataStoreException) { throw failure }
catch (failure: Exception) { throw MetadataStoreException(MetadataStoreIssue.INVALID_RECORD, failure) }

internal fun validMetadataAssetId(value: String): Boolean =
    value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

private fun invalidRecord(): Nothing = throw MetadataStoreException(MetadataStoreIssue.INVALID_RECORD)
