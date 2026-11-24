package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals

internal class ExchangeBorrowedInput(bytes: ByteArray, private val chunkSize: Int = Int.MAX_VALUE) :
    ByteArrayInputStream(bytes) {
    var closeCount = 0
        private set
    var readBytes = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) readBytes++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, minOf(length, chunkSize))
        if (count > 0) readBytes += count
        return count
    }

    override fun close() {
        closeCount++
        super.close()
    }
}

internal class ExchangeBorrowedOutput : ByteArrayOutputStream() {
    var closeCount = 0
        private set

    override fun close() {
        closeCount++
        super.close()
    }
}

/** 巨型输入按固定小样本生成，测试不在堆内保存整个交换文件。 */
internal class ExchangeRepeatedInput(private val pattern: ByteArray, repeats: Long) : InputStream() {
    private val byteSize = pattern.size.toLong() * repeats
    var position = 0L
        private set
    var closeCount = 0
        private set

    init {
        require(pattern.isNotEmpty())
        require(repeats >= 0L)
    }

    override fun read(): Int {
        if (position == byteSize) return -1
        val value = pattern[(position % pattern.size).toInt()].toInt() and 0xff
        position++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == byteSize) return -1
        val count = minOf(length.toLong(), byteSize - position).toInt()
        for (index in 0 until count) {
            buffer[offset + index] = pattern[((position + index) % pattern.size).toInt()]
        }
        position += count
        return count
    }

    override fun close() {
        closeCount++
    }
}

internal data class ExchangePageRequest(val offset: Long, val limit: Int)

internal class ExchangeFixtureSource(
    members: List<ExchangeMember>,
    override val header: ExchangePlaylistHeader = ExchangePlaylistHeader("Playlist", exportId = exchangeTestId(9_999L)),
) : ExchangeExportSource {
    private val members = members.toList()
    override val memberCount: Long = members.size.toLong()
    val requests = mutableListOf<ExchangePageRequest>()
    var validations = 0
        private set

    override suspend fun page(offset: Long, limit: Int): List<ExchangeMember> {
        requests.add(ExchangePageRequest(offset, limit))
        return members.subList(offset.toInt(), offset.toInt() + limit).toList()
    }

    override suspend fun validateSnapshot() {
        validations++
    }
}

internal fun exchangeTestId(value: Long): String = UUID(0L, value + 1L).toString()

internal fun exchangeFreshIds(start: Long = 1_000_000L): () -> String {
    var next = start
    return { exchangeTestId(next++) }
}

internal fun exchangeMember(ordinal: Long, path: String = "music/$ordinal.flac"): ExchangeMember = ExchangeMember(
    ordinal = ordinal,
    memberId = exchangeTestId(ordinal),
    trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "track-$ordinal"),
    relativePath = ExchangeRelativePath.parse(path),
    title = "Title $ordinal",
    durationMs = 123_456L + ordinal,
)

internal fun exchangeUtf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

internal fun exchangeTestSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun exchangeJson(members: String = exchangeJsonMember()): String = """
    {"format":"melox-local-playlist","schemaVersion":1,"exportId":"${exchangeTestId(9_999L)}",
     "playlist":{"name":"Playlist","kind":"manual"},"members":[$members]}
""".trimIndent()

internal fun exchangeJsonMember(): String = """
    {"memberId":"${exchangeTestId(0L)}","kind":"audio","relativePath":"music/one.flac","unresolved":false}
""".trimIndent()

internal suspend fun expectExchangeIssue(
    expected: PlaylistExchangeIssue,
    action: suspend () -> Unit,
): PlaylistExchangeException {
    try {
        action()
    } catch (error: PlaylistExchangeException) {
        assertEquals(expected, error.issue)
        return error
    }
    throw AssertionError("Expected exchange issue $expected")
}
