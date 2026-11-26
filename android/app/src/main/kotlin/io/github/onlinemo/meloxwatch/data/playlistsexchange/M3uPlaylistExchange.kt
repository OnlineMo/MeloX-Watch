package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 在调用方已有物理IO通道解析或写出；不扫描、不访问引用URI，也不回退到网络。 */
class M3uPlaylistExchange(private val newMemberId: () -> String = ::exchangeId) {
    suspend fun parse(
        input: InputStream,
        sink: ExchangePageSink,
        kind: M3uFileKind = M3uFileKind.M3U8,
        encoding: PlaylistExchangeEncoding = PlaylistExchangeEncoding.UTF8,
    ): ExchangeParseResult {
        currentCoroutineContext().ensureActive()
        if (kind == M3uFileKind.M3U8 && encoding != PlaylistExchangeEncoding.UTF8) {
            exchangeFail(PlaylistExchangeIssue.INVALID_ENCODING)
        }
        val bounded = ExchangeInput(input)
        val lines = ExchangeLines(exchangeReader(bounded, encoding))
        val page = ArrayList<ExchangeMember>(PlaylistExchangeLimits.PAGE_SIZE)
        val headers = mutableSetOf<String>()
        var header = ExchangePlaylistHeader(null)
        var firstLine = true
        var count = 0L
        var blocked = 0L
        var extended = false
        var pending = Pending()
        while (true) {
            currentCoroutineContext().ensureActive()
            var line = lines.next() ?: break
            currentCoroutineContext().ensureActive()
            if (firstLine) {
                line = line.removePrefix("\ufeff")
                firstLine = false
            }
            if (line.isBlank()) continue
            if (line.startsWith('#')) {
                when {
                    line == "#EXTM3U" -> {
                        if (extended || count > 0L || pending.present()) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        extended = true
                    }
                    line.startsWith("#PLAYLIST:") -> {
                        headerOnce(headers, "name", count)
                        header = header.copy(name = validateExchangeName(line.substringAfter(':')))
                    }
                    line.startsWith("#MELOX-PLAYLIST-KIND:") -> {
                        headerOnce(headers, "kind", count)
                        header = header.copy(kind = parseKind(line.substringAfter(':')))
                    }
                    line.startsWith("#MELOX-EXPORT-ID:") -> {
                        headerOnce(headers, "exportId", count)
                        val id = line.substringAfter(':')
                        validateExchangeId(id)
                        header = header.copy(exportId = id)
                    }
                    line.startsWith("#EXTINF:") -> {
                        if (pending.hasExtinf) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        val value = line.substringAfter(':')
                        val separator = value.indexOf(',')
                        if (separator < 0) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        pending = pending.copy(
                            hasExtinf = true,
                            durationMs = parseDurationMs(value.substring(0, separator).trim()),
                            title = validateExchangeText(value.substring(separator + 1)),
                        )
                    }
                    line.startsWith("#MELOX-TRACK-ID:") -> {
                        if (pending.trackKey != null || pending.cueReference != null) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        val id = line.substringAfter(':').takeIf { it.isNotBlank() }
                            ?: exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                        val key = TrackKey(TrackKey.LOCAL_PROVIDER, id)
                        validateExchangeKey(key)
                        pending = pending.copy(trackKey = key)
                    }
                    line.startsWith("#MELOX-CUE:") -> {
                        if (pending.cue != null || pending.cueReference != null) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        val value = line.substringAfter(':')
                        val separator = value.indexOf(',')
                        if (separator < 0) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        val track = value.substring(0, separator).toIntOrNull()
                        if (track == null || track !in 1..99) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                        val path = ExchangeRelativePath.parse(value.substring(separator + 1))
                        pending = pending.copy(cue = ExchangeCueHint(path, track))
                    }
                    line.startsWith("#MELOX-CUE-REF:") -> {
                        if (pending.cueReference != null || pending.hasExtinf) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                        val reference = ExchangeCueReferenceCodec.fromBase64(line.substringAfter(':'))
                        if (pending.trackKey != null && pending.trackKey != reference.trackKey ||
                            pending.cue != null && pending.cue.trackNumber != reference.identity.trackNumber) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                        pending = pending.copy(cueReference = reference)
                    }
                    line.startsWith("#EXT-X-") || line.startsWith("#MELOX-") -> {
                        exchangeFail(PlaylistExchangeIssue.UNSUPPORTED_FORMAT)
                    }
                    else -> Unit // 未识别注释不能改变授权或播放器选项。
                }
                continue
            }
            if (count == PlaylistExchangeLimits.MEMBERS) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
            validateExchangeText(line)
            val classified = classify(line)
            val cue = pending.cueReference?.let { reference ->
                ExchangeCueHint(pending.cue?.relativePath, reference.identity.trackNumber, reference)
            } ?: pending.cue
            val member = ExchangeMember(
                ordinal = count,
                memberId = newMemberId(),
                originalMemberId = classified.originalMemberId,
                trackKey = pending.trackKey ?: cue?.reference?.trackKey,
                relativePath = classified.path,
                mediaKind = if (cue == null) ExchangeMediaKind.AUDIO else ExchangeMediaKind.CUE_TRACK,
                cue = cue,
                title = pending.title,
                durationMs = pending.durationMs,
                unresolved = classified.issue != null,
                blockedReference = classified.blocked,
                referenceIssue = classified.issue,
            )
            validateExchangeMember(member)
            page.add(member)
            count++
            if (classified.issue != null) blocked++
            pending = Pending()
            if (page.size == PlaylistExchangeLimits.PAGE_SIZE) {
                currentCoroutineContext().ensureActive()
                sink.append(page.toList())
                page.clear()
            }
        }
        currentCoroutineContext().ensureActive()
        if (pending.present()) exchangeFail(PlaylistExchangeIssue.MALFORMED)
        if (page.isNotEmpty()) sink.append(page.toList())
        currentCoroutineContext().ensureActive()
        return ExchangeParseResult(header, count, blocked, bounded.byteCount, bounded.checksum())
    }

    /** 两种扩展名均写UTF-8；来源lease贯穿导出，目标选择、物理关闭和提交由IO所有者完成。 */
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
        val writer = BufferedWriter(OutputStreamWriter(bounded, encoder), 1024)
        writeLine(writer, "#EXTM3U")
        writeLine(writer, "#PLAYLIST:$name")
        writeLine(writer, "#MELOX-PLAYLIST-KIND:${header.kind.name.lowercase(Locale.ROOT)}")
        writeLine(writer, "#MELOX-EXPORT-ID:$exportId")
        var offset = 0L
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(PlaylistExchangeLimits.PAGE_SIZE.toLong(), count - offset).toInt()
            val page = source.page(offset, wanted)
            if (page.size != wanted) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
            source.validateSnapshot()
            currentCoroutineContext().ensureActive()
            for ((index, member) in page.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (member.ordinal != offset + index) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
                validateExchangeMember(member)
                member.trackKey?.let { writeLine(writer, "#MELOX-TRACK-ID:${it.trackId}") }
                member.cue?.let { cue ->
                    cue.relativePath?.let { writeLine(writer, "#MELOX-CUE:${cue.trackNumber},$it") }
                    cue.reference?.let { writeLine(writer, "#MELOX-CUE-REF:${ExchangeCueReferenceCodec.base64(it)}") }
                }
                writeLine(writer, "#EXTINF:${formatDurationMs(member.durationMs)},${member.title.orEmpty()}")
                // 不回显绝对路径/URI/被阻断的原始引用；占位只携带无访问能力的成员ID。
                val path = member.relativePath?.toString()
                val reference = when {
                    path == null -> "$UNRESOLVED_PREFIX${member.memberId}"
                    path.startsWith('#') -> "./$path"
                    else -> path
                }
                writeLine(writer, reference)
            }
            offset += page.size
        }
        source.validateSnapshot()
        currentCoroutineContext().ensureActive()
        try {
            writer.flush()
        } catch (error: IOException) {
            throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED, error)
        }
        source.validateSnapshot()
        currentCoroutineContext().ensureActive()
        return ExchangeWriteResult(count, bounded.byteCount, bounded.checksum())
    }

    private data class Pending(
        val hasExtinf: Boolean = false,
        val title: String? = null,
        val durationMs: Long? = null,
        val trackKey: TrackKey? = null,
        val cue: ExchangeCueHint? = null,
        val cueReference: io.github.onlinemo.meloxwatch.model.LocalCueReference? = null,
    ) {
        fun present(): Boolean = hasExtinf || trackKey != null || cue != null || cueReference != null
    }

    private data class Classified(
        val path: ExchangeRelativePath? = null,
        val originalMemberId: String? = null,
        val blocked: String? = null,
        val issue: ExchangeReferenceIssue? = null,
    )

    private fun classify(value: String): Classified {
        if (value.startsWith(UNRESOLVED_PREFIX)) {
            val id = value.removePrefix(UNRESOLVED_PREFIX)
            validateExchangeId(id)
            return Classified(originalMemberId = id, blocked = value, issue = ExchangeReferenceIssue.UNRESOLVED_MARKER)
        }
        if (value.startsWith('/') || value.startsWith('\\') || URI_SCHEME.containsMatchIn(value)) {
            return Classified(blocked = value, issue = ExchangeReferenceIssue.EXTERNAL_REFERENCE)
        }
        return try {
            Classified(path = ExchangeRelativePath.parse(value))
        } catch (error: PlaylistExchangeException) {
            if (error.issue != PlaylistExchangeIssue.INVALID_VALUE) throw error
            Classified(blocked = value, issue = ExchangeReferenceIssue.UNSAFE_RELATIVE_PATH)
        }
    }

    private fun parseDurationMs(value: String): Long? {
        if (value == "-1") return null
        if (value.length > 24) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        val normalized = value.removePrefix("+")
        if (!DURATION_SECONDS.matches(normalized)) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        val whole = normalized.substringBefore('.').toLongOrNull() ?: exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        val fraction = if ('.' in normalized) normalized.substringAfter('.').padEnd(3, '0').toLong() else 0L
        val result = try {
            Math.addExact(Math.multiplyExact(whole, 1000L), fraction)
        } catch (_: ArithmeticException) {
            exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        }
        return result.takeIf { it > 0L }
    }

    private fun formatDurationMs(value: Long?): String = if (value == null) "-1" else {
        val seconds = value / 1000L
        val remainder = value % 1000L
        if (remainder == 0L) seconds.toString() else "$seconds.${remainder.toString().padStart(3, '0')}"
    }

    private fun headerOnce(headers: MutableSet<String>, key: String, count: Long) {
        if (count > 0L || !headers.add(key)) exchangeFail(PlaylistExchangeIssue.MALFORMED)
    }

    private fun parseKind(value: String): ExchangePlaylistKind = when (value) {
        "manual" -> ExchangePlaylistKind.MANUAL
        "folder" -> ExchangePlaylistKind.FOLDER
        "favorites" -> ExchangePlaylistKind.FAVORITES
        else -> exchangeFail(PlaylistExchangeIssue.UNSUPPORTED_FORMAT)
    }

    private fun writeLine(writer: BufferedWriter, value: String) {
        if (value.length > PlaylistExchangeLimits.LINE_CHARACTERS) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        if (value.any { it == '\r' || it == '\n' }) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        try {
            writer.write(value)
            writer.write("\n")
        } catch (error: IOException) {
            throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED, error)
        }
    }

    private companion object {
        const val UNRESOLVED_PREFIX = "melox-unresolved:"
        val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val DURATION_SECONDS = Regex("[0-9]+(?:\\.[0-9]{1,3})?")
    }
}
