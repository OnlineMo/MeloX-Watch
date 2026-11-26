package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 测试定义验证原生 Room 发布；物理权限与输出 AC 仍须分别验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomCueLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: RoomCueLibraryFixture
    @Before fun open() = runBlocking {
        fixture = RoomCueLibraryFixture(RuntimeEnvironment.getApplication() as Context, File(temporary.root, "cue-room.db"))
        fixture.open()
    }
    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L)
    fun cueDocumentCreatesNoPlayableTrackAndCannotCompleteBeforeActualGroupCommit() = runBlocking {
        val staged = fixture.stage()
        assertEquals(1, fixture.tracks().size)
        assertNull(fixture.tracks().single().cueReference)
        fixture.library.nextCueDocument(staged.token)
        expect(LibraryIssue.INCOMPLETE) { fixture.library.completeCueDocument(staged.token, staged.cue.objectId) }
        expect(LibraryIssue.BUSY) { fixture.library.nextCueDocument(staged.token) }
        assertTrue(fixture.database.cueDao().currentGroups(checkNotNull(fixture.database.cueDao().document(
            RoomCueLibraryFixture.SOURCE_ID, staged.cue.objectId)).cueId).isEmpty())
    }

    @Test(timeout = 30_000L)
    fun pendingDirectoryFrontierBlocksCueReadsUntilItsOwnedTraversalRetires() = runBlocking {
        val staged = fixture.stage(retireDirectories = false)
        expect(LibraryIssue.INCOMPLETE) { fixture.library.nextCueDocument(staged.token) }
        fixture.retireDirectories(staged.token)
        assertEquals(staged.cue, fixture.library.nextCueDocument(staged.token))
    }

    @Test(timeout = 30_000L)
    fun realWaveBoundariesCommitTogetherAndOriginalFullTrackRemainsAfterReopen() = runBlocking {
        val staged = fixture.stage()
        val (prepared, receipt) = fixture.accept(staged)
        fixture.finish(staged)
        assertEquals(2L, receipt.addedTracks)
        val tracks = fixture.tracks()
        assertEquals(3, tracks.size)
        assertEquals("Whole file", tracks.single { it.cueReference == null }.title)
        val segments = fixture.segments()
        assertEquals(listOf("First segment", "Second segment"), segments.map { it.title })
        val first = checkNotNull(segments[0].cueReference)
        val second = checkNotNull(segments[1].cueReference)
        assertEquals(0L, first.identity.startFrame)
        assertEquals(76L, checkNotNull(first.identity.endFrameExclusive))
        assertEquals(1_013_333L, first.identity.endUsExclusive)
        assertEquals(checkNotNull(first.identity.endFrameExclusive), second.identity.startFrame)
        assertEquals(checkNotNull(first.pcmWindow).endSampleExclusive, checkNotNull(second.pcmWindow).firstSample)
        assertEquals(44_688L, checkNotNull(second.pcmWindow).firstSample)
        assertEquals(88_200L, checkNotNull(second.pcmWindow).endSampleExclusive)
        assertEquals(prepared.document.identity.cueId, first.identity.cueId)
        assertNotEquals(segments[0].trackKey, segments[1].trackKey)
        fixture.reopen()
        assertEquals(segments, fixture.segments())
        assertEquals(1L, checkNotNull(fixture.database.cueDao().documentById(first.identity.cueId)).lastGroupRevision)
    }

    @Test(timeout = 30_000L)
    fun repeatedCompleteScanReusesDocumentTrackKeysGroupRevisionAndAddedSequence() = runBlocking {
        val initial = fixture.stage()
        fixture.accept(initial);
        fixture.finish(initial)
        val before = fixture.segments()
        val nextAdded = checkNotNull(fixture.database.libraryDao().meta()).nextAddedSequence
        val second = fixture.stage(wholeTitle = "New physical tags")
        val (_, receipt) = fixture.accept(second);
        fixture.finish(second)
        assertTrue(receipt.alreadyCommitted)
        assertEquals(0L, receipt.addedTracks)
        assertEquals(before, fixture.segments())
        assertEquals(nextAdded, checkNotNull(fixture.database.libraryDao().meta()).nextAddedSequence)
        assertEquals("New physical tags", fixture.tracks().single { it.cueReference == null }.title)
    }

    @Test(timeout = 30_000L)
    fun contentOrBoundaryRevisionChangePreservesLogicalKeysAndRejectsOldBinding() = runBlocking {
        val initial = fixture.stage()
        fixture.accept(initial);
        fixture.finish(initial)
        val before = fixture.segments()
        val next = fixture.stage(text = RoomCueLibraryFixture.CUE.replace("00:01:01", "00:01:02"))
        fixture.accept(next);
        fixture.finish(next)
        val after = fixture.segments()
        assertEquals(before.map { it.trackKey }, after.map { it.trackKey })
        assertEquals(2L, checkNotNull(after[0].cueReference).identity.groupRevision)
        expect(LibraryIssue.SOURCE_CHANGED) { fixture.library.resolveCueRead(before[0].trackKey, before[0].cueReference) }
        assertNotEquals(before[0].cueReference, after[0].cueReference)
    }

    @Test(timeout = 30_000L)
    fun oldViewRetainsExactMicrosecondBoundsWhileCurrentGroupAdvances() = runBlocking {
        val initial = fixture.stage();
        fixture.accept(initial);
        fixture.finish(initial)
        val view = fixture.library.openView()
        try {
            val before = fixture.library.page(view).tracks.filter { it.cueReference != null }
            val next = fixture.stage(text = RoomCueLibraryFixture.CUE.replace("00:01:01", "00:01:03"))
            fixture.accept(next);
            fixture.finish(next)
            assertEquals(before, fixture.library.page(view).tracks.filter { it.cueReference != null })
            assertNotEquals(before.first().cueReference, fixture.segments().first().cueReference)
        } finally { fixture.library.closeView(view.viewId) }
    }

    @Test(timeout = 30_000L)
    fun sqliteFailureAfterFirstSegmentWriteRollsBackWholeGroupAndPublicationCounters() = runBlocking {
        val staged = fixture.stage()
        val prepared = fixture.prepare(staged)
        val before = fixture.database.libraryDao().meta()
        val key = prepared.segments[1].identity.trackKey.trackId
        fixture.database.withTestWriterSql {
            execute("""CREATE TRIGGER fail_second_cue BEFORE UPDATE ON local_track_versions
                WHEN NEW.trackId = '$key' AND NEW.cueReference IS NOT NULL
                BEGIN SELECT RAISE(ABORT, 'cue-fixture'); END""".trimIndent())
        }
        try {
            expectAny { fixture.library.commitCueGroup(staged.token, prepared, mapOf(0 to staged.facts)) }
        } finally { fixture.database.withTestWriterSql { execute("DROP TRIGGER fail_second_cue") } }
        assertEquals(before, fixture.database.libraryDao().meta())
        assertEquals(1, fixture.tracks().size)
        val document = checkNotNull(fixture.database.cueDao().document(RoomCueLibraryFixture.SOURCE_ID, staged.cue.objectId))
        assertEquals(0L, document.lastGroupRevision)
        assertTrue(fixture.database.cueDao().currentGroups(document.cueId).isEmpty())
        assertNull(fixture.database.libraryDao().currentTrack(prepared.segments[0].identity.trackKey.trackId))
        assertEquals(1L, checkNotNull(fixture.database.libraryDao().scan(staged.token.scanId)).added)
    }

    @Test(timeout = 30_000L)
    fun cancellationBeforeWriterEntryKeepsPriorWholeGroupAndNoDuplicateRows() = runBlocking {
        val initial = fixture.stage();
        fixture.accept(initial);
        fixture.finish(initial)
        val before = fixture.segments()
        val staged = fixture.stage(text = RoomCueLibraryFixture.CUE.replace("First segment", "Edited segment"))
        val prepared = fixture.prepare(staged)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            fixture.library.commitCueGroup(staged.token, prepared, mapOf(0 to staged.facts))
        }
        job.join()
        fixture.library.setScanState(staged.token, ScanPhase.CANCELLED)
        assertEquals(before.map { it.trackKey }, fixture.segments().map { it.trackKey })
        assertEquals(before.map { it.cueReference }, fixture.segments().map { it.cueReference })
        assertEquals(before.map { it.title }, fixture.segments().map { it.title })
        assertEquals(3, fixture.tracks().size)
    }

    @Test(timeout = 30_000L)
    fun malformedOrMissingAudioOutcomeDoesNotRetirePriorLogicalSegments() = runBlocking {
        val initial = fixture.stage();
        fixture.accept(initial);
        fixture.finish(initial)
        val before = fixture.segments()
        val staged = fixture.stage()
        fixture.library.nextCueDocument(staged.token)
        fixture.library.completeCueDocument(staged.token, staged.cue.objectId, CueIssue.MISSING_AUDIO)
        fixture.library.setScanState(staged.token, ScanPhase.FAILED, SourceReadIssue.FILE_MISSING)
        assertEquals(before, fixture.segments())
        assertEquals(1L, checkNotNull(fixture.database.cueDao().document(RoomCueLibraryFixture.SOURCE_ID, staged.cue.objectId)).lastGroupRevision)
    }

    @Test(timeout = 30_000L)
    fun cueAndAudioDescriptorsRetainIndependentAliasesLocationsAndActualHashBaselines() = runBlocking {
        val staged = fixture.stage();
        fixture.accept(staged);
        fixture.finish(staged)
        val segment = fixture.segments().first()
        val read = fixture.library.resolveCueRead(segment.trackKey, segment.cueReference)
        assertNotEquals(read.cue.aliasId, read.audio.aliasId)
        assertNotEquals(read.cue.location.locationId, read.audio.location.locationId)
        assertEquals(RoomCueLibraryFixture.sha(staged.bytes), read.cue.verifiedContentToken)
        assertEquals(RoomCueLibraryFixture.sha(fixture.wave), read.audio.verifiedContentToken)
        assertEquals(segment.cueReference, read.audio.cueReference)
    }

    @Test(timeout = 30_000L)
    fun documentPermissionFailureDisablesSegmentsButLeavesIndependentWholeTrackReadable() = runBlocking {
        val staged = fixture.stage();
        fixture.accept(staged);
        fixture.finish(staged)
        val segment = fixture.segments().first()
        val cueAlias = checkNotNull(segment.cueReference?.binding).cue.aliasId
        fixture.database.withTestWriterTransaction {
            execute("UPDATE local_alias_versions SET readIssue = 'PERMISSION_REVOKED' WHERE aliasId = ? AND validTo IS NULL", cueAlias)
        }
        expect(LibraryIssue.PERMISSION_REVOKED) { fixture.library.resolveCueRead(segment.trackKey, segment.cueReference) }
        val tracks = fixture.tracks()
        assertEquals(LibraryAvailability.AVAILABLE, tracks.single { it.cueReference == null }.availability)
        assertTrue(tracks.filter { it.cueReference != null }.all { it.availability == LibraryAvailability.NEEDS_AUTHORIZATION })
        assertEquals(1, fixture.tracks(LibraryQuery(readableOnly = true)).size)
    }

    @Test(timeout = 30_000L)
    fun changedSourceAuthorizationRejectsPreparedGroupWithoutChangingPriorMembers() = runBlocking {
        val initial = fixture.stage();
        fixture.accept(initial);
        fixture.finish(initial)
        val before = fixture.segments()
        val staged = fixture.stage()
        val prepared = fixture.prepare(staged)
        val source = checkNotNull(fixture.library.source(RoomCueLibraryFixture.SOURCE_ID))
        fixture.library.setSourceIssue(source.spec.sourceId, source.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        expect(LibraryIssue.SOURCE_CHANGED) { fixture.library.commitCueGroup(staged.token, prepared, mapOf(0 to staged.facts)) }
        assertEquals(before.map { it.trackKey }, fixture.segments().map { it.trackKey })
        assertEquals(before.map { it.cueReference }, fixture.segments().map { it.cueReference })
    }

    @Test(timeout = 30_000L)
    fun portablePendingRestoreKeepsKeysAndCanOnlyRebindThroughACompleteVerifiedRescan() = runBlocking {
        val initial = fixture.stage();
        fixture.accept(initial);
        fixture.finish(initial)
        val before = fixture.segments()
        val cueId = checkNotNull(before.first().cueReference).identity.cueId
        fixture.database.withTestWriterTransaction {
            val revision = checkNotNull(fixture.database.libraryDao().meta()).revision + 1L
            assertEquals(1, fixture.database.cueDao().closeGroup(cueId, revision))
            assertEquals(1, fixture.database.cueDao().markReassociationPending(cueId))
            for (track in before) execute("UPDATE local_track_versions SET cueReference = ? WHERE trackId = ? AND validTo IS NULL",
                CueReferenceCodec.encode(checkNotNull(track.cueReference).portable()), track.trackKey.trackId)
            execute("UPDATE library_meta SET revision = ? WHERE id = 1", revision)
        }
        val pending = fixture.segments()
        assertEquals(before.map { it.trackKey }, pending.map { it.trackKey })
        assertTrue(pending.all { it.location == null && it.cueReference?.isBound == false })
        expect(LibraryIssue.SOURCE_UNAVAILABLE) { fixture.library.resolveCueRead(pending.first().trackKey, pending.first().cueReference) }
        val scan = fixture.stage();
        fixture.accept(scan);
        fixture.finish(scan)
        assertEquals(before.map { it.trackKey }, fixture.segments().map { it.trackKey })
        assertEquals(2L, checkNotNull(fixture.segments().first().cueReference).identity.groupRevision)
        assertFalse(checkNotNull(fixture.database.cueDao().documentById(cueId)).reassociationPending)
    }

    private suspend fun expect(issue: LibraryIssue, action: suspend () -> Any?) {
        try {
            action();
            throw AssertionError("Expected $issue")
        }
        catch (error: LibraryException) { assertEquals(issue, error.issue) }
    }
    private suspend fun expectAny(action: suspend () -> Any?) {
        try {
            action();
            throw AssertionError("Expected a rejected writer")
        }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }
}
