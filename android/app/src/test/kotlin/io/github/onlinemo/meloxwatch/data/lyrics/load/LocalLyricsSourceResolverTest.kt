package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsSourceResolverTest {
    @Test fun caseAndNfcEquivalentNamesAreAmbiguousAcrossDifferentObjects() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow.copy(displayName = "Écho.mp3"),
            fixture.sibling("first", "Écho.lrc", "first"), fixture.sibling("second", "E\u0301cho.LRC", "second"))
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Écho.mp3")))))
        assertEquals(LyricsSiblingStatus.AMBIGUOUS, result.siblingStatus)
        assertEquals(LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT, result.source.origin)
        assertEquals(listOf(fixture.audio.location.uri), fixture.openedUris.toList())
        assertEquals(1, fixture.cursorCloses.get())
    }

    @Test fun repeatedIdenticalProviderRowsDoNotInventAnotherSource() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val sibling = fixture.sibling("unique")
        fixture.directory = listOf(fixture.audioRow, sibling, sibling)
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.MATCHED, result.siblingStatus)
        assertEquals(sibling.objectId, result.source.objectId)
    }

    @Test fun excessRowMarksTruncationAndNeverSelectsAnApparentlyUniqueEarlyMatch() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val sibling = fixture.sibling("early")
        fixture.directory = listOf(fixture.audioRow, sibling, sibling.copy(objectId = "other", uri = "content://lyrics.fixture/other", displayName = "other.txt"),
            sibling.copy(objectId = "late", uri = "content://lyrics.fixture/late", displayName = "TRACK.LRC"))
        val result = loaded(fixture.loader(LocalLyricsLoadLimits(maxSiblingEntries = 3)).load(
            fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.DIRECTORY_TRUNCATED, result.siblingStatus)
        assertEquals(listOf(3, 1), fixture.batchLimits.toList())
        assertEquals(listOf(fixture.audio.location.uri), fixture.openedUris.toList())
    }

    @Test fun exactCeilingCanMatchOnlyAfterTheExtraEofProbeCompletes() = runBlocking {
        val fixture = LocalLyricsLoadFixture().apply { eofOnlyOnEmptyBatch = true }
        val sibling = fixture.sibling("unique")
        fixture.directory = listOf(fixture.audioRow, sibling)
        val result = loaded(fixture.loader(LocalLyricsLoadLimits(maxSiblingEntries = 2)).load(
            fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.MATCHED, result.siblingStatus)
        assertEquals(listOf(2, 1), fixture.batchLimits.toList())
        assertEquals(1, fixture.cursorCloses.get())
    }

    @Test fun aDirectoryWithoutTheExactAudioObjectCannotSupplyItsLyrics() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow.copy(objectId = "different-audio"), fixture.sibling("wrong folder"))
        val result = fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3"))))
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(result).issue)
        assertEquals(LyricsSiblingStatus.AUDIO_SCOPE_MISMATCH, result.siblingStatus)
        assertTrue(fixture.openedUris.isEmpty())
    }

    @Test fun suppliedAudioFilenameMustMatchTheRealExactObjectRow() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val result = fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Other.mp3"))))
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(result).issue)
        assertTrue(fixture.openedUris.isEmpty())
    }

    @Test fun absentEnumerationGrantUsesEmbeddedWithoutEnumerating() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        val observed = fixture.observations.getValue(fixture.source.sourceId)
        fixture.observations[fixture.source.sourceId] = observed.copy(access = observed.access.copy(enumerate = ObservedCapability.UNAVAILABLE))
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.SCOPE_UNAVAILABLE, result.siblingStatus)
        assertEquals(0, fixture.cursorOpens.get())
        assertEquals(LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT, result.source.origin)
    }

    @Test fun emptyNonExhaustedBatchIsIncompleteWithoutARepetitionLoop() = runBlocking {
        val fixture = LocalLyricsLoadFixture().apply { emptyNonExhaustedBatch = true }
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.INCOMPLETE_PROVIDER, result.siblingStatus)
        assertEquals(1, fixture.batchLimits.size)
        assertEquals(1, fixture.cursorCloses.get())
    }

    @Test fun oversizedProviderBatchIsIncomplete() = runBlocking {
        val fixture = LocalLyricsLoadFixture().apply { tooManyBatchRows = true }
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("not safely unique"))
        val result = loaded(fixture.loader(LocalLyricsLoadLimits(maxSiblingEntries = 1)).load(
            fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.INCOMPLETE_PROVIDER, result.siblingStatus)
        assertEquals(1, fixture.cursorCloses.get())
    }

    @Test fun unprovenParentRowsCannotEstablishUniqueness() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("unproven").copy(parentObjectId = "another-folder"))
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.INCOMPLETE_PROVIDER, result.siblingStatus)
        assertEquals(listOf(fixture.audio.location.uri), fixture.openedUris.toList())
    }

    @Test fun truncatedDisplayNameCannotProveACompleteDirectoryMatch() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("unknown").copy(displayName = "x".repeat(512)))
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.INCOMPLETE_PROVIDER, result.siblingStatus)
        assertEquals(listOf(fixture.audio.location.uri), fixture.openedUris.toList())
    }

    @Test fun directoryNodesNamedLikeLyricsAreNotTextMatches() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("directory").copy(kind = LocalNodeKind.DIRECTORY))
        val result = loaded(fixture.loader().load(fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))))
        assertEquals(LyricsSiblingStatus.NO_MATCH, result.siblingStatus)
        assertEquals(LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT, result.source.origin)
    }

    @Test fun sourceIncarnationChangeDuringCursorCloseRejectsItsDirectoryResult() = runBlocking {
        val fixture = LocalLyricsLoadFixture()
        fixture.directory = listOf(fixture.audioRow, fixture.sibling("old source"))
        fixture.cursorCloseHook = {
            val observed = fixture.observations.getValue(fixture.source.sourceId)
            fixture.observations[fixture.source.sourceId] = observed.copy(identity = "replacement-incarnation")
        }
        assertEquals(LyricsLoadIssue.SOURCE_CHANGED, failure(fixture.loader().load(
            fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3"))))).issue)
        assertTrue(fixture.openedUris.isEmpty())
    }
}
