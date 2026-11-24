package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonNull
import io.github.onlinemo.meloxwatch.data.network.JsonNumber
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.JsonText
import io.github.onlinemo.meloxwatch.data.network.JsonValue
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 曲目投影与显式备份共用的有界逻辑记录；序列化不打开文件或访问网络。 */
internal object LocalCodecParametersCodec {
    const val MAX_BYTES = 2048
    private const val SCHEMA = 1
    private val required = setOf("schema", "container", "encoding", "inputSampleRateHz", "inputBitsPerSample",
        "channels", "pcmSampleRateHz", "pcmBitsPerSample")
    private val allowed = required + setOf("version", "compressionLevel")

    fun encode(value: LocalCodecParameters): String = checked {
        val fields = linkedMapOf<String, JsonValue>(
            "schema" to JsonNumber(SCHEMA.toString()),
            "container" to JsonText(label(value.container)), "encoding" to JsonText(label(value.encoding)),
            "inputSampleRateHz" to JsonNumber(value.inputSampleRateHz.toString()),
            "inputBitsPerSample" to JsonNumber(value.inputBitsPerSample.toString()),
            "channels" to JsonNumber(value.channels.toString()),
            "pcmSampleRateHz" to JsonNumber(value.pcmSampleRateHz.toString()),
            "pcmBitsPerSample" to JsonNumber(value.pcmBitsPerSample.toString()),
        )
        value.version?.let { fields["version"] = JsonNumber(it.toString()) }
        value.compressionLevel?.let { fields["compressionLevel"] = JsonNumber(it.toString()) }
        String(StrictJson.encode(JsonObject(fields), MAX_BYTES), Charsets.UTF_8)
    }

    fun decode(value: String): LocalCodecParameters = checked {
        require(value.length <= MAX_BYTES)
        // 损坏的 UTF-16 必须报错，不能在编码时替换字符后再保存。
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(buffer.remaining() <= MAX_BYTES)
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val record = StrictJson.decode(bytes, JsonBounds(MAX_BYTES, depth = 1, nodes = 11,
            stringChars = 64, members = 10)) as? JsonObject ?: throw IllegalArgumentException()
        require(record.fields.keys.containsAll(required) && record.fields.keys.all { it in allowed })
        require(integer(record, "schema") == SCHEMA)
        fun text(name: String) = label((record[name] as? JsonText)?.value ?: throw IllegalArgumentException())
        LocalCodecParameters(text("container"), text("encoding"), integer(record, "inputSampleRateHz"),
            integer(record, "inputBitsPerSample"), integer(record, "channels"), integer(record, "pcmSampleRateHz"),
            integer(record, "pcmBitsPerSample"), optionalInteger(record, "version"), optionalInteger(record, "compressionLevel"))
    }

    private fun integer(record: JsonObject, name: String): Int {
        val number = (record[name] as? JsonNumber)?.value?.toLongOrNull() ?: throw IllegalArgumentException()
        require(number in 1L..Int.MAX_VALUE.toLong())
        return number.toInt()
    }

    private fun optionalInteger(record: JsonObject, name: String): Int? = when (record[name]) {
        null, JsonNull -> null
        else -> integer(record, name)
    }

    private fun label(value: String): String {
        require(value.isNotBlank() && value.length <= 64 && value.none { Character.isISOControl(it) })
        return value
    }

    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (failure: NetworkException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, failure) }
    catch (failure: CharacterCodingException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, failure) }
    catch (failure: IllegalArgumentException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, failure) }
}
