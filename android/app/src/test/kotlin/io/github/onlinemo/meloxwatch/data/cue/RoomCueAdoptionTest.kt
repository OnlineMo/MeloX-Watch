package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.library.metadata
import io.github.onlinemo.meloxwatch.data.listening.ListeningHistoryEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningMetaEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningRoundEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackEntity
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueHeadEntity
import io.github.onlinemo.meloxwatch.data.queue.record
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** 原生 Room 测试定义使用真实 PCM/CUE 字节；归档、提供方、服务与设备门槛分别验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomCueAdoptionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: RoomCueLibraryFixture
    @Before fun open() = runBlocking {
        fixture = RoomCueLibraryFixture(RuntimeEnvironment.getApplication() as Context, File(temporary.root, "cue-adoption.db"))
        fixture.open()
    }
    @After fun close() { fixture.close() }

    @Test(timeout = 120_000L)
    fun identicalBomCueAndWaveAdoptAllKnownMembersAndOverTenThousandDuplicatesWithoutChangingHistoryOrOrders() = runBlocking {
        val setup = setup(cueInstances = 10_025, playlists = true, hideSecond = true)
        val lists = checkNotNull(setup.playlists)
        val view = lists.openView(LIST_ID)
        val favorites = lists.openView(PlaylistLimits.FAVORITES_ID)
        val oldMembers = members(lists, view)
        val oldFavorites = members(lists, favorites)
        val oldHistory = fixture.database.listeningDao().history(0L, 1L, 1L)
        val oldStatistics = fixture.database.listeningDao().track(0L, TrackKey.LOCAL_PROVIDER, setup.keys.first().trackId)
        val beforeHead = checkNotNull(fixture.database.queueDao().head())
        val plan = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        assertEquals(2, plan.targets.size)
        assertEquals(10_025L, plan.affectedQueueEntries)
        assertEquals(4L, plan.affectedPlaylistEntries)
        assertTrue(plan.differences.identityChanged)
        assertFalse(plan.differences.differentOrUnknownContent)
        assertEquals(0, plan.differences.boundsChanged)
        assertTrue(fixture.library.isCueAdoptionCurrent(plan))
        expect(LibraryIssue.INVALID_INPUT) { commit(plan, cue = false) }
        val accepted = commit(plan)
        assertEquals(2, accepted.updatedTracks)
        assertEquals(10_025L, accepted.repairedQueueEntries)
        val mapped = logicalRows().associateBy { CueReferenceCodec.decode(checkNotNull(it.cueReference)).identity.trackNumber }
        assertFalse(checkNotNull(mapped[2]).member)
        assertEquals(listOf("Saved first", "Saved second"), mapped.values.sortedBy { it.trackNumber }.map { it.title })
        assertEquals(1L, checkNotNull(mapped[1]).playCount)
        assertEquals(800L, checkNotNull(mapped[1]).listeningMs)
        val reference = CueReferenceCodec.decode(checkNotNull(mapped[1]?.cueReference))
        assertEquals(setup.keys[0], reference.trackKey)
        assertEquals(TARGET, reference.actualDocument.sourceId)
        assertEquals(plan.aliasId, reference.binding?.adoptionAliasId)
        assertNotEquals(reference.identity.cueId, reference.actualDocument.cueId)
        assertWaveWindows(reference, CueReferenceCodec.decode(checkNotNull(mapped[2]?.cueReference)))
        var first = 0L
        while (true) {
            val page = fixture.database.queueDao().originalPage(GENERATION, first, 100)
            if (page.isEmpty()) break
            assertTrue(page.size <= 100)
            for (entry in page) {
                assertEquals(id(entry.originalOrder.toInt()), entry.queueEntryId)
                assertEquals(10_025L - entry.originalOrder, entry.playbackOrder)
                assertEquals("Saved queue ${entry.originalOrder}", entry.displayName)
                if (entry.originalOrder < 10_025L) {
                    val cue = CueReferenceCodec.decode(checkNotNull(entry.cueReference))
                    assertEquals(setup.keys[(entry.originalOrder % 2L).toInt()], cue.trackKey)
                    assertEquals(plan.aliasId, cue.binding?.adoptionAliasId)
                } else assertEquals(setup.control, entry)
            }
            first += page.size
        }
        assertEquals(10_026L, first)
        val afterHead = checkNotNull(fixture.database.queueDao().head())
        assertEquals(beforeHead.currentEntryId, afterHead.currentEntryId)
        assertEquals(beforeHead.positionMs, afterHead.positionMs)
        assertEquals(beforeHead.completedCycles, afterHead.completedCycles)
        assertEquals(beforeHead.queueRevision + 1L, afterHead.queueRevision)
        assertEquals(beforeHead.checkpointRevision + 1L, afterHead.checkpointRevision)
        assertEquals(afterHead.queueRevision, checkNotNull(checkNotNull(fixture.database.queueDao().generation(GENERATION)).committedQueueRevision))
        assertEquals(oldHistory, fixture.database.listeningDao().history(0L, 1L, 1L))
        assertEquals(oldStatistics, fixture.database.listeningDao().track(0L, TrackKey.LOCAL_PROVIDER, setup.keys.first().trackId))
        assertEquals(oldMembers, members(lists, view))
        assertEquals(oldFavorites, members(lists, favorites))
        val currentView = lists.openView(LIST_ID)
        try {
            val current = members(lists, currentView)
            assertEquals(oldMembers.map { it.memberId }, current.map { it.memberId })
            assertEquals(oldMembers.map { it.ordinal }, current.map { it.ordinal })
            assertTrue(current.take(105).all { it == oldMembers[it.ordinal.toInt()] })
            assertTrue(current.takeLast(2).all { it.reference.cueReference?.identity?.groupRevision == plan.logicalGroupRevision })
        } finally { lists.closeView(currentView.viewId) }
        lists.closeView(view.viewId);
        lists.closeView(favorites.viewId)
        val beforeReplayHead = fixture.database.queueDao().head()
        assertEquals(accepted, commit(plan))
        assertEquals(beforeReplayHead, fixture.database.queueDao().head())
        fixture.reopen()
        val read = fixture.library.resolveCueRead(setup.keys[0], reference)
        assertEquals("Saved first", read.metadata?.title)
        assertEquals(reference, read.audio.cueReference)
        assertEquals(RoomCueLibraryFixture.sha(fixture.wave), read.audio.verifiedContentToken)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun cueAndDifferentContentConfirmationsRemainSeparateAndNewPausedCheckpointIsClamped() = runBlocking {
        val changedWave = fixture.wave.copyOf().also { it[44] = (it[44].toInt() xor 1).toByte() }
        val setup = setup(targetCue = BOM_CUE.replace("00:01:01", "00:01:00"), targetWave = changedWave)
        val head = checkNotNull(fixture.database.queueDao().head())
        fixture.database.queueDao().checkpoint(GENERATION, head.checkpointRevision, head.checkpointRevision + 1L, head.currentEntryId, 1005L, head.completedCycles)
        val plan = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        assertTrue(plan.differences.cueContentChanged)
        assertEquals(2, plan.differences.audioContentChanged)
        assertEquals(2, plan.differences.boundsChanged)
        expect(LibraryIssue.INVALID_INPUT) { commit(plan, cue = false, content = true) }
        expect(LibraryIssue.INVALID_INPUT) { commit(plan, cue = true, content = false) }
        commit(plan, content = true)
        assertEquals(1000L, checkNotNull(fixture.database.queueDao().head()).positionMs)
        assertEquals(head.completedCycles, checkNotNull(fixture.database.queueDao().head()).completedCycles)
    }

    @Test(timeout = 30_000L)
    fun unknownAudioHintCannotBeTreatedAsAnIdenticalFile() = runBlocking {
        val setup = setup()
        fixture.library.libraryTransaction {
            val meta = checkNotNull(fixture.database.libraryDao().meta())
            for (row in logicalRows()) {
                val reference = CueReferenceCodec.decode(checkNotNull(row.cueReference))
                fixture.library.writeTrackVersion(row.copy(cueReference = CueReferenceCodec.encode(LocalCueReference(
                    reference.identity.copy(audioContentTokenHint = null)))), row.metadata(), row.filename, row.metadataIssue, meta.revision + 1L)
            }
            fixture.database.libraryDao().updateMeta(meta.copy(revision = meta.revision + 1L))
        }
        val plan = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        assertEquals(2, plan.differences.unknownAudioContent)
        expect(LibraryIssue.INVALID_INPUT) { commit(plan) }
        assertTrue(fixture.library.isCueAdoptionCurrent(plan))
        commit(plan, content = true)
    }

    @Test(timeout = 30_000L)
    fun aMissingOriginalNumberAndBareWholeAudioBothRejectTheCompleteGroup() = runBlocking {
        val oneTrack = BOM_CUE.substringBefore("TRACK 02 AUDIO")
        val setup = setup(targetCue = oneTrack)
        expect(LibraryIssue.INCOMPLETE) { fixture.library.previewCueAdoption(setup.keys[0], setup.selected) }
        val whole = fixture.tracks().first { it.cueReference == null }
        expect(LibraryIssue.INVALID_INPUT) { fixture.library.previewCueAdoption(setup.keys[0], whole.trackKey) }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM local_cue_aliases"))
        assertTrue(logicalRows().all { CueReferenceCodec.decode(checkNotNull(it.cueReference)).binding == null })
    }

    @Test(timeout = 30_000L)
    fun lateLibraryCheckpointAndNativeAuthorizationChangesInvalidateTheirWholePreview() = runBlocking {
        val setup = setup()
        val first = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        fixture.database.withTestWriterSql { execute("UPDATE library_meta SET revision = revision + 1 WHERE id = 1") }
        assertFalse(fixture.library.isCueAdoptionCurrent(first))
        val second = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        val head = checkNotNull(fixture.database.queueDao().head())
        fixture.database.queueDao().checkpoint(GENERATION, head.checkpointRevision, head.checkpointRevision + 1L, head.currentEntryId, 902L, head.completedCycles)
        assertFalse(fixture.library.isCueAdoptionCurrent(second))
        val third = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        fixture.database.withTestWriterSql { execute("UPDATE local_sources SET authEpoch = authEpoch + 1 WHERE sourceId = ?", TARGET) }
        assertFalse(fixture.library.isCueAdoptionCurrent(third))
        expect(LibraryIssue.CONFLICT) { commit(third, content = true) }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM local_cue_alias_versions"))
    }

    @Test(timeout = 30_000L)
    fun previewReplacementExpiryAndCancellationBeforeCommitCannotLeavePartialLibraryQueueOrPlaylistState() = runBlocking {
        val setup = setup(playlists = true)
        val obsolete = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        val plan = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        assertFalse(fixture.library.isCueAdoptionCurrent(obsolete))
        val beforeRows = logicalRows()
        val beforeHead = fixture.database.queueDao().head()
        val beforePlaylistMeta = fixture.database.playlistDao().meta()
        val job = launch {
            fixture.library.libraryTransaction {
                fixture.library.commitCueAdoptionUnlocked(plan, allowCueDifferences = true)
                currentCoroutineContext().cancel(CancellationException("Before native COMMIT"))
                currentCoroutineContext().ensureActive()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(beforeRows, logicalRows())
        assertEquals(beforeHead, fixture.database.queueDao().head())
        assertEquals(beforePlaylistMeta, fixture.database.playlistDao().meta())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM local_cue_alias_versions"))
        assertTrue(fixture.library.isCueAdoptionCurrent(plan))
        fixture.elapsed += 600_000L
        assertFalse(fixture.library.isCueAdoptionCurrent(plan))
        expect(LibraryIssue.CONFLICT) { commit(plan) }
        integrity()
    }

    @Test(timeout = 30_000L)
    fun sqliteFailureAfterFirstQueueRepairsRollsBackAliasRowsRelationsAndBothRevisionDomains() = runBlocking {
        val setup = setup(playlists = true)
        val plan = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        val before = logicalRows()
        val beforeHead = fixture.database.queueDao().head()
        val beforeMeta = fixture.database.libraryDao().meta()
        fixture.database.withTestWriterSql {
            execute("CREATE TRIGGER abort_cue_repair BEFORE UPDATE ON queue_entries WHEN NEW.queueEntryId = 'cue-entry-00001' " +
                "BEGIN SELECT RAISE(ABORT, 'cue-queue-rollback'); END")
        }
        try { expect(LibraryIssue.INVALID_INPUT) { commit(plan) } }
        finally { fixture.database.withTestWriterSql { execute("DROP TRIGGER abort_cue_repair") } }
        assertEquals(before, logicalRows())
        assertEquals(beforeHead, fixture.database.queueDao().head())
        assertEquals(beforeMeta, fixture.database.libraryDao().meta())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM local_cue_aliases"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE operationId = ?", plan.id))
        assertTrue(fixture.library.isCueAdoptionCurrent(plan))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun oldQueueAndPlaylistHintsExposeDifferencesEvenWhenTheCurrentLibraryAlreadyHasAValidBinding() = runBlocking {
        val setup = setup(playlists = true)
        commit(fixture.library.previewCueAdoption(setup.keys[0], setup.selected))
        val current = CueReferenceCodec.decode(checkNotNull(logicalRows().first().cueReference))
        assertFalse(fixture.library.cueNeedsReassociation(current.trackKey))
        val changedHint = LocalCueReference(current.identity.copy(startFrame = 1L, audioContentTokenHint = null))
        fixture.database.withTestWriterSql {
            execute("UPDATE playlist_members SET cueReference = ? WHERE generationId IN " +
                "(SELECT g.dataGenerationId FROM playlist_heads h JOIN playlist_generations g ON g.generationId = h.activeGenerationId) " +
                "AND trackId = ?", CueReferenceCodec.encode(changedHint), current.trackKey.trackId)
            execute("UPDATE queue_entries SET cueReference = ?, locationId = NULL, locationUri = NULL, grantUri = NULL, " +
                "grantFlags = NULL, access = NULL, contentRevision = NULL WHERE generationId = ? AND trackId = ?",
                CueReferenceCodec.encode(changedHint), GENERATION, current.trackKey.trackId)
        }
        assertTrue(fixture.library.cueNeedsReassociation(current.trackKey))
        val plan = fixture.library.previewCueAdoption(current.trackKey, setup.selected)
        assertEquals(1, plan.differences.unknownAudioContent)
        assertEquals(1, plan.differences.boundsChanged)
        assertTrue(plan.affectedQueueEntries > 0L && plan.affectedPlaylistEntries > 0L)
        expect(LibraryIssue.INVALID_INPUT) { commit(plan) }
        commit(plan, content = true)
        assertFalse(fixture.library.cueNeedsReassociation(current.trackKey))
    }

    @Test(timeout = 30_000L)
    fun anOlderBoundQueueRequiresAccessConfirmationEvenWhenTheCurrentLibraryCueAndAudioBoundsMatch() = runBlocking {
        val initial = fixture.stage(text = BOM_CUE);
        fixture.accept(initial);
        fixture.finish(initial)
        val old = checkNotNull(fixture.segments().first().cueReference)
        val entry = QueueEntry(id(0), old.trackKey, 0L, 0L, "Bound queue", old.identity.durationUs / 1000L,
            location = checkNotNull(old.binding).audio.location, cueReference = old).record(GENERATION)
        fixture.database.queueDao().insertGeneration(QueueGenerationEntity(GENERATION, "cue-access-operation", null, 0L,
            "cue-access-input", 1L, 1L, false, "LIST", null, 0L, 700L, false,
            phase = 2, writtenCount = 1L, committedQueueRevision = 1L))
        fixture.database.queueDao().insertEntries(listOf(entry))
        fixture.database.queueDao().insertHead(QueueHeadEntity(activeGenerationId = GENERATION, queueRevision = 1L,
            checkpointRevision = 1L, currentEntryId = id(0), positionMs = 700L))
        val source = checkNotNull(fixture.library.source(RoomCueLibraryFixture.SOURCE_ID))
        fixture.library.reauthorizeSource(source.spec.sourceId, source.authEpoch, source.spec,
            source.grant.copy(offlineRead = ObservedCapability.AVAILABLE))
        val rescanned = fixture.stage(text = BOM_CUE);
        fixture.accept(rescanned);
        fixture.finish(rescanned)
        val current = checkNotNull(fixture.segments().first().cueReference)
        assertNotEquals(old.actualDocument.groupRevision, current.actualDocument.groupRevision)
        assertNotEquals(old.binding?.cue?.authEpoch, current.binding?.cue?.authEpoch)
        assertEquals(old.identity.cueContentToken, current.identity.cueContentToken)
        assertEquals(old.pcmWindow, current.pcmWindow)
        assertEquals(entry, fixture.database.queueDao().entry(GENERATION, id(0)))
        assertEquals(current, fixture.library.resolveCueRead(current.trackKey, current).reference)
        assertTrue(fixture.library.cueNeedsReassociation(current.trackKey))
        val plan = fixture.library.previewCueAdoption(current.trackKey, current.trackKey)
        assertTrue(plan.targets.all { target -> plan.sources.any { it.reference == target.reference } })
        assertTrue(plan.differences.identityChanged)
        assertFalse(plan.differences.cueContentChanged)
        assertFalse(plan.differences.differentOrUnknownContent)
        assertEquals(0, plan.differences.boundsChanged)
        assertEquals(1L, plan.affectedQueueEntries)
        val beforeHead = fixture.database.queueDao().head()
        expect(LibraryIssue.INVALID_INPUT) { commit(plan, cue = false) }
        assertEquals(beforeHead, fixture.database.queueDao().head())
        commit(plan)
        val repaired = CueReferenceCodec.decode(checkNotNull(fixture.database.queueDao().entry(GENERATION, id(0))?.cueReference))
        assertEquals(current.actualDocument, repaired.actualDocument)
        assertFalse(fixture.library.cueNeedsReassociation(current.trackKey))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun aChangedOrCrossPlaylistDataOwnerRejectsPreviewAndCommitWithoutAnyRelationWrites() = runBlocking {
        val setup = setup(playlists = true)
        val repository = checkNotNull(setup.playlists)
        val snapshot = checkNotNull(repository.snapshot(LIST_ID))
        repository.apply(PlaylistMutationRequest("cue-rename-before-adoption", LIST_ID, snapshot.revision,
            PlaylistMutation.Rename("Retained renamed list")))
        val dao = fixture.database.playlistDao()
        val active = checkNotNull(dao.currentGeneration(LIST_ID))
        assertNotEquals(active.generationId, active.dataGenerationId)
        val favorites = checkNotNull(dao.currentGeneration(PlaylistLimits.FAVORITES_ID))
        val beforeRows = logicalRows()
        val beforeQueue = fixture.database.queueDao().head()
        fixture.database.withTestWriterSql {
            execute("UPDATE playlist_generations SET dataGenerationId = ? WHERE generationId = ?",
                favorites.dataGenerationId, active.generationId)
        }
        expect(LibraryIssue.RECOVERY_REQUIRED) { fixture.library.previewCueAdoption(setup.keys[0], setup.selected) }
        fixture.database.withTestWriterSql {
            execute("UPDATE playlist_generations SET dataGenerationId = ? WHERE generationId = ?",
                active.dataGenerationId, active.generationId)
        }
        val plan = fixture.library.previewCueAdoption(setup.keys[0], setup.selected)
        fixture.database.withTestWriterSql {
            execute("UPDATE playlist_generations SET memberCount = memberCount + 1 WHERE generationId = ?", active.dataGenerationId)
        }
        assertFalse(fixture.library.isCueAdoptionCurrent(plan))
        expect(LibraryIssue.CONFLICT) { commit(plan) }
        assertEquals(beforeRows, logicalRows())
        assertEquals(beforeQueue, fixture.database.queueDao().head())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM local_cue_alias_versions"))
        fixture.database.withTestWriterSql {
            execute("UPDATE playlist_generations SET memberCount = ? WHERE generationId = ?", active.memberCount, active.dataGenerationId)
        }
        assertTrue(fixture.library.isCueAdoptionCurrent(plan))
        commit(plan)
        assertNotEquals(active.dataGenerationId, checkNotNull(dao.currentGeneration(LIST_ID)).dataGenerationId)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun aChangedActualNativeGroupDisablesAliasesWithoutReplacingOldSavedReferences() = runBlocking {
        val setup = setup()
        commit(fixture.library.previewCueAdoption(setup.keys[0], setup.selected))
        val before = logicalRows()
        val saved = CueReferenceCodec.decode(checkNotNull(before.first().cueReference))
        val target = fixture.stage(text = BOM_CUE.replace("00:01:01", "00:01:03"), sourceId = TARGET)
        fixture.accept(target);
        fixture.finish(target)
        assertEquals(before, logicalRows())
        expect(LibraryIssue.SOURCE_CHANGED) { fixture.library.resolveCueRead(saved.trackKey, saved) }
        assertTrue(fixture.library.cueNeedsReassociation(saved.trackKey))
        assertEquals(saved, CueReferenceCodec.decode(checkNotNull(fixture.database.queueDao().entry(GENERATION, id(0))?.cueReference)))
    }

    @Test(timeout = 30_000L)
    fun nativeFactsAndSavedTagsRemainIndependentAfterAdoptionAndMatchingRescan() = runBlocking {
        val setup = setup()
        commit(fixture.library.previewCueAdoption(setup.keys[0], setup.selected))
        val before = logicalRows()
        val scan = fixture.stage(text = BOM_CUE)
        fixture.accept(scan);
        fixture.finish(scan)
        assertEquals(before, logicalRows())
        val reference = CueReferenceCodec.decode(checkNotNull(before.first().cueReference))
        assertEquals("Saved first", fixture.library.resolveCueRead(reference.trackKey, reference).metadata?.title)
        val document = checkNotNull(fixture.database.cueDao().document(RoomCueLibraryFixture.SOURCE_ID, "sheet"))
        val group = checkNotNull(fixture.database.cueDao().currentGroups(document.cueId).singleOrNull())
        assertEquals(2, fixture.database.cueDao().nativeSegments(group.versionId).size)
        val selected = fixture.library.registeredNativeCueSelection(reference.trackKey)
        assertEquals(group.groupRevision, selected.groupRevision)
        val origin = fixture.library.previewCueAdoption(reference.trackKey, reference.trackKey, selected)
        assertTrue(origin.differences.identityChanged)
        commit(origin)
        assertEquals(document.sourceId, fixture.library.resolveCueRead(reference.trackKey).reference.actualDocument.sourceId)
    }

    @Test(timeout = 30_000L)
    fun newlyDiscoveredNativeNumbersAreAddedWhileExistingExplicitAliasesKeepTheirAcceptedReferences() = runBlocking {
        val setup = setup()
        commit(fixture.library.previewCueAdoption(setup.keys[0], setup.selected))
        val before = logicalRows()
        val expanded = BOM_CUE.trimEnd() + "\nTRACK 03 AUDIO\nTITLE \"Third segment\"\nINDEX 01 00:01:30\n"
        val scan = fixture.stage(text = expanded)
        val (_, receipt) = fixture.accept(scan);
        fixture.finish(scan)
        assertEquals(1L, receipt.addedTracks)
        val rows = logicalRows()
        assertEquals(3, rows.size)
        assertEquals(before, rows.take(2))
        val third = CueReferenceCodec.decode(checkNotNull(rows[2].cueReference))
        assertEquals(3, third.identity.trackNumber)
        assertNull(third.binding?.adoptionAliasId)
        assertEquals(third, fixture.library.resolveCueRead(third.trackKey).reference)
        assertEquals(third.actualDocument, fixture.library.registeredNativeCueSelection(third.trackKey))
    }

    private suspend fun setup(cueInstances: Int = 5, playlists: Boolean = false, hideSecond: Boolean = false,
        targetCue: String = BOM_CUE, targetWave: ByteArray = fixture.wave): Setup {
        val old = fixture.stage(text = BOM_CUE);
        fixture.accept(old);
        fixture.finish(old)
        val target = fixture.stage(text = targetCue, audio = targetWave, sourceId = TARGET);
        fixture.accept(target);
        fixture.finish(target)
        val selected = fixture.segments(TARGET).first().trackKey
        fixture.library.libraryTransaction {
            val meta = checkNotNull(fixture.database.libraryDao().meta())
            for ((index, row) in logicalRows().withIndex()) fixture.library.writeTrackVersion(
                row.copy(member = !(hideSecond && index == 1), playCount = if (index == 0) 1L else 0L, listeningMs = if (index == 0) 800L else 0L),
                row.metadata().copy(title = if (index == 0) "Saved first" else "Saved second"), row.filename, row.metadataIssue, meta.revision + 1L)
            fixture.database.libraryDao().updateMeta(meta.copy(revision = meta.revision + 1L))
        }
        val refs = logicalRows().map { CueReferenceCodec.decode(checkNotNull(it.cueReference)).portable() }
        val listRepository = if (playlists) seedPlaylists(refs) else null
        val first = refs.first().trackKey
        fixture.database.listeningDao().insertMeta(ListeningMetaEntity(nextHistorySequence = 2L, totalPlayCount = 1L, totalListeningMs = 800L))
        fixture.database.listeningDao().putRound(ListeningRoundEntity("cue-retained-round", first.providerId, first.trackId,
            "Saved first", 0L, 0L, 1, 1_000L, 1_000L, "Asia/Shanghai", 1_800L, 1_800L, "Asia/Shanghai", 1_800L, 800L, 1013L,
            true, "2026-11-23", "Asia/Shanghai", 1L))
        fixture.database.listeningDao().putHistory(ListeningHistoryEntity(0L, 1L, "cue-retained-round", first.providerId,
            first.trackId, "Saved first", 1_000L, "Asia/Shanghai", 1_800L, 800L, 1013L))
        fixture.database.listeningDao().putTrack(ListeningTrackEntity(0L, first.providerId, first.trackId, 1L, 800L, 1_800L, "Saved first"))
        makePending()
        val total = cueInstances.toLong() + 1L
        fixture.database.queueDao().insertGeneration(QueueGenerationEntity(GENERATION, "cue-queue-operation", null, 0L,
            "cue-input", 1L, total, true, "LIST", 5, 0L, 901L, false, phase = 2, writtenCount = total, committedQueueRevision = 1L))
        var index = 0
        while (index < cueInstances) {
            val end = minOf(cueInstances, index + 100)
            fixture.database.queueDao().insertEntries((index until end).map { value ->
                val cue = refs[value % refs.size]
                QueueEntry(id(value), cue.trackKey, value.toLong(), total - 1L - value,
                    "Saved queue $value", cue.identity.durationUs / 1000L, cueReference = cue).record(GENERATION)
            })
            index = end
        }
        val whole = fixture.tracks().first { it.cueReference == null }
        val control = QueueEntry(id(cueInstances), whole.trackKey, cueInstances.toLong(), 0L, "Saved queue $cueInstances",
            2000L, location = whole.location).record(GENERATION)
        fixture.database.queueDao().insertEntries(listOf(control))
        fixture.database.queueDao().insertHead(QueueHeadEntity(activeGenerationId = GENERATION, queueRevision = 1L,
            checkpointRevision = 7L, currentEntryId = id(0), positionMs = 901L, completedCycles = 2))
        return Setup(refs.map { it.trackKey }, selected, control, listRepository)
    }

    private suspend fun seedPlaylists(refs: List<LocalCueReference>): RoomPlaylistRepository {
        check(refs.all { !it.isBound })
        val fences = RoomPlaylistFences(fixture.database)
        val repository = RoomPlaylistRepository(fixture.database, fixture.database.playlistDao(), { fixture.elapsed },
            { 1_790_000_000_000L }, "cue-playlists", fences, fences, cueInputRegistrar = { generation ->
                var first = 0L
                while (true) {
                    val page = fixture.database.playlistDao().membersPage(generation, first, PlaylistLimits.PAGE_SIZE)
                    if (page.isEmpty()) break
                    for (member in page) {
                        val cue = member.cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) } ?: continue
                        val metadata = LocalAudioMetadata(title = member.titleHint, artist = member.artistHint,
                            album = member.albumHint, durationMs = member.durationMs ?: cue.identity.durationUs / 1000L,
                            trackNumber = cue.identity.trackNumber)
                        fixture.library.registerPortableCueHintUnlocked(cue, metadata, "CUE ${cue.identity.trackNumber}")
                    }
                    first = Math.addExact(page.last().ordinal, 1L)
                }
            })
        repository.initialize()
        val cues = refs.map { PlaylistReference(it.trackKey, titleHint = "Saved hint ${it.identity.trackNumber}", cueReference = it) }
        for ((index, reference) in cues.withIndex()) {
            val snapshot = checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID))
            repository.apply(PlaylistMutationRequest("cue-favorite-$index", snapshot.playlistId, snapshot.revision,
                PlaylistMutation.SetFavorite(reference, true)))
        }
        val input = List(105) { PlaylistReference(placeholderId = "retained-placeholder-$it",
            issue = PlaylistReferenceIssue.REQUIRES_REASSOCIATION, titleHint = "Retained $it") } + cues
        val frozen = FrozenPlaylistInput("cue-playlist-input", 1L, input.size.toLong())
        repository.apply(PlaylistMutationRequest("cue-playlist-create", LIST_ID, 0L, PlaylistMutation.Create("Retained list", frozen)),
            PlaylistPageSource { offset, limit ->
                val end = minOf(input.size.toLong(), offset + limit).toInt()
                PlaylistInputPage(frozen, offset, input.subList(offset.toInt(), end), end.toLong() == frozen.totalCount)
            })
        return repository
    }

    private suspend fun makePending() = fixture.library.libraryTransaction {
        val meta = checkNotNull(fixture.database.libraryDao().meta())
        val rows = logicalRows()
        val cueId = CueReferenceCodec.decode(checkNotNull(rows.first().cueReference)).identity.cueId
        fixture.database.cueDao().closeGroup(cueId, meta.revision + 1L)
        fixture.database.cueDao().markReassociationPending(cueId)
        for (row in rows) fixture.library.writeTrackVersion(row.copy(cueReference = CueReferenceCodec.encode(
            CueReferenceCodec.decode(checkNotNull(row.cueReference)).portable())), row.metadata(), row.filename, row.metadataIssue, meta.revision + 1L)
        fixture.database.libraryDao().updateMeta(meta.copy(revision = meta.revision + 1L))
    }
    private suspend fun logicalRows(): List<LibraryTrackVersionEntity> {
        val document = fixture.database.cueDao().document(RoomCueLibraryFixture.SOURCE_ID, "sheet") ?: return emptyList()
        return fixture.database.libraryDao().currentLogicalCueTracks(document.sourceId, document.cueId)
            .sortedBy { CueReferenceCodec.decode(checkNotNull(it.cueReference)).identity.trackNumber }
    }
    private suspend fun commit(plan: CueAdoptionPlan, cue: Boolean = true, content: Boolean = false): CueAdoptionCommit =
        fixture.library.libraryTransaction { fixture.library.commitCueAdoptionUnlocked(plan, cue, content) }
    private suspend fun members(repository: RoomPlaylistRepository, view: PlaylistViewToken): List<PlaylistMember> {
        val result = mutableListOf<PlaylistMember>()
        var first = 0L
        while (true) {
            val page = repository.page(view, first)
            result += page.members
            first = page.nextOrdinal ?: break
        }
        return result
    }
    private fun assertWaveWindows(first: LocalCueReference, second: LocalCueReference) {
        val one = checkNotNull(first.pcmWindow)
        val two = checkNotNull(second.pcmWindow)
        assertEquals(44_688L, one.endSampleExclusive)
        assertEquals(one.endSampleExclusive, two.firstSample)
        assertEquals(88_200L, two.endSampleExclusive)
        val wave = ByteBuffer.wrap(fixture.wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7000.toShort(), wave.getShort(one.physicalByteOffset.toInt()))
        assertEquals(7000.toShort(), wave.getShort((one.physicalByteOffset + one.payloadBytes - 4L).toInt()))
        assertEquals((-11000).toShort(), wave.getShort(two.physicalByteOffset.toInt()))
        assertEquals(fixture.wave.size.toLong(), two.physicalByteOffset + two.payloadBytes)
    }
    private suspend fun scalar(sql: String, vararg args: Any?): Long = fixture.database.withTestWriterSql { scalarLong(sql, *args) }
    private suspend fun integrity() = fixture.database.withTestWriterSql {
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        assertEquals("ok", scalarText("PRAGMA integrity_check"))
    }
    private suspend fun expect(issue: LibraryIssue, action: suspend () -> Any?) {
        try {
            action();
            throw AssertionError("Expected $issue")
        }
        catch (error: LibraryException) { assertEquals(issue, error.issue) }
    }
    private fun id(index: Int): String = "cue-entry-${index.toString().padStart(5, '0')}"
    private data class Setup(val keys: List<TrackKey>, val selected: TrackKey, val control: QueueEntryEntity,
        val playlists: RoomPlaylistRepository?)
    private companion object {
        const val TARGET = "cue-target"
        const val GENERATION = "cue-generation"
        const val LIST_ID = "cue-retained-playlist"
        val BOM_CUE = "\uFEFF" + RoomCueLibraryFixture.CUE
    }
}
