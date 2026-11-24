package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.MalformedJsonException
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 独立且严格的歌单交换v1；不处理应用备份、音频文件或URI授权。 */
class JsonPlaylistExchange(private val newMemberId: () -> String = ::exchangeId) {
    suspend fun parse(input: InputStream, sink: ExchangePageSink): ExchangeParseResult {
        currentCoroutineContext().ensureActive()
        val bounded = ExchangeInput(input)
        val guard = ExchangeJsonGuard(exchangeReader(bounded, PlaylistExchangeEncoding.UTF8))
        currentCoroutineContext().ensureActive()
        return JsonReader(guard).use { reader ->
            reader.isLenient = false
            expect(reader, JsonToken.BEGIN_OBJECT)
            jsonRead { reader.beginObject() }
            val seen = mutableSetOf<String>()
            var header: ExchangePlaylistHeader? = null
            var exportId: String? = null
            var count: Long? = null
            var blocked = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val hasField = jsonRead { reader.hasNext() }
                currentCoroutineContext().ensureActive()
                if (!hasField) break
                when (field(reader, seen)) {
                    "format" -> if (text(reader) != FORMAT) exchangeFail(PlaylistExchangeIssue.UNSUPPORTED_FORMAT)
                    "schemaVersion" -> {
                        if (integer(reader) != VERSION) exchangeFail(PlaylistExchangeIssue.UNSUPPORTED_VERSION)
                    }
                    "exportId" -> {
                        val id = text(reader)
                        validateExchangeId(id)
                        exportId = id
                    }
                    "playlist" -> {
                        header = parsePlaylistHeader(reader)
                    }
                    "members" -> {
                        expect(reader, JsonToken.BEGIN_ARRAY)
                        jsonRead { reader.beginArray() }
                        val page = ArrayList<ExchangeMember>(PlaylistExchangeLimits.PAGE_SIZE)
                        var ordinal = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val hasMember = jsonRead { reader.hasNext() }
                            currentCoroutineContext().ensureActive()
                            if (!hasMember) break
                            if (ordinal == PlaylistExchangeLimits.MEMBERS) {
                                exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
                            }
                            val member = parseMember(reader, ordinal)
                            currentCoroutineContext().ensureActive()
                            if (member.unresolved) blocked++
                            page.add(member)
                            ordinal++
                            if (page.size == PlaylistExchangeLimits.PAGE_SIZE) {
                                sink.append(page.toList())
                                currentCoroutineContext().ensureActive()
                                page.clear()
                            }
                        }
                        jsonRead { reader.endArray() }
                        currentCoroutineContext().ensureActive()
                        if (page.isNotEmpty()) {
                            sink.append(page.toList())
                            currentCoroutineContext().ensureActive()
                        }
                        count = ordinal
                    }
                    else -> exchangeFail(PlaylistExchangeIssue.UNKNOWN_FIELD)
                }
            }
            jsonRead { reader.endObject() }
            currentCoroutineContext().ensureActive()
            expect(reader, JsonToken.END_DOCUMENT)
            currentCoroutineContext().ensureActive()
            if (seen != setOf("format", "schemaVersion", "exportId", "playlist", "members")) {
                exchangeFail(PlaylistExchangeIssue.MALFORMED)
            }
            ExchangeParseResult(
                header = checkNotNull(header).copy(exportId = checkNotNull(exportId)),
                memberCount = checkNotNull(count),
                blockedReferenceCount = blocked,
                byteCount = bounded.byteCount,
                sha256 = bounded.checksum(),
            )
        }
    }

    suspend fun write(output: OutputStream, source: ExchangeExportSource): ExchangeWriteResult {
        currentCoroutineContext().ensureActive()
        val count = source.memberCount
        if (count < 0L || count > PlaylistExchangeLimits.MEMBERS) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        source.validateSnapshot()
        currentCoroutineContext().ensureActive()
        val header = source.header
        val name = validateExchangeName(header.name ?: exchangeFail(PlaylistExchangeIssue.INVALID_VALUE))
        val exportId = header.exportId ?: exchangeId()
        validateExchangeId(exportId)
        val bounded = ExchangeOutput(output)
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val writer = JsonWriter(OutputStreamWriter(bounded, encoder))
        writer.isLenient = false
        jsonWrite {
            writer.beginObject()
            writer.name("format").value(FORMAT)
            writer.name("schemaVersion").value(VERSION)
            writer.name("exportId").value(exportId)
            writer.name("playlist").beginObject()
            writer.name("name").value(name)
            writer.name("kind").value(header.kind.name.lowercase(Locale.ROOT))
            writer.endObject()
            writer.name("members").beginArray()
        }
        var offset = 0L
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(PlaylistExchangeLimits.PAGE_SIZE.toLong(), count - offset).toInt()
            val page = source.page(offset, wanted)
            if (page.size != wanted) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
            source.validateSnapshot()
            currentCoroutineContext().ensureActive()
            for ((index, value) in page.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (value.ordinal != offset + index) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                validateExchangeMember(value)
                writeMember(writer, value)
            }
            offset += page.size
        }
        source.validateSnapshot()
        currentCoroutineContext().ensureActive()
        jsonWrite {
            writer.endArray()
            writer.endObject()
            writer.flush()
        }
        source.validateSnapshot()
        currentCoroutineContext().ensureActive()
        return ExchangeWriteResult(count, bounded.byteCount, bounded.checksum())
    }

    private fun parsePlaylistHeader(reader: JsonReader): ExchangePlaylistHeader {
        expect(reader, JsonToken.BEGIN_OBJECT)
        jsonRead { reader.beginObject() }
        val seen = mutableSetOf<String>()
        var name: String? = null
        var kind: ExchangePlaylistKind? = null
        while (jsonRead { reader.hasNext() }) {
            when (field(reader, seen)) {
                "name" -> {
                    name = validateExchangeName(text(reader))
                }
                "kind" -> {
                    kind = when (text(reader)) {
                        "manual" -> ExchangePlaylistKind.MANUAL
                        "folder" -> ExchangePlaylistKind.FOLDER
                        "favorites" -> ExchangePlaylistKind.FAVORITES
                        else -> exchangeFail(PlaylistExchangeIssue.UNSUPPORTED_FORMAT)
                    }
                }
                else -> exchangeFail(PlaylistExchangeIssue.UNKNOWN_FIELD)
            }
        }
        jsonRead { reader.endObject() }
        if (seen != setOf("name", "kind")) exchangeFail(PlaylistExchangeIssue.MALFORMED)
        return ExchangePlaylistHeader(checkNotNull(name), checkNotNull(kind))
    }

    private fun parseMember(reader: JsonReader, ordinal: Long): ExchangeMember {
        expect(reader, JsonToken.BEGIN_OBJECT)
        jsonRead { reader.beginObject() }
        val seen = mutableSetOf<String>()
        var originalId: String? = null
        var key: TrackKey? = null
        var relative: ExchangeRelativePath? = null
        var mediaKind = ExchangeMediaKind.AUDIO
        var cue: ExchangeCueHint? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration: Long? = null
        var fingerprint: String? = null
        var unresolved = false
        while (jsonRead { reader.hasNext() }) {
            when (field(reader, seen)) {
                "memberId" -> {
                    val id = text(reader)
                    validateExchangeId(id)
                    originalId = id
                }
                "trackKey" -> {
                    key = if (readNull(reader)) null else parseTrackKey(reader)
                }
                "relativePath" -> {
                    relative = optionalText(reader)?.let(ExchangeRelativePath::parse)
                }
                "kind" -> {
                    mediaKind = when (text(reader)) {
                        "audio" -> ExchangeMediaKind.AUDIO
                        "cue_track" -> ExchangeMediaKind.CUE_TRACK
                        else -> exchangeFail(PlaylistExchangeIssue.UNSUPPORTED_FORMAT)
                    }
                }
                "cue" -> {
                    cue = if (readNull(reader)) null else parseCueHint(reader)
                }
                "title" -> {
                    title = optionalText(reader)
                }
                "artist" -> {
                    artist = optionalText(reader)
                }
                "album" -> {
                    album = optionalText(reader)
                }
                "durationMs" -> {
                    duration = if (readNull(reader)) null else integer(reader)
                    if (duration != null && duration <= 0L) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                }
                "contentSha256" -> {
                    fingerprint = optionalText(reader)
                }
                "unresolved" -> {
                    expect(reader, JsonToken.BOOLEAN)
                    unresolved = jsonRead { reader.nextBoolean() }
                }
                else -> exchangeFail(PlaylistExchangeIssue.UNKNOWN_FIELD)
            }
        }
        jsonRead { reader.endObject() }
        if (originalId == null) exchangeFail(PlaylistExchangeIssue.MALFORMED)
        val member = ExchangeMember(
            ordinal = ordinal,
            memberId = newMemberId(),
            originalMemberId = originalId,
            trackKey = key,
            relativePath = relative,
            mediaKind = mediaKind,
            cue = cue,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            contentSha256 = fingerprint,
            unresolved = unresolved,
        )
        validateExchangeMember(member)
        return member
    }

    private fun parseTrackKey(reader: JsonReader): TrackKey {
        expect(reader, JsonToken.BEGIN_OBJECT)
        jsonRead { reader.beginObject() }
        val seen = mutableSetOf<String>()
        var provider: String? = null
        var id: String? = null
        while (jsonRead { reader.hasNext() }) {
            when (field(reader, seen)) {
                "providerId" -> {
                    provider = text(reader, 64)
                }
                "trackId" -> {
                    id = text(reader, PlaylistExchangeLimits.TRACK_ID_CHARACTERS)
                }
                else -> exchangeFail(PlaylistExchangeIssue.UNKNOWN_FIELD)
            }
        }
        jsonRead { reader.endObject() }
        if (provider != TrackKey.LOCAL_PROVIDER || id.isNullOrBlank()) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        val key = TrackKey(checkNotNull(provider), checkNotNull(id))
        validateExchangeKey(key)
        return key
    }

    private fun parseCueHint(reader: JsonReader): ExchangeCueHint {
        expect(reader, JsonToken.BEGIN_OBJECT)
        jsonRead { reader.beginObject() }
        val seen = mutableSetOf<String>()
        var path: ExchangeRelativePath? = null
        var track: Long? = null
        var reference: io.github.onlinemo.meloxwatch.model.LocalCueReference? = null
        while (jsonRead { reader.hasNext() }) {
            when (field(reader, seen)) {
                "relativePath" -> {
                    path = optionalText(reader)?.let(ExchangeRelativePath::parse)
                }
                "trackNumber" -> {
                    track = integer(reader)
                }
                "reference" -> reference = if (readNull(reader)) null else ExchangeCueReferenceCodec.decode(
                    text(reader, PlaylistExchangeLimits.CUE_REFERENCE_BYTES))
                else -> exchangeFail(PlaylistExchangeIssue.UNKNOWN_FIELD)
            }
        }
        jsonRead { reader.endObject() }
        if (path == null && reference == null || track == null || track !in 1L..99L ||
            reference != null && reference.identity.trackNumber.toLong() != track) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        return ExchangeCueHint(path, checkNotNull(track).toInt(), reference)
    }

    private fun writeMember(writer: JsonWriter, member: ExchangeMember) = jsonWrite {
        writer.beginObject()
        writer.name("memberId").value(member.memberId)
        writer.name("kind").value(member.mediaKind.name.lowercase(Locale.ROOT))
        member.trackKey?.let { key ->
            writer.name("trackKey").beginObject()
            writer.name("providerId").value(key.providerId)
            writer.name("trackId").value(key.trackId)
            writer.endObject()
        }
        member.relativePath?.let { writer.name("relativePath").value(it.toString()) }
        member.cue?.let { cue ->
            writer.name("cue").beginObject()
            cue.relativePath?.let { writer.name("relativePath").value(it.toString()) }
            writer.name("trackNumber").value(cue.trackNumber.toLong())
            cue.reference?.let { writer.name("reference").value(ExchangeCueReferenceCodec.encode(it)) }
            writer.endObject()
        }
        member.title?.let { writer.name("title").value(it) }
        member.artist?.let { writer.name("artist").value(it) }
        member.album?.let { writer.name("album").value(it) }
        member.durationMs?.let { writer.name("durationMs").value(it) }
        member.contentSha256?.let { writer.name("contentSha256").value(it) }
        writer.name("unresolved").value(member.unresolved)
        writer.endObject()
    }

    private fun field(reader: JsonReader, seen: MutableSet<String>): String {
        val name = validateExchangeText(jsonRead { reader.nextName() }, 64)
        if (!seen.add(name)) exchangeFail(PlaylistExchangeIssue.MALFORMED)
        return name
    }

    private fun text(reader: JsonReader, limit: Int = PlaylistExchangeLimits.FIELD_CHARACTERS): String {
        expect(reader, JsonToken.STRING)
        return validateExchangeText(jsonRead { reader.nextString() }, limit)
    }

    private fun optionalText(reader: JsonReader): String? = if (readNull(reader)) null else text(reader)

    private fun readNull(reader: JsonReader): Boolean {
        if (jsonRead { reader.peek() } != JsonToken.NULL) return false
        jsonRead { reader.nextNull() }
        return true
    }

    private fun integer(reader: JsonReader): Long {
        expect(reader, JsonToken.NUMBER)
        val value = jsonRead { reader.nextString() }
        if (!UNSIGNED_INTEGER.matches(value)) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        return value.toLongOrNull() ?: exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }

    private fun expect(reader: JsonReader, token: JsonToken) {
        if (jsonRead { reader.peek() } != token) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }

    /** 只翻译解析API错误；取消继承IllegalStateException，必须先重抛，暂存/来源失败不进入此边界。 */
    private fun <T> jsonRead(action: () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: PlaylistExchangeException) {
        throw error
    } catch (error: EOFException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.MALFORMED, error)
    } catch (error: MalformedJsonException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.MALFORMED, error)
    } catch (error: IOException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.READ_FAILED, error)
    } catch (error: IllegalStateException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.MALFORMED, error)
    } catch (error: NumberFormatException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_VALUE, error)
    }

    private fun <T> jsonWrite(action: () -> T): T = try {
        action()
    } catch (error: IOException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED, error)
    }

    private companion object {
        const val FORMAT = "melox-local-playlist"
        const val VERSION = 1L
        val UNSIGNED_INTEGER = Regex("0|[1-9][0-9]*")
    }
}
