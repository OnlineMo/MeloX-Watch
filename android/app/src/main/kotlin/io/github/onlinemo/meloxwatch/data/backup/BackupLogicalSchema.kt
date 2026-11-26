package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalCodecParametersCodec
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.data.playlists.requirePlaylistIdentifier
import io.github.onlinemo.meloxwatch.data.playlists.playlistName
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.data.themes.ThemeRef
import io.github.onlinemo.meloxwatch.data.themes.ThemeLimits
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId

/** Whitelisted logical fields are independent of Room's storage schema and operational journals. */
internal object BackupLogicalSchema {
    private enum class Type { TEXT, INTEGER, DECIMAL, FLAG }
    private data class Field(val type: Type, val nullable: Boolean = false, val characters: Int = 4096,
        val minimum: Long = 0L, val maximum: Long = Long.MAX_VALUE)
    private fun text(nullable: Boolean = false, characters: Int = 4096) = Field(Type.TEXT, nullable, characters)
    private fun number(nullable: Boolean = false, minimum: Long = 0L, maximum: Long = Long.MAX_VALUE) =
        Field(Type.INTEGER, nullable, minimum = minimum, maximum = maximum)
    private val flag = Field(Type.FLAG)
    private val fields: Map<BackupRecordKind, Map<String, Field>> = mapOf(
        BackupRecordKind.LIBRARY_META to mapOf("shortAudioFilter" to flag),
        BackupRecordKind.TRACK to mapOf(
            "providerId" to text(characters = 128), "member" to flag, "firstAddedEpochMs" to number(),
            "firstAddedSequence" to number(minimum = 1), "filename" to text(characters = 512),
            "title" to text(true), "artist" to text(true), "artists" to text(characters = 600_000),
            "album" to text(true), "albumArtist" to text(true), "durationMs" to number(true, 1),
            "discNumber" to number(true, maximum = Int.MAX_VALUE.toLong()), "trackNumber" to number(true, maximum = Int.MAX_VALUE.toLong()),
            "compilation" to flag, "mimeType" to text(true, 256), "parserVersion" to number(minimum = 1, maximum = Int.MAX_VALUE.toLong()),
            "metadataIssue" to text(true, 64), "codecParameters" to text(true, LocalCodecParametersCodec.MAX_BYTES),
            "cueReference" to text(true, CueReferenceCodec.MAX_BYTES),
        ),
        BackupRecordKind.TRACK_LOCATION to mapOf("trackId" to text(characters = 128), "locationId" to text(characters = 128)),
        BackupRecordKind.SOURCE_HINT to mapOf(
            "kind" to text(characters = 32), "uriHint" to text(true, 8192), "mediaVolume" to text(characters = 256),
            "authorityHint" to text(true, 512), "rootObjectIdHint" to text(true, 8192), "state" to text(characters = 32),
            "ownedTokenHint" to text(true, 128),
        ),
        BackupRecordKind.LOCATION_HINT to mapOf("sizeBytes" to number(true), "modifiedEpochMs" to number(true), "contentSha256" to text(true, 64),
            "providerContentTokenHint" to text(true, 8192)),
        BackupRecordKind.NODE_HINT to mapOf("sourceId" to text(characters = 128), "objectIdHint" to text(true, 8192),
            "parentNodeId" to text(true, 128), "name" to text(true, 512), "uriHint" to text(true, 8192),
            "directory" to flag, "pathName" to text(true, 8192)),
        BackupRecordKind.ALIAS_HINT to mapOf("sourceId" to text(characters = 128), "objectIdHint" to text(true, 8192),
            "locationId" to text(characters = 128), "nodeId" to text(characters = 128), "uriHint" to text(true, 8192), "filename" to text(characters = 512)),
        BackupRecordKind.IMPORT_HINT to mapOf("sourceId" to text(characters = 128), "trackId" to text(characters = 128),
            "sha256" to text(characters = 64), "bytes" to number(minimum = 1), "ownedTokenHint" to text(characters = 128)),
        BackupRecordKind.RULE to mapOf("sourceId" to text(characters = 128), "nodeId" to text(characters = 128), "subtree" to flag),
        BackupRecordKind.DISPLAY_GROUP to mapOf("kind" to text(characters = 16), "groupKey" to text(characters = 8192),
            "originalName" to text(true), "originalSubtitle" to text(true)),
        BackupRecordKind.DISPLAY_NAME to mapOf("displayName" to text()),
        BackupRecordKind.DISPLAY_ALBUM_LINK to mapOf("groupId" to text(characters = 128)),
        BackupRecordKind.DISPLAY_ARTIST_LINK to mapOf("trackId" to text(characters = 128), "groupId" to text(characters = 128), "included" to flag),
        BackupRecordKind.FAVORITES_HEAD to playlistHeadFields(), BackupRecordKind.PLAYLIST_HEAD to playlistHeadFields(),
        BackupRecordKind.FAVORITES_MEMBER to playlistMemberFields(), BackupRecordKind.PLAYLIST_MEMBER to playlistMemberFields(),
        BackupRecordKind.QUEUE_META to mapOf("totalCount" to number(), "shuffle" to flag, "repeatMode" to text(characters = 32),
            "cycleLimit" to number(true, 1, 99), "currentEntryId" to text(true, 128),
            "positionMs" to number(), "completedCycles" to number(maximum = Int.MAX_VALUE.toLong())),
        BackupRecordKind.QUEUE_ENTRY to mapOf("providerId" to text(characters = 128), "trackId" to text(characters = 128),
            "originalOrder" to number(), "playbackOrder" to number(), "displayName" to text(true),
            "durationMs" to number(true), "locationIdHint" to text(true, 128), "uriHint" to text(true, 8192),
            "cueReference" to text(true, CueReferenceCodec.MAX_BYTES)),
        BackupRecordKind.HISTORY_META to mapOf("totalPlayCount" to number(), "totalListeningMs" to number()),
        BackupRecordKind.HISTORY_ENTRY to mapOf("ordinal" to number(minimum = 1), "providerId" to text(characters = 128),
            "trackId" to text(characters = 128), "displayName" to text(true), "firstProgressEpochMs" to number(),
            "firstProgressZoneId" to text(characters = 256), "lastProgressEpochMs" to number(),
            "listeningMs" to number(), "knownDurationMs" to number(true, 1)),
        BackupRecordKind.TRACK_TOTAL to mapOf("providerId" to text(characters = 128), "trackId" to text(characters = 128),
            "playCount" to number(), "listeningMs" to number(), "lastProgressEpochMs" to number(true), "displayName" to text(true)),
        BackupRecordKind.DAY_TOTAL to mapOf("localDate" to text(characters = 32), "zoneId" to text(characters = 256),
            "playCount" to number(), "listeningMs" to number()),
        BackupRecordKind.LYRICS_TRACK to mapOf("userDelayMs" to number(minimum = -LyricsUserOffset.MAX_DELAY_MS.toLong(), maximum = LyricsUserOffset.MAX_DELAY_MS.toLong()),
            "primaryDocumentIdHint" to text(true, 8192), "primarySourceIdHint" to text(true, 128), "primaryUriHint" to text(true, 8192),
            "primaryEncoding" to text(true, 32), "primarySha256" to text(true, 64), "primaryResourceId" to text(true, 64),
            "translationDocumentIdHint" to text(true, 8192), "translationSourceIdHint" to text(true, 128), "translationUriHint" to text(true, 8192),
            "translationEncoding" to text(true, 32), "translationSha256" to text(true, 64), "translationResourceId" to text(true, 64)),
        BackupRecordKind.RESOURCE to mapOf("kind" to text(characters = 32), "bytes" to number(minimum = 1),
            "sha256" to text(characters = 64), "licenseRequired" to flag),
        BackupRecordKind.THEME_INSTALLATION to mapOf("themeId" to text(characters = ThemeLimits.ID_CHARACTERS),
            "manifestSha256" to text(characters = 64), "displayName" to text(characters = ThemeLimits.NAME_CHARACTERS),
            "packageResourceId" to text(characters = 64), "resourceBytes" to number(maximum = ThemeLimits.UNPACKED_BYTES),
            "fontBytes" to number(maximum = ThemeLimits.UNPACKED_BYTES)),
        BackupRecordKind.METADATA_LYRICS to metadataFields(true),
        BackupRecordKind.METADATA_ARTWORK to metadataFields(false),
    )

    fun validate(record: BackupRecord) {
        if (record.kind == BackupRecordKind.PREFERENCE) {
            BackupPreferencesCodec.validate(record)
            return
        }
        val expected = fields[record.kind] ?: reject()
        if (record.kind in setOf(BackupRecordKind.TRACK, BackupRecordKind.TRACK_LOCATION, BackupRecordKind.SOURCE_HINT,
                BackupRecordKind.LOCATION_HINT, BackupRecordKind.NODE_HINT, BackupRecordKind.ALIAS_HINT, BackupRecordKind.IMPORT_HINT,
                BackupRecordKind.RULE, BackupRecordKind.DISPLAY_GROUP, BackupRecordKind.DISPLAY_NAME, BackupRecordKind.DISPLAY_ALBUM_LINK,
                BackupRecordKind.DISPLAY_ARTIST_LINK, BackupRecordKind.FAVORITES_HEAD, BackupRecordKind.PLAYLIST_HEAD,
                BackupRecordKind.QUEUE_ENTRY, BackupRecordKind.LYRICS_TRACK, BackupRecordKind.THEME_INSTALLATION,
                BackupRecordKind.METADATA_LYRICS, BackupRecordKind.METADATA_ARTWORK) && record.key.length > 128) reject()
        val legacy = if (record.kind in LEGACY_CUE_BACKUP_KINDS) setOf("cueReference") else emptySet()
        if (record.values.keys != expected.keys && record.values.keys != expected.keys - legacy) reject()
        for ((name, spec) in expected) {
            val value = record.values[name] ?: if (name in legacy) BackupCell.Null else reject()
            if (value == BackupCell.Null && spec.nullable) continue
            val valid = when (spec.type) {
                Type.TEXT -> value is BackupCell.Text && value.value.length <= spec.characters
                Type.INTEGER -> value is BackupCell.Integer && value.value in spec.minimum..spec.maximum
                Type.DECIMAL -> value is BackupCell.Decimal && value.value.isFinite()
                Type.FLAG -> value is BackupCell.Flag
            }
            if (!valid) reject()
        }
        record.portableCueReference()
        when (record.kind) {
            BackupRecordKind.LIBRARY_META -> if (record.key != "library") reject()
            BackupRecordKind.TRACK -> {
                if (record.key.length > 128 || record.text("providerId") != TrackKey.LOCAL_PROVIDER) reject()
                try { LocalAudioMetadata(record.optionalText("title"), record.optionalText("artist"), artists(record.text("artists")),
                    record.optionalText("album"), record.optionalText("albumArtist"), record.optionalLong("durationMs"),
                    record.optionalLong("discNumber")?.toInt(), record.optionalLong("trackNumber")?.toInt(), record.flag("compilation"),
                    record.optionalText("mimeType"), record.long("parserVersion").toInt(),
                    codecParameters = record.optionalText("codecParameters")?.let(LocalCodecParametersCodec::decode)) }
                catch (error: IllegalArgumentException) { throw BackupException(BackupIssue.INVALID_INPUT, error) }
            }
            BackupRecordKind.SOURCE_HINT -> {
                if (LocalSourceKind.entries.none { it.name == record.text("kind") } ||
                    LibrarySourceState.entries.none { it.name == record.text("state") }) reject()
                uri(record.optionalText("uriHint"))
                hint(record.optionalText("rootObjectIdHint"))
                hint(record.optionalText("ownedTokenHint"))
            }
            BackupRecordKind.LOCATION_HINT -> { digest(record.optionalText("contentSha256")); hint(record.optionalText("providerContentTokenHint")) }
            BackupRecordKind.NODE_HINT -> {
                uri(record.optionalText("uriHint")); hint(record.optionalText("objectIdHint")); relative(record.optionalText("pathName"))
            }
            BackupRecordKind.ALIAS_HINT -> { uri(record.optionalText("uriHint")); hint(record.optionalText("objectIdHint")) }
            BackupRecordKind.IMPORT_HINT -> { digest(record.text("sha256")); hint(record.text("ownedTokenHint")) }
            BackupRecordKind.DISPLAY_GROUP -> if (record.text("kind") !in setOf("ALBUM", "ARTIST")) reject()
            BackupRecordKind.FAVORITES_HEAD, BackupRecordKind.PLAYLIST_HEAD -> {
                val kind = PlaylistKind.entries.firstOrNull { it.name == record.text("kind") } ?: reject()
                if ((record.kind == BackupRecordKind.FAVORITES_HEAD) != (kind == PlaylistKind.FAVORITES) ||
                    (kind == PlaylistKind.FAVORITES) != (record.key == PlaylistLimits.FAVORITES_ID)) reject()
                if (playlistName(record.text("name")) != record.text("name")) reject()
                val bound = listOf("bindingSourceId", "bindingNodeId", "bindingSubtree").count { record.values[it] != BackupCell.Null }
                if ((kind == PlaylistKind.FOLDER && bound != 3) || (kind != PlaylistKind.FOLDER && bound != 0)) reject()
            }
            BackupRecordKind.FAVORITES_MEMBER, BackupRecordKind.PLAYLIST_MEMBER -> {
                if ((record.optionalText("providerId") == null) != (record.optionalText("trackId") == null)) reject()
                if ((record.optionalText("trackId") != null) == (record.optionalText("placeholderId") != null)) reject()
                relative(record.optionalText("relativePathHint"))
                requirePlaylistIdentifier(record.text("playlistId"))
                requirePlaylistIdentifier(record.text("memberId"))
                val provider = record.optionalText("providerId")
                PlaylistReference(provider?.let { TrackKey(it, record.text("trackId")) }, record.optionalText("placeholderId"),
                    record.optionalText("issue")?.let { name -> PlaylistReferenceIssue.entries.firstOrNull { it.name == name } ?: reject() },
                    record.optionalText("titleHint"), record.optionalText("artistHint"), record.optionalText("albumHint"),
                    record.optionalText("relativePathHint"), record.optionalText("descriptionHint"), record.optionalLong("durationMs"),
                    cueReference = record.portableCueReference())
            }
            BackupRecordKind.QUEUE_META -> {
                if (record.key != "queue" || QueueRepeatMode.entries.none { it.name == record.text("repeatMode") }) reject()
                if ((record.long("totalCount") == 0L) != (record.optionalText("currentEntryId") == null)) reject()
                if (record.long("totalCount") == 0L && (record.long("positionMs") != 0L || record.long("completedCycles") != 0L)) reject()
            }
            BackupRecordKind.QUEUE_ENTRY -> { TrackKey(record.text("providerId"), record.text("trackId")); uri(record.optionalText("uriHint")) }
            BackupRecordKind.HISTORY_META -> if (record.key != "history") reject()
            BackupRecordKind.HISTORY_ENTRY -> {
                TrackKey(record.text("providerId"), record.text("trackId"))
                zone(record.text("firstProgressZoneId"))
            }
            BackupRecordKind.TRACK_TOTAL -> TrackKey(record.text("providerId"), record.text("trackId"))
            BackupRecordKind.DAY_TOTAL -> { LocalDate.parse(record.text("localDate")); zone(record.text("zoneId")) }
            BackupRecordKind.LYRICS_TRACK -> {
                if (record.long("userDelayMs") % LyricsUserOffset.STEP_MS != 0L) reject()
                for (prefix in listOf("primary", "translation")) {
                    uri(record.optionalText(prefix + "UriHint")); digest(record.optionalText(prefix + "Sha256")); digest(record.optionalText(prefix + "ResourceId"))
                    hint(record.optionalText(prefix + "DocumentIdHint"))
                    val content = listOf("Encoding", "Sha256").count { record.values[prefix + it] != BackupCell.Null }
                    if (content !in setOf(0, 2)) reject()
                    if (content == 0 && listOf("DocumentIdHint", "SourceIdHint", "UriHint", "ResourceId").any {
                            record.values[prefix + it] != BackupCell.Null }) reject()
                    if (content == 2 && LyricsEncodingChoice.entries.none { it.name == record.text(prefix + "Encoding") }) reject()
                }
            }
            BackupRecordKind.RESOURCE -> resourceDescriptor(record, checkFields = false)
            BackupRecordKind.THEME_INSTALLATION -> {
                ThemeRef(record.key, record.text("themeId"), record.text("manifestSha256"))
                digest(record.text("packageResourceId"))
                if (record.long("fontBytes") > record.long("resourceBytes") || record.text("displayName").isBlank() ||
                    record.text("displayName").any(Char::isISOControl)) reject()
            }
            BackupRecordKind.METADATA_LYRICS, BackupRecordKind.METADATA_ARTWORK -> BackupMetadataAssociations.decode(record)
            else -> Unit
        }
    }

    fun resourceDescriptor(record: BackupRecord, checkFields: Boolean = true): BackupResource {
        if (record.kind != BackupRecordKind.RESOURCE) reject()
        if (checkFields && record.values.keys != fields[BackupRecordKind.RESOURCE]?.keys) reject()
        val kind = BackupResourceKind.entries.firstOrNull { it.name == record.text("kind") } ?: reject()
        val sha256 = record.text("sha256")
        digest(sha256)
        if (record.key != resourceId(kind, sha256) || kind == BackupResourceKind.FONT && !record.flag("licenseRequired") ||
            kind in setOf(BackupResourceKind.IMAGE, BackupResourceKind.LYRICS) && record.flag("licenseRequired")) reject()
        return try { BackupResource(record.key, kind, record.long("bytes"), sha256, record.flag("licenseRequired")) }
        catch (error: IllegalArgumentException) { throw BackupException(BackupIssue.INVALID_RESOURCE, error) }
    }

    fun resourceRecord(resource: BackupResource): BackupRecord = BackupRecord(BackupRecordKind.RESOURCE, resource.id, mapOf(
        "kind" to BackupCell.Text(resource.kind.name), "bytes" to BackupCell.Integer(resource.bytes),
        "sha256" to BackupCell.Text(resource.sha256), "licenseRequired" to BackupCell.Flag(resource.requiresFontLicenseAcknowledgment),
    ))
    fun resourceId(kind: BackupResourceKind, sha256: String): String = backupStableId(kind.name, sha256)
    fun artists(encoded: String): List<String> {
        val value = StrictJson.decode(encoded.toByteArray(Charsets.UTF_8), JsonBounds(bytes = 600_000, depth = 2,
            nodes = 129, stringChars = 4096, members = 128)) as? JsonArray ?: reject()
        return value.values.map { (it as? JsonText)?.value ?: reject() }
    }
    fun portableUri(value: String?): String? = value?.takeIf { runCatching { uri(it); true }.getOrDefault(false) }
    fun portableHint(value: String?): String? = value?.takeIf { runCatching { hint(it); true }.getOrDefault(false) }
    fun portableRelative(value: String?): String? = value?.takeIf { runCatching { relative(it); true }.getOrDefault(false) }

    private fun uri(value: String?) {
        if (value == null) return
        val uri = try { URI(value) } catch (error: Exception) { throw BackupException(BackupIssue.INVALID_INPUT, error) }
        if (uri.scheme != "content" || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || value.any(Char::isISOControl)) reject()
    }
    private fun hint(value: String?) {
        if (value == null) return
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') ||
            Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value) || value.contains("://") || value.any(Char::isISOControl)) reject()
    }
    private fun relative(value: String?) {
        if (value == null) return
        hint(value)
        if (value.contains('\\') || value.split('/').any { it == "." || it == ".." }) reject()
    }
    private fun digest(value: String?) { if (value != null && !value.matches(Regex("[0-9a-f]{64}"))) reject() }
    private fun zone(value: String) { try { ZoneId.of(value) } catch (error: Exception) { throw BackupException(BackupIssue.INVALID_INPUT, error) } }
    private fun metadataFields(lyrics: Boolean) = mapOf("revision" to number(), "sha256" to text(true, 64),
        "bytes" to number(true, 1, BackupLimits.IMAGE_BYTES), "provenance" to text(true, MetadataProvenance.PROVENANCE_BYTES),
        "resourceId" to text(true, 64), "cueReference" to text(true, CueReferenceCodec.MAX_BYTES)) +
        if (lyrics) mapOf("primaryLinked" to flag) else emptyMap()
    private fun playlistHeadFields() = mapOf("name" to text(), "kind" to text(characters = 32),
        "createdEpochMs" to number(), "updatedEpochMs" to number(), "memberCount" to number(),
        "bindingSourceId" to text(true, 128), "bindingNodeId" to text(true, 128), "bindingSubtree" to Field(Type.FLAG, true))
    private fun playlistMemberFields() = mapOf("playlistId" to text(characters = 128), "memberId" to text(characters = 128),
        "ordinal" to number(), "providerId" to text(true, 128), "trackId" to text(true, 128), "placeholderId" to text(true, 128),
        "issue" to text(true, 64), "titleHint" to text(true), "artistHint" to text(true), "albumHint" to text(true),
        "relativePathHint" to text(true), "descriptionHint" to text(true), "durationMs" to number(true, 1),
        "cueReference" to text(true, CueReferenceCodec.MAX_BYTES))
    private fun reject(): Nothing = throw BackupException(BackupIssue.INVALID_INPUT)
}

internal fun BackupRecord.text(name: String): String = (values[name] as? BackupCell.Text)?.value
    ?: throw BackupException(BackupIssue.INVALID_INPUT)
internal fun BackupRecord.optionalText(name: String): String? = when (val value = values[name]) {
    BackupCell.Null -> null
    is BackupCell.Text -> value.value
    else -> throw BackupException(BackupIssue.INVALID_INPUT)
}
internal fun BackupRecord.long(name: String): Long = (values[name] as? BackupCell.Integer)?.value
    ?: throw BackupException(BackupIssue.INVALID_INPUT)
internal fun BackupRecord.optionalLong(name: String): Long? = when (val value = values[name]) {
    BackupCell.Null -> null
    is BackupCell.Integer -> value.value
    else -> throw BackupException(BackupIssue.INVALID_INPUT)
}
internal fun BackupRecord.flag(name: String): Boolean = (values[name] as? BackupCell.Flag)?.value
    ?: throw BackupException(BackupIssue.INVALID_INPUT)
internal fun BackupRecord.optionalFlag(name: String): Boolean? = when (val value = values[name]) {
    BackupCell.Null -> null
    is BackupCell.Flag -> value.value
    else -> throw BackupException(BackupIssue.INVALID_INPUT)
}
internal fun BackupRecord.decimal(name: String): Double = (values[name] as? BackupCell.Decimal)?.value
    ?: throw BackupException(BackupIssue.INVALID_INPUT)
internal fun BackupRecord.optionalDecimal(name: String): Double? = when (val value = values[name]) {
    BackupCell.Null -> null
    is BackupCell.Decimal -> value.value
    else -> throw BackupException(BackupIssue.INVALID_INPUT)
}
internal fun backupText(value: String?): BackupCell = value?.let(BackupCell::Text) ?: BackupCell.Null
internal fun backupNumber(value: Long?): BackupCell = value?.let(BackupCell::Integer) ?: BackupCell.Null
internal fun backupFlag(value: Boolean?): BackupCell = value?.let(BackupCell::Flag) ?: BackupCell.Null
internal fun backupDecimal(value: Double?): BackupCell = value?.let(BackupCell::Decimal) ?: BackupCell.Null
internal fun backupStableId(vararg values: String): String {
    val bytes = ByteArrayOutputForIdentity()
    for (value in values) bytes.add(value)
    return bytes.finish()
}
private class ByteArrayOutputForIdentity {
    private val digest = java.security.MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array())
        digest.update(encoded)
    }
    fun finish(): String = backupHex(digest.digest())
}
