package io.github.onlinemo.meloxwatch.data.playlistsexchange

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class JsonPlaylistExchangeTest {
    @Test fun roundTripKeepsEveryPageMetadataIdentityHintAndRepeatedTrackKeyInArrayOrder() {
        runBlocking {
            val members = List(205) { exchangeMember(it.toLong()).copy(artist = "Artist $it", album = "Album",
                contentSha256 = "a".repeat(64)) }.toMutableList()
            members[6] = members[6].copy(trackKey = members[5].trackKey)
            val header = ExchangePlaylistHeader("Café", ExchangePlaylistKind.FOLDER, exchangeTestId(9_999L))
            val source = ExchangeFixtureSource(members, header)
            val output = ExchangeBorrowedOutput()
            val written = JsonPlaylistExchange().write(output, source)
            val input = ExchangeBorrowedInput(output.toByteArray(), chunkSize = 2)
            val actual = mutableListOf<ExchangeMember>()
            val pageSizes = mutableListOf<Int>()
            val parsed = JsonPlaylistExchange(exchangeFreshIds()).parse(input, ExchangePageSink { page ->
                pageSizes.add(page.size)
                actual.addAll(page)
            })
            assertEquals(header, parsed.header)
            assertEquals(205L, parsed.memberCount)
            assertEquals(listOf(100, 100, 5), pageSizes)
            assertEquals(members.map { it.memberId }, actual.map { it.originalMemberId })
            assertEquals(members.map { it.trackKey }, actual.map { it.trackKey })
            assertEquals(members.map { it.relativePath }, actual.map { it.relativePath })
            assertEquals(members.map { it.ordinal }, actual.map { it.ordinal })
            assertEquals(members.map { it.title }, actual.map { it.title })
            assertEquals(members.map { it.artist }, actual.map { it.artist })
            assertEquals(members.map { it.album }, actual.map { it.album })
            assertEquals(members.map { it.durationMs }, actual.map { it.durationMs })
            assertEquals(members.map { it.contentSha256 }, actual.map { it.contentSha256 })
            assertNotEquals(members.first().memberId, actual.first().memberId)
            assertEquals(205, actual.map { it.memberId }.toSet().size)
            assertEquals(written.sha256, parsed.sha256)
            assertEquals(exchangeTestSha256(output.toByteArray()), parsed.sha256)
            assertEquals(written.byteCount, parsed.byteCount)
            assertEquals(listOf(ExchangePageRequest(0L, 100), ExchangePageRequest(100L, 100),
                ExchangePageRequest(200L, 5)), source.requests)
            assertEquals(0, input.closeCount)
            assertEquals(0, output.closeCount)
        }
    }

    @Test fun emptyPlaylistStillUsesTheSchemaAndValidatesItsLeaseWithoutRequestingMembers() {
        runBlocking {
            val source = ExchangeFixtureSource(
                emptyList(),
                ExchangePlaylistHeader("Empty", ExchangePlaylistKind.FAVORITES, exchangeTestId(9_999L)),
            )
            val output = ExchangeBorrowedOutput()
            val written = JsonPlaylistExchange().write(output, source)
            val parsed = JsonPlaylistExchange().parse(ExchangeBorrowedInput(output.toByteArray()), ExchangePageSink {
                throw AssertionError("Empty playlist cannot append a page")
            })
            assertEquals(0L, written.memberCount)
            assertEquals(0L, parsed.memberCount)
            assertEquals(source.header, parsed.header)
            assertTrue(source.validations >= 3)
            assertTrue(source.requests.isEmpty())
        }
    }

    @Test fun fieldOrderIsIndependentAndOptionalNullMetadataDoesNotCreateAccessRights() {
        runBlocking {
            val text = """
                {"members":[{"album":null,"artist":null,"durationMs":null,"trackKey":null,
                 "contentSha256":null,"relativePath":"music/one.flac","unresolved":false,
                 "kind":"audio","memberId":"${exchangeTestId(0L)}"}],
                 "playlist":{"kind":"manual","name":"  Cafe\u0301  "},
                 "exportId":"${exchangeTestId(9_999L)}","schemaVersion":1,"format":"melox-local-playlist"}
            """.trimIndent()
            val members = mutableListOf<ExchangeMember>()
            val result = JsonPlaylistExchange().parse(ExchangeBorrowedInput(exchangeUtf8(text)),
                ExchangePageSink { members.addAll(it) })
            assertEquals("Café", result.header.name)
            assertEquals(1L, result.memberCount)
            assertNull(members.single().trackKey)
            assertNull(members.single().durationMs)
            assertNull(members.single().artist)
            assertEquals("music/one.flac", members.single().relativePath.toString())
        }
    }

    @Test fun utf8BomAndOneByteChunksPreserveTheRawFileHash() {
        runBlocking {
            val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + exchangeUtf8(exchangeJson())
            val input = ExchangeBorrowedInput(bytes, chunkSize = 1)
            val parsed = JsonPlaylistExchange().parse(input, ExchangePageSink {})
            assertEquals(1L, parsed.memberCount)
            assertEquals(bytes.size.toLong(), parsed.byteCount)
            assertEquals(exchangeTestSha256(bytes), parsed.sha256)
            assertEquals(0, input.closeCount)
        }
    }

    @Test fun repeatedOriginalMemberIdsArePreviewHintsAndDoNotCollapseOrderedInstances() {
        runBlocking {
            val text = exchangeJson(exchangeJsonMember() + "," + exchangeJsonMember())
            val members = mutableListOf<ExchangeMember>()
            val parsed = JsonPlaylistExchange(exchangeFreshIds()).parse(ExchangeBorrowedInput(exchangeUtf8(text)),
                ExchangePageSink { members.addAll(it) })
            assertEquals(2L, parsed.memberCount)
            assertEquals(listOf(0L, 1L), members.map { it.ordinal })
            assertEquals(members.first().originalMemberId, members.last().originalMemberId)
            assertNotEquals(members.first().memberId, members.last().memberId)
        }
    }

    @Test fun cueSheetAndLogicalTrackHintsSurviveUnresolvedExportWithoutWholeAudioFallback() {
        runBlocking {
            val cue = ExchangeCueHint(ExchangeRelativePath.parse("album/disc.cue"), 2)
            val known = exchangeMember(0L, "album/image.flac").copy(mediaKind = ExchangeMediaKind.CUE_TRACK, cue = cue)
            val unknown = known.copy(ordinal = 1L, memberId = exchangeTestId(1L), trackKey = null,
                relativePath = null, unresolved = true)
            val output = ExchangeBorrowedOutput()
            JsonPlaylistExchange().write(output, ExchangeFixtureSource(listOf(known, unknown)))
            val members = mutableListOf<ExchangeMember>()
            val result = JsonPlaylistExchange().parse(ExchangeBorrowedInput(output.toByteArray()),
                ExchangePageSink { members.addAll(it) })
            assertEquals(1L, result.blockedReferenceCount)
            assertTrue(members.all { it.mediaKind == ExchangeMediaKind.CUE_TRACK && it.cue == cue })
            assertNull(members.last().trackKey)
            assertNull(members.last().relativePath)
            assertTrue(members.last().unresolved)
        }
    }

    @Test fun rawBlockedReferencesAndTheirIssueFieldsCannotBeExportedOrReactivated() {
        runBlocking {
            val blocked = "content://example.invalid/document/blocked"
            val member = exchangeMember(0L).copy(trackKey = null, relativePath = null, unresolved = true,
                blockedReference = blocked, referenceIssue = ExchangeReferenceIssue.EXTERNAL_REFERENCE)
            val output = ExchangeBorrowedOutput()
            JsonPlaylistExchange().write(output, ExchangeFixtureSource(listOf(member)))
            val text = output.toByteArray().toString(Charsets.UTF_8)
            assertFalse(text.contains(blocked))
            assertFalse(text.contains("blockedReference"))
            assertFalse(text.contains("referenceIssue"))
            val members = mutableListOf<ExchangeMember>()
            val result = JsonPlaylistExchange().parse(ExchangeBorrowedInput(output.toByteArray()),
                ExchangePageSink { members.addAll(it) })
            assertEquals(1L, result.memberCount)
            assertEquals(1L, result.blockedReferenceCount)
            assertNull(members.single().blockedReference)
            assertNull(members.single().relativePath)
            assertTrue(members.single().unresolved)
        }
    }

    @Test fun escapedMetadataAndSupplementarySymbolsRoundTripAsTextWithoutLineInjection() {
        runBlocking {
            val symbol = String(Character.toChars(0x1d11e))
            val title = "quote\" slash\\ line\nnext\t$symbol"
            val source = ExchangeFixtureSource(listOf(exchangeMember(0L, "music/$symbol.flac").copy(title = title)),
                ExchangePlaylistHeader("  Cafe\u0301  ", exportId = exchangeTestId(9_999L)))
            val output = ExchangeBorrowedOutput()
            JsonPlaylistExchange().write(output, source)
            val members = mutableListOf<ExchangeMember>()
            val result = JsonPlaylistExchange().parse(ExchangeBorrowedInput(output.toByteArray(), chunkSize = 1),
                ExchangePageSink { members.addAll(it) })
            assertEquals("Café", result.header.name)
            assertEquals(title, members.single().title)
            assertEquals("music/$symbol.flac", members.single().relativePath.toString())
        }
    }
}
