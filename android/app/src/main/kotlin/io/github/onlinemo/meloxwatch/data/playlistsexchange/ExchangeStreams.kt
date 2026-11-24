package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.security.MessageDigest

/** 只借用物理流；已有IO所有者连接取消并负责关闭，本包装不释放或重开提供方通道。 */
internal class ExchangeInput(private val input: InputStream) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val singleByte = ByteArray(1)
    private var completedChecksum: String? = null
    var byteCount: Long = 0L
        private set
    private var eof = false

    override fun read(): Int {
        return if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkExchangeBufferBounds(buffer.size, offset, length)
        if (length == 0) return 0
        if (eof) return -1
        // 最多多读一字节证明超限；恰好到预算必须继续核验EOF，不能把前缀当完整文件。
        val wanted = minOf(length.toLong(), PlaylistExchangeLimits.BYTES - byteCount + 1L).toInt()
        val count = try {
            input.read(buffer, offset, wanted)
        } catch (error: IOException) {
            throw PlaylistExchangeException(PlaylistExchangeIssue.READ_FAILED, error)
        }
        if (count < 0) {
            eof = true
            return -1
        }
        if (count == 0 || count > wanted) exchangeFail(PlaylistExchangeIssue.READ_FAILED)
        if (count > PlaylistExchangeLimits.BYTES - byteCount) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        digest.update(buffer, offset, count)
        byteCount += count
        return count
    }

    fun checksum(): String {
        check(eof)
        val previous = completedChecksum
        if (previous != null) return previous
        val checksum = exchangeDigestHex(digest.digest())
        completedChecksum = checksum
        return checksum
    }

    override fun close() {
        // 不关闭借用流；调用方仍拥有取消和物理占用的完成确认。
    }
}

internal class ExchangeOutput(private val output: OutputStream) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val singleByte = ByteArray(1)
    private var completedChecksum: String? = null
    var byteCount: Long = 0L
        private set

    override fun write(value: Int) {
        singleByte[0] = value.toByte()
        write(singleByte, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkExchangeBufferBounds(buffer.size, offset, length)
        check(completedChecksum == null)
        if (length > PlaylistExchangeLimits.BYTES - byteCount) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        if (length == 0) return
        try {
            output.write(buffer, offset, length)
        } catch (error: IOException) {
            throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED, error)
        }
        digest.update(buffer, offset, length)
        byteCount += length
    }

    override fun flush() {
        try {
            output.flush()
        } catch (error: IOException) {
            throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED, error)
        }
    }

    fun checksum(): String {
        val previous = completedChecksum
        if (previous != null) return previous
        val checksum = exchangeDigestHex(digest.digest())
        completedChecksum = checksum
        return checksum
    }

    override fun close() {
        // 目标文档的提交和失败清理属于IO所有者。
    }
}

internal fun exchangeReader(input: ExchangeInput, encoding: PlaylistExchangeEncoding): Reader {
    val buffered = PushbackInputStream(input, 4)
    val prefix = ByteArray(4)
    var prefixLength = 0
    while (prefixLength < prefix.size) {
        val count = buffered.read(prefix, prefixLength, prefix.size - prefixLength)
        if (count < 0) break
        prefixLength += count
    }
    val first = if (prefixLength > 0) prefix[0].toInt() and 0xff else -1
    val second = if (prefixLength > 1) prefix[1].toInt() and 0xff else -1
    val third = if (prefixLength > 2) prefix[2].toInt() and 0xff else -1
    val fourth = if (prefixLength > 3) prefix[3].toInt() and 0xff else -1
    val utf16Bom = first == 0xff && second == 0xfe || first == 0xfe && second == 0xff
    val utf32Bom = first == 0 && second == 0 && third == 0xfe && fourth == 0xff
    val utf8Bom = first == 0xef && second == 0xbb && third == 0xbf
    if (utf16Bom || utf32Bom || utf8Bom && encoding != PlaylistExchangeEncoding.UTF8) {
        exchangeFail(PlaylistExchangeIssue.INVALID_ENCODING)
    }
    if (prefixLength > 0) buffered.unread(prefix, 0, prefixLength)
    val charset = try {
        if (encoding == PlaylistExchangeEncoding.UTF8) StandardCharsets.UTF_8 else Charset.forName("GB18030")
    } catch (error: UnsupportedCharsetException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_ENCODING, error)
    }
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputStreamReader(buffered, decoder)
}

/** readLine会先分配整行；逐字符限额可在畸形长行完成前拒绝。 */
internal class ExchangeLines(input: Reader) {
    private val reader = BufferedReader(input, 1024)
    private var pending = -1

    fun next(): String? {
        val line = StringBuilder()
        while (true) {
            val next = if (pending >= 0) {
                val saved = pending
                pending = -1
                saved
            } else {
                read()
            }
            if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (next == '\n'.code) return line.toString()
            if (next == '\r'.code) {
                val following = read()
                if (following >= 0 && following != '\n'.code) pending = following
                return line.toString()
            }
            if (line.length == PlaylistExchangeLimits.LINE_CHARACTERS) {
                exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
            }
            line.append(next.toChar())
        }
    }

    private fun read(): Int = try {
        reader.read()
    } catch (error: CharacterCodingException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_ENCODING, error)
    } catch (error: IOException) {
        throw PlaylistExchangeException(PlaylistExchangeIssue.READ_FAILED, error)
    }
}

/**
 * 在JsonReader填充小缓冲区时限制字符串/标量/深度，并补足Android strict模式仍宽松的词法行为。
 * 不接管语法或schema；对象分号、大小写literal、未知转义和裸控制符必须在原始流中拒绝。
 */
internal class ExchangeJsonGuard(private val reader: Reader) : Reader() {
    private var inString = false
    private var escaped = false
    private var stringCharacters = 0
    private var decodedStringCharacters = 0
    private var unicodeCharactersRemaining = 0
    private val scalar = StringBuilder(64)
    private var depth = 0
    private var firstCharacter = true

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        checkExchangeBufferBounds(buffer.size, offset, length)
        if (length == 0) return 0
        val count = try {
            reader.read(buffer, offset, length)
        } catch (error: CharacterCodingException) {
            throw PlaylistExchangeException(PlaylistExchangeIssue.INVALID_ENCODING, error)
        }
        if (count == 0 || count > length) exchangeFail(PlaylistExchangeIssue.READ_FAILED)
        if (count < 0) {
            finishScalar()
            if (inString || depth != 0) exchangeFail(PlaylistExchangeIssue.MALFORMED)
            return -1
        }
        for (index in offset until offset + count) {
            if (firstCharacter) {
                if (buffer[index] == '\ufeff') buffer[index] = ' '
                firstCharacter = false
            }
            val char = buffer[index]
            if (inString) {
                readStringCharacter(char)
                continue
            }
            when (char) {
                '"' -> {
                    finishScalar()
                    inString = true
                    stringCharacters = 0
                    decodedStringCharacters = 0
                    escaped = false
                    unicodeCharactersRemaining = 0
                }
                '{', '[' -> {
                    finishScalar()
                    depth++
                    if (depth > PlaylistExchangeLimits.DEPTH) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
                }
                '}', ']' -> {
                    finishScalar()
                    depth--
                    if (depth < 0) exchangeFail(PlaylistExchangeIssue.MALFORMED)
                }
                ',', ':', ' ', '\r', '\n', '\t' -> finishScalar()
                else -> {
                    scalar.append(char)
                    if (scalar.length > 64) exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
                }
            }
        }
        return count
    }

    private fun readStringCharacter(char: Char) {
        if (!escaped && unicodeCharactersRemaining == 0 && char == '"') {
            inString = false
            return
        }
        stringCharacters++
        if (stringCharacters > PlaylistExchangeLimits.JSON_STRING_TOKEN_CHARACTERS) {
            exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        }
        if (unicodeCharactersRemaining > 0) {
            if (char !in '0'..'9' && char !in 'a'..'f' && char !in 'A'..'F') {
                exchangeFail(PlaylistExchangeIssue.MALFORMED)
            }
            unicodeCharactersRemaining--
            if (unicodeCharactersRemaining == 0) countDecodedCharacter()
            return
        }
        if (escaped) {
            escaped = false
            when (char) {
                'u' -> unicodeCharactersRemaining = 4
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> countDecodedCharacter()
                else -> exchangeFail(PlaylistExchangeIssue.MALFORMED)
            }
            return
        }
        if (char == '\\') {
            escaped = true
            return
        }
        if (char < ' ') exchangeFail(PlaylistExchangeIssue.MALFORMED)
        countDecodedCharacter()
    }

    private fun countDecodedCharacter() {
        decodedStringCharacters++
        if (decodedStringCharacters > PlaylistExchangeLimits.FIELD_CHARACTERS) {
            exchangeFail(PlaylistExchangeIssue.LIMIT_EXCEEDED)
        }
    }

    private fun finishScalar() {
        if (scalar.isEmpty()) return
        val value = scalar.toString()
        scalar.setLength(0)
        if (value != "true" && value != "false" && value != "null" && !JSON_NUMBER.matches(value)) {
            exchangeFail(PlaylistExchangeIssue.MALFORMED)
        }
    }

    override fun close() {
        // JsonReader.use只结束解析器，不关闭借用的提供方流。
    }

    private companion object {
        val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}

private fun checkExchangeBufferBounds(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
}

private fun exchangeDigestHex(bytes: ByteArray): String {
    val result = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    for (index in bytes.indices) {
        val byte = bytes[index].toInt() and 0xff
        result[index * 2] = digits[byte ushr 4]
        result[index * 2 + 1] = digits[byte and 0x0f]
    }
    return String(result)
}
