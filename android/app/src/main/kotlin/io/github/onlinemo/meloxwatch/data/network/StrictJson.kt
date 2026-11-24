package io.github.onlinemo.meloxwatch.data.network

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal sealed interface JsonValue
internal class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
    override fun toString(): String = "JsonObject(redacted)"
}
internal class JsonArray(val values: List<JsonValue>) : JsonValue {
    override fun toString(): String = "JsonArray(redacted)"
}
internal class JsonText(val value: String) : JsonValue {
    override fun toString(): String = "JsonText(redacted)"
}
internal class JsonNumber(val value: String) : JsonValue {
    override fun toString(): String = "JsonNumber(redacted)"
}
internal class JsonBoolean(val value: Boolean) : JsonValue
internal data object JsonNull : JsonValue

internal fun JsonValue?.jsonObject(): JsonObject = this as? JsonObject ?: invalidJson()
internal fun JsonValue?.jsonString(): String = (this as? JsonText)?.value ?: invalidJson()
internal fun JsonValue?.jsonLong(): Long = (this as? JsonNumber)?.value?.toLongOrNull() ?: invalidJson()
internal fun invalidJson(): Nothing = throw NetworkException(NetworkIssue.INVALID_RESPONSE)

internal data class JsonBounds(
    val bytes: Int = NetworkLimits.RESPONSE_BYTES,
    val depth: Int = 16,
    val nodes: Int = 40_000,
    val stringChars: Int = 64 * 1024,
    val members: Int = 10_000,
)

/** UTF-8 and JSON are checked before DTO conversion, including duplicate names and lone surrogates. */
internal object StrictJson {
    fun decode(bytes: ByteArray, bounds: JsonBounds = JsonBounds(), checkCancelled: () -> Unit = {}): JsonValue {
        if (bytes.isEmpty() || bytes.size > bounds.bytes) invalidJson()
        checkCancelled()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            invalidJson()
        }
        return Reader(text, bounds, checkCancelled).read()
    }

    fun encode(
        value: JsonValue,
        maxBytes: Int = NetworkLimits.REQUEST_BYTES,
        checkCancelled: () -> Unit = {},
    ): ByteArray {
        val writer = Writer(maxBytes, checkCancelled)
        writer.value(value, 0)
        checkCancelled()
        val bytes = writer.result().toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw NetworkException(NetworkIssue.INVALID_REQUEST)
        return bytes
    }

    private class Reader(private val input: String, private val bounds: JsonBounds, private val checkCancelled: () -> Unit) {
        private var position = 0
        private var nodes = 0

        fun read(): JsonValue {
            val result = value(0)
            whitespace()
            if (position != input.length) invalidJson()
            checkCancelled()
            return result
        }

        private fun value(depth: Int): JsonValue {
            checkCancelled()
            if (depth > bounds.depth || ++nodes > bounds.nodes) invalidJson()
            whitespace()
            return when (peek()) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonText(string())
                't' -> {
                    literal("true")
                    JsonBoolean(true)
                }
                'f' -> {
                    literal("false")
                    JsonBoolean(false)
                }
                'n' -> {
                    literal("null")
                    JsonNull
                }
                '-', in '0'..'9' -> number()
                else -> invalidJson()
            }
        }

        private fun objectValue(depth: Int): JsonObject {
            position++
            val fields = linkedMapOf<String, JsonValue>()
            whitespace()
            if (consume('}')) return JsonObject(fields)
            while (true) {
                if (peek() != '"' || fields.size >= bounds.members) invalidJson()
                val name = string()
                if (fields.containsKey(name)) invalidJson()
                whitespace()
                expect(':')
                fields[name] = value(depth + 1)
                whitespace()
                if (consume('}')) return JsonObject(fields)
                expect(',')
                whitespace()
            }
        }

        private fun arrayValue(depth: Int): JsonArray {
            position++
            val values = mutableListOf<JsonValue>()
            whitespace()
            if (consume(']')) return JsonArray(values)
            while (true) {
                if (values.size >= bounds.members) invalidJson()
                values.add(value(depth + 1))
                whitespace()
                if (consume(']')) return JsonArray(values)
                expect(',')
            }
        }

        private fun string(): String {
            expect('"')
            val output = StringBuilder()
            while (position < input.length) {
                if (position % 1024 == 0) checkCancelled()
                val char = input[position++]
                when {
                    char == '"' -> return output.toString().also { checkSurrogates(it, ::invalidJson) }
                    char == '\\' -> {
                        val escaped = input.getOrNull(position++) ?: invalidJson()
                        output.append(when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> hexChar()
                            else -> invalidJson()
                        })
                    }
                    char.code < 32 -> invalidJson()
                    else -> output.append(char)
                }
                if (output.length > bounds.stringChars) invalidJson()
            }
            invalidJson()
        }

        private fun hexChar(): Char {
            var result = 0
            repeat(4) {
                val char = input.getOrNull(position++) ?: invalidJson()
                if (char !in "0123456789abcdefABCDEF") invalidJson()
                val digit = char.digitToInt(16)
                result = result * 16 + digit
            }
            return result.toChar()
        }

        private fun number(): JsonNumber {
            val start = position
            consume('-')
            if (consume('0')) {
                if (peek() in '0'..'9') invalidJson()
            } else {
                if (peek() !in '1'..'9') invalidJson()
                digits()
            }
            if (consume('.')) {
                if (peek() !in '0'..'9') invalidJson()
                digits()
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                if (peek() !in '0'..'9') invalidJson()
                digits()
            }
            if (position - start > 128) invalidJson()
            return JsonNumber(input.substring(start, position))
        }

        private fun digits() {
            val start = position
            while (peek() in '0'..'9') {
                if (++position - start > 128) invalidJson()
            }
        }
        private fun literal(value: String) {
            if (!input.startsWith(value, position)) invalidJson()
            position += value.length
        }
        private fun whitespace() {
            while (peek() in " \r\n\t") {
                if (++position % 1024 == 0) checkCancelled()
            }
        }
        private fun peek(): Char = input.getOrNull(position) ?: '\u0000'
        private fun consume(char: Char): Boolean = (peek() == char).also { if (it) position++ }
        private fun expect(char: Char) { if (!consume(char)) invalidJson() }
    }

    private class Writer(private val limit: Int, private val checkCancelled: () -> Unit) {
        private val output = StringBuilder()
        private var nodes = 0
        fun result(): String = output.toString()

        fun value(value: JsonValue, depth: Int) {
            checkCancelled()
            if (depth > 16 || ++nodes > 40_000) invalidRequest()
            when (value) {
                is JsonObject -> {
                    if (value.fields.size > 10_000) invalidRequest()
                    append("{")
                    value.fields.entries.sortedBy { it.key }.forEachIndexed { index, (name, item) ->
                        if (index != 0) append(",")
                        string(name)
                        append(":")
                        this.value(item, depth + 1)
                    }
                    append("}")
                }
                is JsonArray -> {
                    if (value.values.size > 10_000) invalidRequest()
                    append("[")
                    value.values.forEachIndexed { index, item ->
                        if (index != 0) append(",")
                        this.value(item, depth + 1)
                    }
                    append("]")
                }
                is JsonText -> string(value.value)
                is JsonNumber -> {
                    if (value.value.length > 128 || !NUMBER.matches(value.value)) invalidRequest()
                    append(value.value)
                }
                is JsonBoolean -> append(if (value.value) "true" else "false")
                JsonNull -> append("null")
            }
        }

        private fun string(value: String) {
            if (value.length > limit) invalidRequest()
            checkSurrogates(value, ::invalidRequest)
            append("\"")
            value.forEachIndexed { index, char ->
                if (index % 1024 == 0) checkCancelled()
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(if (char.code < 32) "\\u" + char.code.toString(16).padStart(4, '0') else char.toString())
                }
            }
            append("\"")
        }

        private fun append(value: String) {
            if (value.length > limit - output.length) invalidRequest()
            output.append(value)
        }
        private fun invalidRequest(): Nothing = throw NetworkException(NetworkIssue.INVALID_REQUEST)
    }

    private fun checkSurrogates(value: String, invalid: () -> Nothing) {
        var position = 0
        while (position < value.length) {
            val char = value[position++]
            if (char.isHighSurrogate()) {
                if (position >= value.length || !value[position++].isLowSurrogate()) invalid()
            } else if (char.isLowSurrogate()) invalid()
        }
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
