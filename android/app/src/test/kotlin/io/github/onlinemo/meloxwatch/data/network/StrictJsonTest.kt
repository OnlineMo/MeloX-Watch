package io.github.onlinemo.meloxwatch.data.network

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonTest {
    @Test fun canonicalOutputSortsEveryObjectAndRetainsUnicodeAndEscapes() {
        val value = JsonObject(linkedMapOf("z" to JsonText("音\n\""), "a" to JsonObject(mapOf("b" to JsonBoolean(true), "a" to JsonNull))))
        assertEquals("{\"a\":{\"a\":null,\"b\":true},\"z\":\"音\\n\\\"\"}", StrictJson.encode(value).toString(Charsets.UTF_8))
    }

    @Test fun duplicateKeysCannotOverrideTheAuthenticatedProfileOrServerCode() {
        for (input in listOf("{\"code\":200,\"code\":403}", "{\"profile\":{\"userId\":1,\"userId\":2}}", "{\"a\":1,\"\\u0061\":2}")) {
            invalid(input)
        }
    }

    @Test fun malformedUtf8NeverBecomesReplacementCharacters() {
        val bytes = byteArrayOf(0x22, 0xc3.toByte(), 0x28, 0x22)
        assertEquals(NetworkIssue.INVALID_RESPONSE, assertThrows(NetworkException::class.java) { StrictJson.decode(bytes) }.issue)
    }

    @Test fun rejectsLoneSurrogatesControlCharactersAndNonAsciiHexEscapes() {
        listOf("\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800x\"", "\"\\u１２３４\"", "\"a\n\"").forEach(::invalid)
        assertEquals("😀", StrictJson.decode("\"\\uD83D\\uDE00\"".toByteArray()).jsonString())
    }

    @Test fun rejectsTrailingDataTrailingCommasAndNonJsonNumbers() {
        listOf("{}{}", "[1,]", "{\"x\":1,}", "01", "+1", "1.", "1e", "NaN", "Infinity", "-", "true false").forEach(::invalid)
    }

    @Test fun objectMembersArrayMembersDepthAndNodesHaveSeparateBounds() {
        val bounds = JsonBounds(depth = 1, nodes = 3, members = 2)
        listOf("[[[0]]]", "[0,1,2]", "{\"a\":0,\"b\":1,\"c\":2}").forEach { invalid(it, bounds) }
        invalid("[[],[]]", bounds.copy(nodes = 2))
        assertEquals(2, (StrictJson.decode("[0,1]".toByteArray(), bounds) as JsonArray).values.size)
    }

    @Test fun enforcesActualUtf8ByteLimitAndTheStringCharacterLimit() {
        invalid("\"abcd\"", JsonBounds(stringChars = 3))
        invalid("\"音\"", JsonBounds(bytes = 4))
        assertEquals(NetworkIssue.INVALID_REQUEST, assertThrows(NetworkException::class.java) {
            StrictJson.encode(JsonText("音"), maxBytes = 4)
        }.issue)
    }

    @Test fun writerRejectsLoneSurrogatesAndMalformedNumberTokens() {
        for (value in listOf(JsonText("\uD800"), JsonNumber("01"), JsonNumber("1e"))) {
            assertEquals(NetworkIssue.INVALID_REQUEST, assertThrows(NetworkException::class.java) { StrictJson.encode(value) }.issue)
        }
    }

    @Test fun cancellationPropagatesFromParsingAndEncodingWithoutErrorRemapping() {
        val cancel = CancellationException("fixture cancellation")
        assertEquals(cancel, assertThrows(CancellationException::class.java) { StrictJson.decode("{}".toByteArray()) { throw cancel } })
        assertEquals(cancel, assertThrows(CancellationException::class.java) { StrictJson.encode(JsonObject(emptyMap())) { throw cancel } })
    }

    @Test fun credentialBearingJsonValuesHaveRedactedDiagnosticStrings() {
        listOf(JsonText("fixture-cookie"), JsonNumber("123456"), JsonObject(mapOf("cookie" to JsonText("fixture-cookie"))),
            JsonArray(listOf(JsonText("fixture-cookie")))).forEach { assertTrue(it.toString().contains("redacted")) }
    }

    private fun invalid(input: String, bounds: JsonBounds = JsonBounds()) {
        assertEquals(NetworkIssue.INVALID_RESPONSE, assertThrows(NetworkException::class.java) { StrictJson.decode(input.toByteArray(Charsets.UTF_8), bounds) }.issue)
    }
}
