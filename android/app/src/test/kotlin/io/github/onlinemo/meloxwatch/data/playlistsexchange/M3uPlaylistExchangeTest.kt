package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistExchangeTest {
    @Test fun roundTripKeepsAllThreePagesOrderDuplicateKeysAndExactMillisecondDurations() {
        runBlocking {
            val members = List(205) { exchangeMember(it.toLong()) }.toMutableList()
            members[6] = members[6].copy(trackKey = members[5].trackKey)
            members[10] = members[10].copy(relativePath = ExchangeRelativePath.parse("#music.flac"))
            val header = ExchangePlaylistHeader("Café", ExchangePlaylistKind.FOLDER, exchangeTestId(9_999L))
            val source = ExchangeFixtureSource(members, header)
            val output = ExchangeBorrowedOutput()
            val written = M3uPlaylistExchange().write(output, source)
            val bytes = output.toByteArray()
            val input = ExchangeBorrowedInput(bytes, chunkSize = 3)
            val parsedMembers = mutableListOf<ExchangeMember>()
            val pages = mutableListOf<Int>()
            val parsed = M3uPlaylistExchange(exchangeFreshIds()).parse(input, ExchangePageSink { page ->
                pages.add(page.size)
                parsedMembers.addAll(page)
            })
            assertEquals(header, parsed.header)
            assertEquals(205L, written.memberCount)
            assertEquals(205L, parsed.memberCount)
            assertEquals(listOf(100, 100, 5), pages)
            assertEquals(members.map { it.ordinal }, parsedMembers.map { it.ordinal })
            assertEquals(members.map { it.trackKey }, parsedMembers.map { it.trackKey })
            assertEquals(members.map { it.relativePath }, parsedMembers.map { it.relativePath })
            assertEquals(members.map { it.title }, parsedMembers.map { it.title })
            assertEquals(members.map { it.durationMs }, parsedMembers.map { it.durationMs })
            assertEquals(listOf(ExchangePageRequest(0L, 100), ExchangePageRequest(100L, 100),
                ExchangePageRequest(200L, 5)), source.requests)
            assertEquals(bytes.size.toLong(), written.byteCount)
            assertEquals(written.sha256, parsed.sha256)
            assertEquals(exchangeTestSha256(bytes), parsed.sha256)
            assertNotEquals(members.first().memberId, parsedMembers.first().memberId)
            assertEquals(0, input.closeCount)
            assertEquals(0, output.closeCount)
        }
    }

    @Test fun utf8BomPlainM3uAndMixedLineEndingsKeepTheEntireRawHash() {
        runBlocking {
            val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                exchangeUtf8("Cafe\u0301/one.flac\r\ntwo.flac\rlast.flac")
            val input = ExchangeBorrowedInput(bytes, chunkSize = 1)
            val members = mutableListOf<ExchangeMember>()
            val parsed = M3uPlaylistExchange().parse(input, ExchangePageSink { members.addAll(it) })
            assertNull(parsed.header.name)
            assertEquals(3L, parsed.memberCount)
            assertEquals(listOf("Café/one.flac", "two.flac", "last.flac"), members.map { it.relativePath.toString() })
            assertEquals(bytes.size.toLong(), parsed.byteCount)
            assertEquals(exchangeTestSha256(bytes), parsed.sha256)
            assertEquals(0, input.closeCount)
        }
    }

    @Test fun gb18030RequiresExplicitM3uRetryAndCannotBeUsedForM3u8() {
        runBlocking {
            val bytes = "#PLAYLIST:中文歌单\n音乐/歌曲.flac\n".toByteArray(Charset.forName("GB18030"))
            val sink = ExchangePageSink {}
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) {
                M3uPlaylistExchange().parse(ExchangeBorrowedInput(bytes), sink, kind = M3uFileKind.M3U)
            }
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) {
                M3uPlaylistExchange().parse(
                    ExchangeBorrowedInput(bytes), sink, encoding = PlaylistExchangeEncoding.GB18030,
                )
            }
            val members = mutableListOf<ExchangeMember>()
            val result = M3uPlaylistExchange().parse(
                ExchangeBorrowedInput(bytes),
                ExchangePageSink { members.addAll(it) },
                kind = M3uFileKind.M3U,
                encoding = PlaylistExchangeEncoding.GB18030,
            )
            assertEquals("中文歌单", result.header.name)
            assertEquals("音乐/歌曲.flac", members.single().relativePath.toString())
            assertEquals(exchangeTestSha256(bytes), result.sha256)
        }
    }

    @Test fun externalAndUnsafeReferencesRemainOrderedPlaceholdersAndAreNotEchoedOnExport() {
        runBlocking {
            val blocked = listOf("https://example.invalid/remote.mp3", "content://example.invalid/document/item",
                "/storage/example.flac", "C:\\music\\one.flac", "\\\\server\\share\\one.flac", "../escape.flac",
                "music/\u202eevil.flac")
            val text = (blocked + "safe.flac").joinToString("\n")
            val members = mutableListOf<ExchangeMember>()
            val result = M3uPlaylistExchange(exchangeFreshIds()).parse(ExchangeBorrowedInput(exchangeUtf8(text)),
                ExchangePageSink { members.addAll(it) })
            assertEquals(8L, result.memberCount)
            assertEquals(7L, result.blockedReferenceCount)
            assertEquals(blocked, members.take(7).map { it.blockedReference })
            assertTrue(members.take(7).all { it.unresolved && it.relativePath == null })
            assertEquals("safe.flac", members.last().relativePath.toString())
            val output = ExchangeBorrowedOutput()
            M3uPlaylistExchange().write(output, ExchangeFixtureSource(members))
            val exported = output.toByteArray().toString(Charsets.UTF_8)
            for (reference in blocked) assertFalse(exported.contains(reference))
            val roundTripMembers = mutableListOf<ExchangeMember>()
            val roundTrip = M3uPlaylistExchange(exchangeFreshIds(2_000_000L)).parse(
                ExchangeBorrowedInput(output.toByteArray()), ExchangePageSink { roundTripMembers.addAll(it) })
            assertEquals(8L, roundTrip.memberCount)
            assertEquals(7L, roundTrip.blockedReferenceCount)
            assertEquals(members.take(7).map { it.memberId }, roundTripMembers.take(7).map { it.originalMemberId })
            assertTrue(roundTripMembers.take(7).all { it.referenceIssue == ExchangeReferenceIssue.UNRESOLVED_MARKER })
        }
    }

    @Test fun unresolvedMarkersHaveCanonicalIdsAndNeverBecomeUriAccessHints() {
        runBlocking {
            val members = mutableListOf<ExchangeMember>()
            val originalId = exchangeTestId(41L)
            M3uPlaylistExchange(exchangeFreshIds()).parse(
                ExchangeBorrowedInput(exchangeUtf8("melox-unresolved:$originalId")),
                ExchangePageSink { members.addAll(it) },
            )
            assertEquals(originalId, members.single().originalMemberId)
            assertTrue(members.single().unresolved)
            assertNull(members.single().trackKey)
            assertNull(members.single().relativePath)
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                M3uPlaylistExchange().parse(
                    ExchangeBorrowedInput(exchangeUtf8("melox-unresolved:../other")), ExchangePageSink {},
                )
            }
        }
    }

    @Test fun cueHintsRetainTheSheetPathTrackNumberAndLogicalKindEvenWhenUnresolved() {
        runBlocking {
            val hint = ExchangeCueHint(ExchangeRelativePath.parse("album/disc.cue"), 2)
            val cueMember = exchangeMember(0L, "album/image.flac").copy(
                mediaKind = ExchangeMediaKind.CUE_TRACK, cue = hint,
            )
            val missingCue = cueMember.copy(ordinal = 1L, memberId = exchangeTestId(1L), trackKey = null,
                relativePath = null, unresolved = true)
            val output = ExchangeBorrowedOutput()
            M3uPlaylistExchange().write(output, ExchangeFixtureSource(listOf(cueMember, missingCue)))
            val members = mutableListOf<ExchangeMember>()
            M3uPlaylistExchange(exchangeFreshIds()).parse(ExchangeBorrowedInput(output.toByteArray()),
                ExchangePageSink { members.addAll(it) })
            assertEquals(listOf(ExchangeMediaKind.CUE_TRACK, ExchangeMediaKind.CUE_TRACK), members.map { it.mediaKind })
            assertEquals(listOf(hint, hint), members.map { it.cue })
            assertEquals("album/image.flac", members.first().relativePath.toString())
            assertNull(members.last().relativePath)
            assertNull(members.last().trackKey)
            assertTrue(members.last().unresolved)
        }
    }

    @Test fun duplicateOrDanglingMetadataAndStreamingExtensionsHaveExplicitFailures() {
        runBlocking {
            val malformed = listOf("#EXTM3U\n#EXTM3U\na.flac", "#PLAYLIST:a\n#PLAYLIST:b\na.flac",
                "a.flac\n#PLAYLIST:late", "#EXTINF:1,first\n#EXTINF:2,second\na.flac",
                "#MELOX-TRACK-ID:first\n#MELOX-TRACK-ID:second\na.flac", "#EXTINF:-1,dangling")
            for (text in malformed) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    M3uPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
            for (text in listOf("#EXT-X-VERSION:3\na.flac", "#MELOX-UNKNOWN:1\na.flac")) {
                expectExchangeIssue(PlaylistExchangeIssue.UNSUPPORTED_FORMAT) {
                    M3uPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun decimalDurationsUseExactIntegerMathAndRejectOverflowOrAmbiguousNumbers() {
        runBlocking {
            val durations = listOf("1.001", "0", "-1", "+1.123", "9223372036854775.807")
            val text = durations.mapIndexed { index, duration ->
                "#EXTINF:$duration,title\n$index.flac"
            }.joinToString("\n")
            val members = mutableListOf<ExchangeMember>()
            M3uPlaylistExchange().parse(
                ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink { members.addAll(it) },
            )
            assertEquals(listOf(1_001L, null, null, 1_123L, Long.MAX_VALUE), members.map { it.durationMs })
            for (value in listOf("-2", "1.0000", "NaN", "9223372036854775.808", "1e3", "+")) {
                expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                    M3uPlaylistExchange().parse(
                        ExchangeBorrowedInput(exchangeUtf8("#EXTINF:$value,title\na.flac")), ExchangePageSink {},
                    )
                }
            }
        }
    }

    @Test fun oversizedLinesFieldsAndPathsRejectTheFileRatherThanSilentlyTruncatingMembers() {
        runBlocking {
            for (text in listOf("#" + "a".repeat(PlaylistExchangeLimits.LINE_CHARACTERS),
                "#EXTINF:-1," + "a".repeat(PlaylistExchangeLimits.FIELD_CHARACTERS + 1) + "\na.flac",
                "a".repeat(PlaylistExchangeLimits.FIELD_CHARACTERS + 1))) {
                expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                    M3uPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)), ExchangePageSink {})
                }
            }
        }
    }

    @Test fun hundredThousandMembersIsOneInputBudgetWithBoundedPagesAndNoEarlySuccessOnExcess() {
        runBlocking {
            var staged = 0L
            var largestPage = 0
            val sink = ExchangePageSink { page ->
                assertEquals(staged, page.first().ordinal)
                assertEquals(staged + page.size - 1L, page.last().ordinal)
                staged += page.size
                largestPage = maxOf(largestPage, page.size)
            }
            val input = ExchangeRepeatedInput(exchangeUtf8("a\n"), PlaylistExchangeLimits.MEMBERS)
            val result = M3uPlaylistExchange(exchangeFreshIds()).parse(input, sink)
            assertEquals(PlaylistExchangeLimits.MEMBERS, result.memberCount)
            assertEquals(PlaylistExchangeLimits.MEMBERS, staged)
            assertEquals(100, largestPage)
            assertEquals(0, input.closeCount)
            staged = 0L
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                M3uPlaylistExchange(exchangeFreshIds()).parse(
                    ExchangeRepeatedInput(exchangeUtf8("a\n"), PlaylistExchangeLimits.MEMBERS + 1L), sink)
            }
            assertEquals(PlaylistExchangeLimits.MEMBERS, staged)
        }
    }

    @Test fun exportRejectsMetadataThatWouldInjectAnotherM3uLine() {
        runBlocking {
            val member = exchangeMember(0L).copy(title = "title\n#PLAYLIST:injected")
            val output = ExchangeBorrowedOutput()
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) {
                M3uPlaylistExchange().write(output, ExchangeFixtureSource(listOf(member)))
            }
            assertEquals(0, output.closeCount)
        }
    }

    @Test fun emptyPlaylistStillExportsItsMetadataAndValidatesTheSourceLease() {
        runBlocking {
            val source = ExchangeFixtureSource(
                emptyList(),
                ExchangePlaylistHeader("Empty", ExchangePlaylistKind.FAVORITES, exchangeTestId(9_999L)),
            )
            val output = ExchangeBorrowedOutput()
            val written = M3uPlaylistExchange().write(output, source)
            val parsed = M3uPlaylistExchange().parse(ExchangeBorrowedInput(output.toByteArray()), ExchangePageSink {
                throw AssertionError("Empty playlist cannot append a page")
            })
            assertEquals(0L, written.memberCount)
            assertEquals(0L, parsed.memberCount)
            assertEquals(source.header, parsed.header)
            assertTrue(source.validations >= 3)
            assertTrue(source.requests.isEmpty())
        }
    }
}
