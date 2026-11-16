package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.model.TrackKey
import java.text.Normalizer
import java.util.UUID

/** 单次交换文件的输入/输出预算，不限制持久音乐库或歌单的逻辑条数。 */
object PlaylistExchangeLimits {
    const val BYTES = 8 * 1024 * 1024L
    const val MEMBERS = 100_000L
    const val PAGE_SIZE = 100
    const val DEPTH = 16
    const val LINE_CHARACTERS = 8192
    const val FIELD_CHARACTERS = 4096
    const val NAME_CODE_POINTS = 80
    const val TRACK_ID_CHARACTERS = 1024
    const val JSON_STRING_TOKEN_CHARACTERS = FIELD_CHARACTERS * 6
}

enum class PlaylistExchangeEncoding { UTF8, GB18030 }
enum class M3uFileKind { M3U, M3U8 }
enum class ExchangePlaylistKind { MANUAL, FOLDER, FAVORITES }
enum class ExchangeMediaKind { AUDIO, CUE_TRACK }
enum class ExchangeReferenceIssue { EXTERNAL_REFERENCE, UNSAFE_RELATIVE_PATH, UNRESOLVED_MARKER }
enum class PlaylistExchangeIssue {
    INVALID_VALUE, INVALID_ENCODING, MALFORMED, UNKNOWN_FIELD,
    UNSUPPORTED_VERSION, UNSUPPORTED_FORMAT, LIMIT_EXCEEDED, READ_FAILED, WRITE_FAILED,
}

/**
 * 对外消息只有稳定错误码；底层解析/IO异常只保留类型和栈帧，不携带原始消息、cause或suppressed。
 * 输入字段及提供方的异常消息可能含私有路径或凭据，不能成为诊断输出。
 */
class PlaylistExchangeException(val issue: PlaylistExchangeIssue, cause: Throwable? = null) :
    RuntimeException(issue.name, exchangeDiagnosticCause(cause))

private fun exchangeDiagnosticCause(cause: Throwable?): Throwable? {
    if (cause == null) return null
    val diagnostic = RuntimeException(cause.javaClass.name)
    diagnostic.stackTrace = cause.stackTrace
    return diagnostic
}

/** CUE清单的相对提示和原始轨号；命中实体音频路径不能替代逻辑分轨匹配。 */
data class ExchangeCueHint(val relativePath: ExchangeRelativePath, val trackNumber: Int) {
    init {
        require(trackNumber in 1..99)
    }
}

data class ExchangePlaylistHeader(
    val name: String?,
    val kind: ExchangePlaylistKind = ExchangePlaylistKind.MANUAL,
    val exportId: String? = null,
)

/**
 * 提示不是授权或匹配证明，resolver只能查询另行授权的本地目录快照。
 * CUE提示必须匹配逻辑分轨；未匹配时保留占位，不能回退成整段音频。
 * blockedReference是私有暂存预览文本，UI须安全显示，两个writer均不导出该原始引用。
 */
data class ExchangeMember(
    val ordinal: Long,
    val memberId: String,
    val originalMemberId: String? = null,
    val trackKey: TrackKey? = null,
    val relativePath: ExchangeRelativePath? = null,
    val mediaKind: ExchangeMediaKind = ExchangeMediaKind.AUDIO,
    val cue: ExchangeCueHint? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val contentSha256: String? = null,
    val unresolved: Boolean = false,
    val blockedReference: String? = null,
    val referenceIssue: ExchangeReferenceIssue? = null,
)

data class ExchangeParseResult(
    val header: ExchangePlaylistHeader,
    val memberCount: Long,
    val blockedReferenceCount: Long,
    val byteCount: Long,
    val sha256: String,
)

/** 写完借用流不等于目标文档已提交；IO所有者另行核验、提交或清理本次未完成目标。 */
data class ExchangeWriteResult(val memberCount: Long, val byteCount: Long, val sha256: String)

/** 页属于私有暂存generation；解析成功后仍须预览/确认，失败或取消由所有者丢弃整代暂存。 */
fun interface ExchangePageSink {
    suspend fun append(page: List<ExchangeMember>)
}

/** 整次导出持有同一不可变歌单/目录lease；空歌单也须核验，page只返回请求的有界批次。 */
interface ExchangeExportSource {
    val header: ExchangePlaylistHeader
    val memberCount: Long
    suspend fun page(offset: Long, limit: Int): List<ExchangeMember>
    suspend fun validateSnapshot()
}

internal fun exchangeId(): String = UUID.randomUUID().toString()

internal fun validateExchangeId(value: String) {
    val valid = try {
        value.length == 36 && UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }
    if (!valid) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
}

internal fun validateExchangeText(value: String, limit: Int = PlaylistExchangeLimits.FIELD_CHARACTERS): String {
    if (value.length > limit) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\u0000') exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        if (Character.isHighSurrogate(char)) {
            if (index + 1 == value.length || !Character.isLowSurrogate(value[index + 1])) {
                exchangeFail(PlaylistExchangeIssue.INVALID_ENCODING)
            }
            index++
        } else if (Character.isLowSurrogate(char)) {
            exchangeFail(PlaylistExchangeIssue.INVALID_ENCODING)
        }
        index++
    }
    return value
}

internal fun validateExchangeName(value: String): String {
    validateExchangeText(value)
    val name = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    if (name.isEmpty() || name.codePointCount(0, name.length) > PlaylistExchangeLimits.NAME_CODE_POINTS ||
        containsExchangeControl(name)) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
    return name
}

internal fun validateExchangeKey(key: TrackKey) {
    validateExchangeText(key.trackId, PlaylistExchangeLimits.TRACK_ID_CHARACTERS)
    if (key.providerId != TrackKey.LOCAL_PROVIDER || key.trackId.isBlank() || containsExchangeControl(key.trackId)) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
}

/** 按完整code point拒绝C0/C1、格式控制及行/段分隔；补充平面Cf不能藏在surrogate pair中。 */
internal fun containsExchangeControl(value: String): Boolean {
    var offset = 0
    while (offset < value.length) {
        val codePoint = Character.codePointAt(value, offset)
        val type = Character.getType(codePoint)
        if (Character.isISOControl(codePoint) || type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()) {
            return true
        }
        offset += Character.charCount(codePoint)
    }
    return false
}

private fun validateExchangeMetadata(value: String) {
    validateExchangeText(value)
    var offset = 0
    while (offset < value.length) {
        val codePoint = Character.codePointAt(value, offset)
        val type = Character.getType(codePoint)
        // JSON可正确转义换行/Tab；其它控制或格式字符不能伪装可读元数据。
        val allowedLineControl = codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code
        if (Character.isISOControl(codePoint) && !allowedLineControl || type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()) {
            exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
        }
        offset += Character.charCount(codePoint)
    }
}

internal fun validateExchangeMember(member: ExchangeMember) {
    if (member.ordinal < 0L || member.ordinal >= PlaylistExchangeLimits.MEMBERS) {
        exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
    }
    validateExchangeId(member.memberId)
    member.originalMemberId?.let(::validateExchangeId)
    member.trackKey?.let(::validateExchangeKey)
    member.title?.let { validateExchangeMetadata(it) }
    member.artist?.let { validateExchangeMetadata(it) }
    member.album?.let { validateExchangeMetadata(it) }
    member.blockedReference?.let { validateExchangeText(it) }
    if (member.durationMs != null && member.durationMs <= 0L) exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    if (member.contentSha256 != null && !member.contentSha256.matches(EXCHANGE_SHA256)) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
    if ((member.mediaKind == ExchangeMediaKind.CUE_TRACK) != (member.cue != null)) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
    if (member.trackKey == null && member.relativePath == null && member.contentSha256 == null &&
        member.cue == null && !member.unresolved) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
    if ((member.blockedReference == null) != (member.referenceIssue == null)) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
    if (member.referenceIssue != null && (!member.unresolved || member.relativePath != null)) {
        exchangeFail(PlaylistExchangeIssue.INVALID_VALUE)
    }
}

internal fun exchangeFail(issue: PlaylistExchangeIssue): Nothing = throw PlaylistExchangeException(issue)

private val EXCHANGE_SHA256 = Regex("[0-9a-f]{64}")
