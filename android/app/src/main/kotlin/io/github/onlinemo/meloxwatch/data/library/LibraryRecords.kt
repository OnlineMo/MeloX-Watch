package io.github.onlinemo.meloxwatch.data.library

import android.content.Intent
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object LibraryCodec {
    fun source(value: LocalSourceSpec): String = JSONObject().apply {
        put("schema", 1); put("sourceId", value.sourceId); put("kind", value.kind.name)
        put("uri", value.uri); put("grantFlags", value.grantFlags); put("mediaVolume", value.mediaVolume)
        put("authority", value.authority); put("rootObjectId", value.rootObjectId); put("incarnation", value.providerIncarnation)
    }.toString()

    fun source(value: String): LocalSourceSpec = JSONObject(value).let {
        require(it.getInt("schema") == 1)
        LocalSourceSpec(it.getString("sourceId"), LocalSourceKind.valueOf(it.getString("kind")), it.getString("uri"),
            it.getInt("grantFlags"), it.getString("mediaVolume"), it.nullString("authority"), it.nullString("rootObjectId"), it.nullString("incarnation"))
    }

    fun grant(value: LocalGrantObservation): String = JSONObject().apply {
        put("schema", 1)
        put("read", value.read.name); put("enumerate", value.enumerate.name)
        put("persistedRead", value.persistedRead.name); put("persistedWrite", value.persistedWrite.name)
        put("offlineRead", value.offlineRead.name); put("write", value.write.name); put("delete", value.delete.name)
        put("rename", value.rename.name); put("move", value.move.name)
        put("declaredWrite", value.declaredWrite); put("declaredDelete", value.declaredDelete)
        put("declaredRename", value.declaredRename); put("declaredMove", value.declaredMove)
        put("appOwned", value.appOwned); put("returnedFlags", value.returnedFlags); put("acceptedPersistedFlags", value.acceptedPersistedFlags)
        put("persistenceAttempted", value.persistenceAttempted); put("persistenceRejected", value.persistenceRejected)
    }.toString()

    fun grant(value: String): LocalGrantObservation = JSONObject(value).let { j ->
        require(j.getInt("schema") == 1)
        fun capability(name: String) = ObservedCapability.valueOf(j.getString(name))
        LocalGrantObservation(capability("read"), capability("enumerate"), capability("persistedRead"), capability("persistedWrite"),
            capability("offlineRead"), capability("write"), capability("delete"), capability("rename"), capability("move"),
            j.nullBoolean("declaredWrite"), j.nullBoolean("declaredDelete"), j.nullBoolean("declaredRename"), j.nullBoolean("declaredMove"),
            j.getBoolean("appOwned"), j.getInt("returnedFlags"), j.getInt("acceptedPersistedFlags"),
            j.optBoolean("persistenceAttempted", false), j.optBoolean("persistenceRejected", false))
    }

    fun candidate(value: ObservedCandidate): String = JSONObject().apply {
        put("schema", 1)
        put("sourceId", value.sourceId); put("objectId", value.objectId); put("uri", value.uri)
        put("parentObjectId", value.parentObjectId); put("displayName", value.displayName); put("mimeType", value.mimeType)
        put("kind", value.kind.name); put("sizeBytes", value.sizeBytes); put("modifiedEpochMs", value.modifiedEpochMs)
        put("providerVersion", value.providerVersion); put("addedEpochSeconds", value.addedEpochSeconds)
        put("generationAdded", value.generationAdded); put("generationModified", value.generationModified)
        put("providerContentToken", value.providerContentToken); put("entityProof", value.entityProof)
    }.toString()

    fun candidate(value: String): ObservedCandidate = JSONObject(value).let {
        require(it.getInt("schema") == 1)
        ObservedCandidate(it.getString("sourceId"), it.getString("objectId"), it.getString("uri"), it.nullString("parentObjectId"),
            it.getString("displayName"), it.nullString("mimeType"), LocalNodeKind.valueOf(it.getString("kind")),
            it.nullLong("sizeBytes"), it.nullLong("modifiedEpochMs"), it.nullString("providerVersion"), it.nullLong("addedEpochSeconds"),
            it.nullLong("generationAdded"), it.nullLong("generationModified"), it.nullString("providerContentToken"), it.nullString("entityProof"))
    }

    fun metadata(value: LocalAudioMetadata): String = JSONObject().apply {
        put("schema", 1); put("title", value.title); put("artist", value.artist); put("artists", JSONArray(value.artists))
        put("album", value.album); put("albumArtist", value.albumArtist); put("durationMs", value.durationMs)
        put("discNumber", value.discNumber); put("trackNumber", value.trackNumber); put("compilation", value.compilation)
        put("mimeType", value.mimeType); put("parserVersion", value.parserVersion)
        value.codecParameters?.let { put("codecParameters", codecParameters(it)) }
    }.toString()

    fun metadata(value: String): LocalAudioMetadata = LibraryMetadataJson.metadata(value)

    private fun codecParameters(value: LocalCodecParameters) = JSONObject(LocalCodecParametersCodec.encode(value))

    fun outcome(value: MetadataOutcome): String = JSONObject().apply {
        put("schema", 1); put("objectId", value.objectId); put("metadata", value.metadata?.let(::metadata))
        put("issue", value.issue?.name)
        put("verifiedContentToken", value.verifiedContentToken)
    }.toString()

    fun outcome(value: String): MetadataOutcome = LibraryMetadataJson.outcome(value)

    fun strings(value: List<String>): String = JSONArray(value).toString()
    fun strings(value: String): List<String> = JSONArray(value).let { j -> List(j.length()) { j.getString(it) } }
    private fun JSONObject.nullString(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    private fun JSONObject.nullLong(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
    private fun JSONObject.nullBoolean(name: String): Boolean? = if (!has(name) || isNull(name)) null else getBoolean(name)
}

/** Version 1 uses Unicode NFC/ROOT lowercase and code-point keys; it does not claim pinyin. */
internal object LibrarySortKeys {
    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    fun section(value: String?): String {
        val first = value?.let(::normalize)?.codePoints()?.findFirst()?.orElse(-1) ?: -1
        return when {
            first in 'a'.code..'z'.code -> first.toChar().uppercaseChar().toString()
            first in '0'.code..'9'.code -> "DIGITS"
            first in 0x3400..0x4dbf || first in 0x4e00..0x9fff || first in 0x20000..0x323af -> "HAN"
            first < 0 -> "UNKNOWN"
            else -> "OTHER"
        }
    }
    private fun sectionRank(section: String): Char = when (section) {
        "DIGITS" -> '1'; "HAN" -> '2'; "OTHER" -> '3'; "UNKNOWN" -> '4'; else -> '0'
    }
    fun text(value: String?): String {
        val normal = value?.let(::normalize).orEmpty()
        return sectionRank(section(value)) + ":" + normal.codePoints().toArray().joinToString("") { it.toString(16).padStart(6, '0') }
    }
    fun natural(value: String): String {
        val normal = normalize(value)
        val result = StringBuilder().append(sectionRank(section(value))).append(':')
        var position = 0
        while (position < normal.length) {
            val point = normal.codePointAt(position)
            if (point in '0'.code..'9'.code) {
                var end = position
                while (end < normal.length && normal[end] in '0'..'9') end++
                val digits = normal.substring(position, end).trimStart('0').ifEmpty { "0" }
                // Length plus decimal digits compares numbers of arbitrary size without overflow.
                result.append('1').append(digits.length.toString().padStart(4, '0')).append(':').append(digits).append(';')
                position = end
            } else {
                result.append('0').append(point.toString(16).padStart(6, '0'))
                position += Character.charCount(point)
            }
        }
        return result.toString()
    }
    fun album(metadata: LocalAudioMetadata): String {
        val album = metadata.album?.takeIf { it.isNotBlank() } ?: return "UNKNOWN"
        val owner = if (metadata.compilation) "COMPILATION" else metadata.albumArtist?.takeIf { it.isNotBlank() }
            ?: metadata.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: metadata.artist.orEmpty()
        return identity("ALBUM", normalize(album), normalize(owner))
    }
    fun identity(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

/** 当前授权行的平面事实与SQL门槛一致；旧JSON不能恢复已拒绝的读权或持久标记。 */
internal fun LibraryGrantEntity.model(): LocalGrantObservation {
    val read = ObservedCapability.entries.firstOrNull { it.name == readCapability }
        ?: ObservedCapability.UNAVAILABLE
    val persistedMask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val persistedFlags = acceptedPersistedFlags.takeIf { it >= 0 && (it and persistedMask.inv()) == 0 } ?: 0
    // 未列出的枚举、写删、声明能力和持久化结果仍保留JSON观测；UNKNOWN只准许进入验证。
    return LibraryCodec.grant(observation).copy(
        read = read,
        acceptedPersistedFlags = persistedFlags,
        appOwned = appOwned,
    )
}

internal fun LibrarySourceEntity.model(grant: LibraryGrantEntity): LibrarySource {
    require(grant.grantId == grantId && grant.sourceId == sourceId)
    return LibrarySource(LibraryCodec.source(spec), grant.model(), grantId, revision, authEpoch,
        LibrarySourceState.valueOf(state), activeScanId, latestScanId)
}

internal fun LibraryScanEntity.token() = ScanToken(scanId, sourceId, sourceRevision, authEpoch, rulesRevision, ScanMode.valueOf(mode))
internal fun LibraryScanEntity.coverage() = ScanCoverage(enumerationComplete, authorizationValid, rulesUnchanged, sourceUnchanged, excludedSubtrees)
internal fun LibraryScanEntity.model() = ScanState(token(), ScanPhase.valueOf(phase), ScanCounters(seen, processed, added, skipped, failed),
    coverage(), issue?.let(SourceReadIssue::valueOf))
internal fun LibraryScanEntity.withCoverage(value: ScanCoverage) = copy(enumerationComplete = value.enumerationComplete,
    authorizationValid = value.authorizationValid, rulesUnchanged = value.rulesUnchanged, sourceUnchanged = value.sourceUnchanged,
    excludedSubtrees = value.excludedSubtrees)

internal fun LibraryTrackVersionEntity.metadata() = LocalAudioMetadata(title, artist, LibraryCodec.strings(artists), album, albumArtist,
    durationMs, discNumber, trackNumber, compilation, mimeType, parserVersion,
    codecParameters?.let(LocalCodecParametersCodec::decode))

internal fun LibraryRowRecord.cueReference(): LocalCueReference? = track.cueReference?.let {
    CueReferenceCodec.decode(it).also { reference ->
        require(reference.trackKey == TrackKey(track.providerId, track.trackId))
    }
}

internal fun LibraryRowRecord.reference(cue: LocalCueReference? = cueReference()): LocalLocationReference? {
    if (cue != null) return cue.binding?.audio?.location
    val locationId = selectedLocationId ?: return null
    val uri = selectedUri ?: return null
    val grant = selectedGrantObservation?.let(LibraryCodec::grant) ?: return null
    val kind = selectedSpec?.let(LibraryCodec::source)?.kind ?: return null
    val access = when {
        grant.appOwned && kind == LocalSourceKind.APP_OWNED -> LocalAccess.APP_OWNED
        kind == LocalSourceKind.MEDIA_STORE -> LocalAccess.MEDIA_PERMISSION
        grant.persistedRead == ObservedCapability.AVAILABLE && grant.acceptedPersistedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 -> LocalAccess.PERSISTED
        else -> LocalAccess.TEMPORARY
    }
    return LocalLocationReference(locationId, uri, selectedGrantUri ?: uri, grant.acceptedPersistedFlags, access, selectedContentRevision ?: 0L)
}

internal fun LibraryRowRecord.model(): LibraryTrack {
    val cue = cueReference()
    return LibraryTrack(TrackKey(track.providerId, track.trackId), track.displayTitle, track.filename,
        track.metadata(), LibraryAvailability.entries.getOrNull(availabilityRank) ?: throw LibraryException(LibraryIssue.RECOVERY_REQUIRED),
        track.metadataIssue, track.firstAddedEpochMs, track.firstAddedSequence, displayAlbumKey, reference(cue), selectedSourceId,
        selectedAliasId, selectedNodeId, effectivePlayCount, effectiveListeningMs, displayArtist, displayAlbum, cue)
}

internal fun LibraryRowRecord.cursor(view: LibraryViewToken) = LibraryCursor(view.viewId, view.revision, view.sortRuleVersion,
    missingRank, nullRank, sortKey, track.providerId, track.trackId)
