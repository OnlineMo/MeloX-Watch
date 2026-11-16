package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class JsonPlaylistValidationTest {
    @Test fun androidLenientLexicalDefaultsCannotBypassStrictExchangeParsing() {
        runBlocking {
            val json = exchangeJson()
            val malformed = listOf(
                json.replace("\"schemaVersion\":1,", "\"schemaVersion\":1;"),
                json.replace("\"unresolved\":false", "\"unresolved\":FALSE"),
                json.replace("\"schemaVersion\":1,", "\"schemaVersion\":01,"),
                json.replace("music/one.flac", "music\\qone.flac"),
                json.replace("music/one.flac", "music/one\n.flac"),
                json.replace("\"unresolved\":false}", "\"unresolved\":false},"),
                json.replace("\"format\"", "'format'"),
                "//comment\n$json",
                "$json{}",
            )
            for (text in malformed) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun duplicateFieldsAreRejectedAtEveryObjectLevel() {
        runBlocking {
            val json = exchangeJson()
            val memberId = "\"memberId\":\"${exchangeTestId(0L)}\""
            val duplicates = listOf(
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                json.replace("\"name\":\"Playlist\"", "\"name\":\"Playlist\",\"name\":\"Other\""),
                json.replace(memberId, "$memberId,$memberId"),
                withMemberField("\"trackKey\":{\"providerId\":\"local\",\"trackId\":\"a\",\"trackId\":\"b\"}"),
                withMemberField("\"cue\":{\"relativePath\":\"disc.cue\",\"trackNumber\":1,\"trackNumber\":2}"),
            )
            for (text in duplicates) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun unknownFieldsCannotCarryScriptsCredentialsOrAuthorizationCapabilities() {
        runBlocking {
            val json = exchangeJson()
            val unknown = listOf(
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"script\":\"unused\""),
                json.replace("\"kind\":\"manual\"", "\"kind\":\"manual\",\"uri\":\"unused\""),
                withMemberField("\"token\":\"unused\""),
                withMemberField("\"trackKey\":{\"providerId\":\"local\",\"trackId\":\"a\",\"path\":\"unused\"}"),
                withMemberField("\"cue\":{\"relativePath\":\"disc.cue\",\"trackNumber\":1,\"source\":\"unused\"}"),
            )
            for (text in unknown) {
                expectExchangeIssue(PlaylistExchangeIssue.UNKNOWN_FIELD) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun missingRequiredRootHeaderAndMemberIdentityNeverReturnACompletedExchange() {
        runBlocking {
            val json = exchangeJson()
            val missing = listOf(
                json.replace("\"format\":\"melox-local-playlist\",", ""),
                json.replace("\"exportId\":\"${exchangeTestId(9_999L)}\",", ""),
                json.replace("\"playlist\":{\"name\":\"Playlist\",\"kind\":\"manual\"},", ""),
                json.replace(",\"members\":[${exchangeJsonMember()}]", ""),
                json.replace("\"memberId\":\"${exchangeTestId(0L)}\",", ""),
            )
            for (text in missing) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun formatAndSchemaVersionAreIndependentAndUnsupportedValuesHaveStableCodes() {
        runBlocking {
            expectExchangeIssue(PlaylistExchangeIssue.UNSUPPORTED_FORMAT) {
                val text = exchangeJson().replace("melox-local-playlist", "other")
                JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
            }
            expectExchangeIssue(PlaylistExchangeIssue.UNSUPPORTED_VERSION) {
                val text = exchangeJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2")
                JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
            }
        }
    }

    @Test fun numbersStringsBooleansAndNullsCannotBeCoercedIntoOtherSchemaTypes() {
        runBlocking {
            val json = exchangeJson()
            val invalid = listOf(
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1e0"),
                json.replace("\"unresolved\":false", "\"unresolved\":\"false\""),
                json.replace("\"relativePath\":\"music/one.flac\"", "\"relativePath\":123"),
                withMemberField("\"trackKey\":{\"providerId\":\"local\",\"trackId\":123}"),
                withMemberField("\"durationMs\":-1"),
                withMemberField("\"durationMs\":0"),
                withMemberField("\"durationMs\":9223372036854775808"),
            )
            for (text in invalid) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun unsafePathsHiddenControlsAndMalformedCueHintsNeverResolveAsOrdinaryAudio() {
        runBlocking {
            val json = exchangeJson()
            val invalid = listOf(
                json.replace("music/one.flac", "../outside.flac"),
                json.replace("music/one.flac", "https://example.invalid/one.flac"),
                json.replace("music/one.flac", "music/\\u0085one.flac"),
                json.replace("Playlist", "one\\uDB40\\uDC01two"),
                json.replace("\"kind\":\"audio\"", "\"kind\":\"cue_track\""),
                withMemberField("\"cue\":{\"relativePath\":\"disc.cue\",\"trackNumber\":1}"),
                withMemberField("\"cue\":{\"relativePath\":\"disc.cue\",\"trackNumber\":0}"),
                withMemberField("\"trackKey\":{\"providerId\":\"online\",\"trackId\":\"a\"}"),
                withMemberField("\"title\":\"one\\u0001two\""),
                withMemberField("\"artist\":\"one\\uDB40\\uDC01two\""),
            )
            for (text in invalid) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun invalidSurrogatesUtf8TailsAndUtf16BomsAreEncodingFailuresAndLeaveStreamsBorrowed() {
        runBlocking {
            val bytes = listOf(
                exchangeUtf8(exchangeJson().replace("music/one.flac", "music/\\uD800.flac")),
                exchangeUtf8(exchangeJson()) + byteArrayOf(0xc3.toByte(), 0x28),
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + exchangeJson().toByteArray(Charsets.UTF_16LE),
            )
            for (sample in bytes) {
                val input = ExchangeBorrowedInput(sample)
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) {
                    JsonPlaylistExchange().parse(input, ExchangePageSink {})
                }
                assertEquals(0, input.closeCount)
            }
        }
    }

    @Test fun completeJsonPrefixCannotHideOversizedTrailingWhitespace() {
        runBlocking {
            val prefix = exchangeUtf8(exchangeJson(""))
            fun input(extraBytes: Long): SequenceInputStream = SequenceInputStream(ByteArrayInputStream(prefix),
                ExchangeRepeatedInput(byteArrayOf(0x20), PlaylistExchangeLimits.BYTES - prefix.size + extraBytes))
            val exact = JsonPlaylistExchange().parse(input(0L), ExchangePageSink {})
            assertEquals(0L, exact.memberCount)
            assertEquals(PlaylistExchangeLimits.BYTES, exact.byteCount)
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                JsonPlaylistExchange().parse(input(1L), ExchangePageSink {})
            }
        }
    }

    @Test fun memberInputBudgetUsesBoundedPagesAndRejectsExcessWithoutDroppingInstances() {
        runBlocking {
            val empty = exchangeJson("")
            val arrayOffset = empty.indexOf("[]")
            val prefix = exchangeUtf8(empty.substring(0, arrayOffset + 1))
            val suffix = empty.substring(arrayOffset + 1)
            val member = """{"memberId":"${exchangeTestId(0L)}","unresolved":true}"""
            fun input(count: Long): SequenceInputStream {
                val streams = listOf<InputStream>(
                    ByteArrayInputStream(prefix),
                    ExchangeRepeatedInput(exchangeUtf8("$member,"), count - 1L),
                    ByteArrayInputStream(exchangeUtf8(member + suffix)),
                )
                return SequenceInputStream(Collections.enumeration(streams))
            }
            var staged = 0L
            val sink = ExchangePageSink { page ->
                assertEquals(staged, page.first().ordinal)
                assertEquals(staged + page.size - 1L, page.last().ordinal)
                assertEquals(100, page.size)
                staged += page.size
            }
            val result = JsonPlaylistExchange(exchangeFreshIds()).parse(input(PlaylistExchangeLimits.MEMBERS), sink)
            assertEquals(PlaylistExchangeLimits.MEMBERS, result.memberCount)
            assertEquals(PlaylistExchangeLimits.MEMBERS, staged)
            staged = 0L
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                JsonPlaylistExchange(exchangeFreshIds()).parse(input(PlaylistExchangeLimits.MEMBERS + 1L), sink)
            }
            assertEquals(PlaylistExchangeLimits.MEMBERS, staged)
        }
    }

    @Test fun decodedStringDepthAndScalarBudgetsRejectExpansionBeforeSchemaAllocation() {
        runBlocking {
            val oversized = listOf(
                withMemberField("\"title\":\"" + "a".repeat(PlaylistExchangeLimits.FIELD_CHARACTERS + 1) + "\""),
                withMemberField("\"title\":\"" + "\\u0061".repeat(PlaylistExchangeLimits.FIELD_CHARACTERS + 1) + "\""),
                exchangeJson().replace("\"schemaVersion\":1", "\"schemaVersion\":" + "1".repeat(65)),
                exchangeJson("[".repeat(17) + "0" + "]".repeat(17)),
            )
            for (text in oversized) {
                expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                    JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun truncatedOrLateIncompatibleMetadataNeverTurnsPrivatePagesIntoACompletedImport() {
        runBlocking {
            val input = ExchangeBorrowedInput(exchangeUtf8(exchangeJson().dropLast(1)))
            expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                JsonPlaylistExchange().parse(input, ExchangePageSink {})
            }
            assertEquals(0, input.closeCount)
            var staged = 0L
            val members = List(101) { exchangeJsonMember() }.joinToString(",")
            val late = """
                {"members":[$members],"playlist":{"name":"Playlist","kind":"manual"},
                 "exportId":"${exchangeTestId(9_999L)}","format":"melox-local-playlist","schemaVersion":2}
            """.trimIndent()
            val error = expectExchangeIssue(PlaylistExchangeIssue.UNSUPPORTED_VERSION) {
                JsonPlaylistExchange(exchangeFreshIds()).parse(ExchangeBorrowedInput(exchangeUtf8(late)),
                    ExchangePageSink { staged += it.size })
            }
            // 这些页仅是私有generation；集成所有者必须丢弃，不能按已暂存数量提交旧列表。
            assertEquals(101L, staged)
            assertEquals("UNSUPPORTED_VERSION", error.message)
            assertFalse(error.stackTraceToString().contains(late))
        }
    }

    private fun withMemberField(field: String): String = exchangeJson(
        exchangeJsonMember().replace("\"unresolved\":false", "\"unresolved\":false,$field"),
    )
}
