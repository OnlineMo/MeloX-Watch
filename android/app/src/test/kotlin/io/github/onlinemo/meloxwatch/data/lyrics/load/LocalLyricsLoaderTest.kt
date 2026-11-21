package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsBinaryFixture as Binary
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsLoaderTest {
    @Test fun manualLrcPreservesRealTimesOffsetsContentAndCallerBinding() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val text = "[offset:250]\n[00:02][00:01]line"
        val input = fixture.document(text)
        val request = fixture.request(LyricsLoadSelection.TextDocument(input), input.documentId)
        val result = loaded(fixture.loader().load(request))
        assertSame(request.binding, result.binding)
        assertEquals(LocalContentValidation.VERIFIED, result.audio.contentValidation)
        assertEquals(LyricsSourceOrigin.EXPLICIT_DOCUMENT, result.source.origin)
        assertEquals(input.sourceGeneration, result.source.sourceGeneration)
        assertEquals(Binary.sha256(text.toByteArray()), result.source.contentSha256)
        assertEquals(listOf(1_000L, 2_000L), (0 until result.document.timeline.size).map { result.document.timeline.line(it).startsAtMs })
        assertEquals(250L, result.document.sourceOffsetMs)
        assertEquals(2, fixture.openedUris.size)
        assertEquals(2, fixture.closedStreams.get())
        assertEquals(2, fixture.validationCalls.get())
        assertTrue(result.accountedBytes in 1L..8_388_608L)
        try {
            (result.document.sourceLines as MutableList<*>).clear()
            throw AssertionError("Expected an immutable document")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun savedAssociationWinsWithoutReadingSiblingOrEmbeddedSources() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val associated = fixture.document("selected", nativeToken = "native-text-v1")
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("automatic"))
        val request = fixture.request(LyricsLoadSelection.Automatic(associated, LyricsSiblingScope("folder", "Track.mp3")))
        val result = loaded(fixture.loader().load(request))
        assertEquals("selected", result.document.sourceLines.single().text)
        assertEquals(listOf(associated.candidate.uri), fixture.openedUris.toList())
        assertEquals(0, fixture.cursorOpens.get())
    }

    @Test fun badAssociatedEncodingRemainsAnErrorAndDoesNotEraseOrFallBack() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("ok", nativeToken = "native-text-v1")
        fixture.streams[input.candidate.uri] = { ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)) }
        val result = fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(input, LyricsSiblingScope("folder", "Track.mp3"))))
        assertEquals(LyricsParseIssue.INVALID_ENCODING, failure(result).parseIssue)
        assertEquals(listOf(input.candidate.uri), fixture.openedUris.toList())
        assertEquals(input.candidate, fixture.documents[input.candidate.uri])
        assertEquals(0, fixture.cursorOpens.get())
    }

    @Test fun uniqueSiblingWinsOverEmbeddedAndItsCursorIsClosedBeforeTextRead() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val sibling = fixture.sibling("[00:01]sibling")
        fixture.directory = listOf(fixture.audioRow, sibling)
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSourceOrigin.SAME_NAME_LRC, result.source.origin)
        assertEquals(LyricsSiblingStatus.MATCHED, result.siblingStatus)
        assertEquals(listOf(sibling.uri, sibling.uri), fixture.openedUris.toList())
        assertEquals(1, fixture.cursorCloses.get())
    }

    @Test fun embeddedSynchronizationWinsOverEarlierPlainTextWithoutInventingTiming() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.setAudio(Binary.id3(
            Binary.frame("USLT", Binary.uslt("plain", encoding = 3), version = 4),
            Binary.frame("SYLT", Binary.sylt(listOf("timed" to 1_500L)), version = 4), version = 4,
        ))
        val result = loaded(fixture.loader().load(fixture.request()))
        assertEquals(LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED, result.source.origin)
        assertEquals(LyricsKind.SYNCHRONIZED, result.document.kind)
        assertEquals(1_500L, result.document.timeline.line(0).startsAtMs)
        assertEquals(2, result.embeddedCandidates.size)
        assertEquals(listOf(fixture.audio.location.uri), fixture.openedUris.toList())
        assertEquals(1, fixture.closedStreams.get())
    }

    @Test fun explicitEmbeddedCandidateCanChooseThePlainSourceByExactIdentity() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.setAudio(Binary.id3(
            Binary.frame("USLT", Binary.uslt("plain", encoding = 3), version = 4),
            Binary.frame("SYLT", Binary.sylt(listOf("timed" to 1_000L)), version = 4), version = 4,
        ))
        val loader = fixture.loader()
        val automatic = loaded(loader.load(fixture.request()))
        val plainId = automatic.embeddedCandidates.first().source.candidateId
        val result = loaded(loader.load(fixture.request(LyricsLoadSelection.Embedded(plainId), plainId)))
        assertEquals(LyricsKind.PLAIN_TEXT, result.document.kind)
        assertEquals(plainId, result.source.documentId)
        assertFalse(result.document.hasWordTimings)
        assertEquals(2, fixture.closedStreams.get())
    }

    @Test fun incompleteEmbeddedMetadataNeverPublishesItsPrivateCandidates() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.setAudio(Binary.id3(Binary.frame("USLT", Binary.uslt("private")), declaredTagSize = 100_000))
        val result = fixture.loader().load(fixture.request()) as LocalLyricsLoadResult.Unavailable
        assertEquals(LyricsLoadIssue.EMBEDDED_FAILED, result.failure.issue)
        assertEquals(EmbeddedLyricsIssue.TRUNCATED_METADATA, result.embeddedDiagnostics.last().issue)
        assertEquals(1, fixture.closedStreams.get())
    }

    @Test fun aCompleteContainerWithoutLyricsReportsAbsence() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.setAudio(Binary.flac())
        assertEquals(LyricsLoadIssue.NO_LYRICS, failure(fixture.loader().load(fixture.request())).issue)
        assertEquals(1, fixture.closedStreams.get())
    }

    @Test fun emptyAndPlainTextRemainDistinctSuccessfulTextStates() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val loader = fixture.loader()
        val empty = fixture.document("")
        val plain = fixture.document("untimed")
        assertEquals(LyricsKind.EMPTY, loaded(loader.load(fixture.request(LyricsLoadSelection.TextDocument(empty)))).document.kind)
        assertEquals(LyricsKind.PLAIN_TEXT, loaded(loader.load(fixture.request(LyricsLoadSelection.TextDocument(plain)))).document.kind)
        assertEquals(4, fixture.closedStreams.get())
    }

    @Test fun staleCallerGenerationIsRejectedBeforeAnyProviderOperation() = runBlocking {
        val fixture = LocalLyricsLoadFixture().apply { current = false }
        assertEquals(LyricsLoadIssue.STALE_REQUEST, failure(fixture.loader().load(fixture.request())).issue)
        assertEquals(0, fixture.validationCalls.get())
        assertTrue(fixture.openedUris.isEmpty())
    }

    @Test fun callerSourceChangeDuringReadRejectsTheLateDocument() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("[00:01]old")
        fixture.streams[input.candidate.uri] = { object : ByteArrayInputStream("[00:01]old".toByteArray()) {
            override fun close() {
                fixture.current = false
                super.close()
            }
        } }
        assertEquals(LyricsLoadIssue.STALE_REQUEST, failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input)))).issue)
        assertEquals(1, fixture.openedUris.size)
    }

    @Test fun authorizationEpochChangeDuringAcquisitionRejectsTheDocument() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("text")
        fixture.validationHook = { fixture.audio = fixture.audio.copy(source = fixture.audio.source.copy(authEpoch = 7L)) }
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input)))).issue)
    }

    @Test fun audioObjectChangeWithoutALogicalTrackChangeRejectsTheDocument() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("text")
        fixture.validationHook = { fixture.audio = fixture.audio.copy(objectId = "replacement-object") }
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input)))).issue)
    }

    @Test fun documentIdentityFenceRejectsADifferentSelectedInput() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("text")
        assertEquals(LyricsLoadIssue.DOCUMENT_MISMATCH,
            failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input), "previous-picker"))).issue)
        assertTrue(fixture.openedUris.isEmpty())
    }

    @Test fun savedFullShaBaselineRejectsChangedSelectedBytes() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("changed").copy(expectedContentSha256 = Binary.sha256("original".toByteArray()))
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input)))).issue)
        assertEquals(1, fixture.closedStreams.get())
    }

    @Test fun weakTextRequiresTwoEqualCompleteHashesEvenWithUnchangedMetadata() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("[00:01]a")
        val reads = AtomicInteger()
        fixture.streams[input.candidate.uri] = { ByteArrayInputStream(if (reads.incrementAndGet() == 1) "[00:01]a".toByteArray() else "[00:01]b".toByteArray()) }
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input)))).issue)
        assertEquals(2, fixture.closedStreams.get())
    }

    @Test fun revokedPersistedTextGrantKeepsTheSelectedAssociationInput() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val input = fixture.document("text").copy(requirePersistedRead = true)
        val original = fixture.observations.getValue(input.source.sourceId)
        fixture.observations[input.source.sourceId] = original.copy(access = original.access.copy(persistedRead = ObservedCapability.AVAILABLE))
        fixture.verifyHook = { fixture.observations[input.source.sourceId] = original }
        assertEquals(LyricsLoadIssue.PERMISSION_REVOKED, failure(fixture.loader().load(fixture.request(LyricsLoadSelection.TextDocument(input)))).issue)
        assertEquals(input.candidate, fixture.documents[input.candidate.uri])
    }

    @Test fun unknownAudioContentIsNotTurnedIntoLyricSupport() = runBlocking {
        val fixture = LocalLyricsLoadFixture().apply { audioIssue = SourceReadIssue.CONTENT_UNKNOWN }
        assertEquals(LyricsLoadIssue.CONTENT_UNKNOWN, failure(fixture.loader().load(fixture.request())).issue)
        assertTrue(fixture.openedUris.isEmpty())
    }
}
