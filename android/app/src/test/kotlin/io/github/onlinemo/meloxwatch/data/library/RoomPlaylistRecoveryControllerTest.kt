package io.github.onlinemo.meloxwatch.data.library

import android.content.Intent
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.allowsFileReadValidation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistDao
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMemberEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReceiptEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistPreviewRevision
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 真实Room提交、来源围栏、未知结果及独立lease；定义不代表云测试或设备验收通过。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistRecoveryControllerTest : PlaylistRecoveryRoomFixture() {
    @Test(timeout = 30_000L)
    fun explicitSelectedKeyAndActualTitleReplaceOnlyTheIntendedReferenceAndKeepMemberIdentityAndOrder() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val before = members(old)
        val libraryBefore = library.overview()
        val playlistBefore = playlists.overview()
        val preview = controller.preview(target, replacement)
        assertEquals(target, preview.target)
        assertEquals(replacement, preview.replacement)
        assertNotEquals(sameNamedDecoy, preview.replacement)
        assertEquals("Selected actual title", preview.replacementTitle)
        assertEquals(PlaylistPreviewRevision(libraryBefore.revision, libraryBefore.rulesRevision), preview.revision)
        assertEquals(1, database.libraryDao().views().size)
        assertEquals(old, playlists.snapshot(old.playlistId))
        val commit = controller.confirm("confirmed-repair", preview.previewId, confirmed = true)
        val current = checkNotNull(commit.snapshot)
        val after = members(current)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, commit.result)
        assertFalse(commit.wasReplay)
        assertEquals(old.revision + 1L, commit.appliedRevision)
        assertEquals(before.map { it.memberId }, after.map { it.memberId })
        assertEquals(before.map { it.ordinal }, after.map { it.ordinal })
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
        assertEquals(replacement, after[1].reference.trackKey)
        assertEquals("Selected actual title", after[1].reference.titleHint)
        assertEquals("Actual artist", after[1].reference.artistHint)
        assertEquals("Actual album", after[1].reference.albumHint)
        assertEquals(120_000L, after[1].reference.durationMs)
        assertNull(after[1].reference.placeholderId)
        assertNull(after[1].reference.descriptionHint)
        assertEquals(libraryBefore, library.overview())
        assertEquals(playlistBefore.revision + 1L, playlists.overview().revision)
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM queue_generations"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun cancelOrAClosedUnconfirmedPreviewCannotWriteAndASecondPreviewCannotAcquireAnotherLease() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val preview = controller.preview(target, replacement)
        expectIssue(PlaylistIssue.BUSY) { controller.preview(target, sameNamedDecoy) }
        assertEquals(1, database.libraryDao().views().size)
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm("cancel-repair", preview.previewId, confirmed = false) }
        assertNull(database.playlistDao().receipt("cancel-repair"))
        assertEquals(old, playlists.snapshot(old.playlistId))
        controller.closePreview(preview.previewId)
        controller.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { controller.confirm("cancel-repair", preview.previewId, confirmed = true) }
        assertEquals(old, playlists.snapshot(old.playlistId))
        val fresh = controller.preview(target, replacement)
        assertNotEquals(preview.previewId, fresh.previewId)
        controller.closePreview(fresh.previewId)
    }

    @Test(timeout = 30_000L)
    fun forgedMemberOwnerUnsupportedAutomaticListsAndTheAuthorizationActionCannotBecomeReferenceEdits() = runBlocking {
        val target = target()
        val invalidTargets = listOf(
            target.copy(member = target.member.copy(memberId = "other-member")) to PlaylistIssue.SOURCE_CHANGED,
            target.copy(member = target.member.copy(ordinal = 0L)) to PlaylistIssue.SOURCE_CHANGED,
            target.copy(view = target.view.copy(owner = PlaylistViewOwner.QUEUE)) to PlaylistIssue.INVALID_INPUT,
            target.copy(view = target.view.copy(snapshot = target.view.snapshot.copy(kind = PlaylistKind.FOLDER))) to PlaylistIssue.AUTOMATIC_MEMBERS,
            target.copy(view = target.view.copy(snapshot = target.view.snapshot.copy(kind = PlaylistKind.FAVORITES))) to PlaylistIssue.AUTOMATIC_MEMBERS,
            target.copy(action = PlaylistRecoveryAction.REAUTHORIZE) to PlaylistIssue.INVALID_INPUT,
        )
        for ((invalid, expected) in invalidTargets) expectIssue(expected) { controller.preview(invalid, replacement) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.preview(target, TrackKey("online-provider", "online-key")) }
        expectIssue(PlaylistIssue.NOT_FOUND) { controller.preview(target, TrackKey(TrackKey.LOCAL_PROVIDER, "not-indexed")) }
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(target.view.snapshot, playlists.snapshot(target.view.snapshot.playlistId))
        val oldMembers = members(target.view.snapshot)
        val existingOperation = "create-${target.view.snapshot.playlistId}"
        val originalReceipt = checkNotNull(database.playlistDao().receipt(existingOperation))
        val preview = controller.preview(target, replacement)
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm(existingOperation, preview.previewId, true) }
        assertEquals(originalReceipt, database.playlistDao().receipt(existingOperation))
        assertEquals(target.view.snapshot, playlists.snapshot(target.view.snapshot.playlistId))
        assertEquals(oldMembers, members(target.view.snapshot))
        controller.closePreview(preview.previewId)
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm(existingOperation, preview.previewId, true) }
        assertEquals(originalReceipt, database.playlistDao().receipt(existingOperation))
        assertEquals(target.view.snapshot, playlists.snapshot(target.view.snapshot.playlistId))
        assertEquals(oldMembers, members(target.view.snapshot))
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun anExistingDuplicateTargetAndAConcurrentAcceptedRenameKeepTheOriginalMembersAndRejectTheOldRevision() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val before = members(old)
        val duplicate = controller.preview(target, sameNamedDecoy)
        expectIssue(PlaylistIssue.DUPLICATE_REFERENCE) { controller.confirm("duplicate-repair", duplicate.previewId, true) }
        assertEquals(before, members(old))
        assertNull(database.playlistDao().receipt("duplicate-repair"))
        controller.closePreview(duplicate.previewId)

        var armed = false
        var renamed = old
        val realDao = database.playlistDao()
        val gated = object : PlaylistDao by realDao {
            override suspend fun membersPage(generation: String, first: Long, limit: Int): List<PlaylistMemberEntity> {
                val rows = realDao.membersPage(generation, first, limit)
                if (armed && !database.hasCurrentWriterTransaction()) {
                    armed = false
                    renamed = checkNotNull(repository().apply(PlaylistMutationRequest("winning-rename", old.playlistId,
                        old.revision, PlaylistMutation.Rename("Later accepted name"))).snapshot)
                }
                return rows
            }
        }
        playlists = repository(gated)
        controller = recovery()
        val preview = controller.preview(target, replacement)
        armed = true
        expectIssue(PlaylistIssue.CONFLICT) { controller.confirm("stale-repair", preview.previewId, true) }
        assertFalse(armed)
        assertEquals(renamed, playlists.snapshot(old.playlistId))
        assertEquals(before, members(renamed))
        assertNull(database.playlistDao().receipt("stale-repair"))
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm("new-operation-on-old-preview", preview.previewId, true) }
        controller.closePreview(preview.previewId)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun finalSameRoomFencesReadRealLibraryRulesSourceAuthorizationAndFullScanRowsAfterTheLastCopyPage() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val before = members(old)
        val dao = database.libraryDao()
        val baselineMeta = checkNotNull(dao.meta())
        val baselineSource = checkNotNull(dao.source(source.spec.sourceId))
        val baselineScan = checkNotNull(dao.scan(checkNotNull(baselineSource.latestScanId)))
        val changes = listOf(
            FenceChange(PlaylistIssue.SOURCE_CHANGED) { dao.updateMeta(baselineMeta.copy(revision = baselineMeta.revision + 1L)); Unit },
            FenceChange(PlaylistIssue.SOURCE_CHANGED) { dao.updateMeta(baselineMeta.copy(rulesRevision = baselineMeta.rulesRevision + 1L)); Unit },
            FenceChange(PlaylistIssue.SOURCE_CHANGED) { dao.putSource(baselineSource.copy(revision = baselineSource.revision + 1L)) },
            FenceChange(PlaylistIssue.SOURCE_CHANGED) { dao.putSource(baselineSource.copy(authEpoch = baselineSource.authEpoch + 1L)) },
            FenceChange(PlaylistIssue.SOURCE_CHANGED) { dao.putSource(baselineSource.copy(activeScanId = "active-new-scan")) },
            FenceChange(PlaylistIssue.SOURCE_CHANGED) { dao.putSource(baselineSource.copy(latestScanId = "later-complete-scan")) },
            FenceChange(PlaylistIssue.SOURCE_UNAVAILABLE) {
                database.withTestWriterSql { execute("UPDATE local_grants SET readCapability = 'UNAVAILABLE'") }
            },
            FenceChange(PlaylistIssue.INCOMPLETE) { dao.updateScan(baselineScan.copy(mode = ScanMode.INCREMENTAL.name)); Unit },
            FenceChange(PlaylistIssue.INCOMPLETE) { dao.updateScan(baselineScan.copy(phase = ScanPhase.FAILED.name)); Unit },
            FenceChange(PlaylistIssue.INCOMPLETE) { dao.updateScan(baselineScan.copy(enumerationComplete = false)); Unit },
            FenceChange(PlaylistIssue.INCOMPLETE) {
                dao.putSource(baselineSource.copy(absenceScanSequence = baselineScan.sequence + 1L))
            },
        )
        val realPlaylistDao = database.playlistDao()
        for ((index, change) in changes.withIndex()) {
            var armed = false
            val gated = object : PlaylistDao by realPlaylistDao {
                override suspend fun membersPage(generation: String, first: Long, limit: Int): List<PlaylistMemberEntity> {
                    val rows = realPlaylistDao.membersPage(generation, first, limit)
                    if (armed && !database.hasCurrentWriterTransaction()) {
                        armed = false
                        database.withPreservedTransaction { change.mutate() }
                    }
                    return rows
                }
            }
            playlists = repository(gated)
            controller = recovery()
            val preview = controller.preview(target, replacement)
            armed = true
            expectIssue(change.issue) { controller.confirm("raced-repair-$index", preview.previewId, true) }
            assertFalse(armed)
            assertEquals(old, playlists.snapshot(old.playlistId))
            assertEquals(before, members(old))
            assertNull(realPlaylistDao.receipt("raced-repair-$index"))
            controller.closePreview(preview.previewId)
            database.withPreservedTransaction {
                dao.updateMeta(baselineMeta)
                dao.putSource(baselineSource)
                dao.updateScan(baselineScan)
                database.withTestWriterSql { execute("UPDATE local_grants SET readCapability = 'AVAILABLE'") }
            }
        }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        assertEquals(0, dao.views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun anUnavailableChosenSourceIsExplainedAndDoesNotUseAnotherFileWithTheSameName() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val currentSource = checkNotNull(library.source(source.spec.sourceId))
        library.setSourceIssue(currentSource.spec.sourceId, currentSource.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { controller.preview(target, replacement) }
        assertEquals(old, playlists.snapshot(old.playlistId))
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
    }

    @Test(timeout = 30_000L)
    fun cancellationBeforeCommitKeepsTheOriginalFrozenOperationForAnExplicitRetryAndPreventsASecondCommand() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val realDao = database.playlistDao()
        val gated = object : PlaylistDao by realDao {
            override suspend fun membersPage(generation: String, first: Long, limit: Int): List<PlaylistMemberEntity> {
                val rows = realDao.membersPage(generation, first, limit)
                if (armed && !database.hasCurrentWriterTransaction()) {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                return rows
            }
        }
        playlists = repository(gated)
        controller = recovery()
        val preview = controller.preview(target, replacement)
        armed = true
        val job = launch { controller.confirm("retry-original-repair", preview.previewId, true) }
        try {
            withTimeout(10_000L) { entered.await() }
            expectIssue(PlaylistIssue.BUSY) { controller.confirm("second-concurrent-command", preview.previewId, true) }
            withTimeout(10_000L) { job.cancelAndJoin() }
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertEquals(old, playlists.snapshot(old.playlistId))
        assertNull(database.playlistDao().receipt("retry-original-repair"))
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm("new-operation-on-unknown-result", preview.previewId, true) }
        val accepted = controller.confirm("retry-original-repair", preview.previewId, true)
        assertFalse(accepted.wasReplay)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, accepted.result)
        assertEquals(replacement, members(checkNotNull(accepted.snapshot))[1].reference.trackKey)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun cancellationAfterTheNoncancellableCommitAndCloseStillAllowsTheSameReceiptToReplayWithoutItsSource() = runBlocking {
        val target = target()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val realDao = database.playlistDao()
        val gated = object : PlaylistDao by realDao {
            override suspend fun insertReceipt(receipt: PlaylistReceiptEntity) {
                realDao.insertReceipt(receipt)
                if (receipt.operationId == "accepted-before-cancellation") {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        playlists = repository(gated)
        controller = recovery()
        val preview = controller.preview(target, replacement)
        val job = launch { controller.confirm("accepted-before-cancellation", preview.previewId, true) }
        try {
            withTimeout(10_000L) { entered.await() }
            job.cancel()
            assertFalse(job.isCompleted)
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertTrue(job.isCancelled)
        assertTrue(database.playlistDao().receipt("accepted-before-cancellation") != null)
        controller.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        val currentSource = checkNotNull(library.source(source.spec.sourceId))
        library.setSourceIssue(currentSource.spec.sourceId, currentSource.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        elapsed += LibraryLimits.VIEW_LEASE_MS + 1L
        val replay = controller.confirm("accepted-before-cancellation", preview.previewId, true)
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, replay.result)
        assertEquals(target.view.snapshot.revision + 1L, replay.appliedRevision)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun cancelledPreviewAcquisitionHandsOffTheRealLeaseBeforeCancellationAndClosesItWithoutWriting() = runBlocking {
        val target = target()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val port = object : LocalLibraryRepository by library {
            override suspend fun openView(query: LibraryQuery, sort: LibrarySort, owner: LibraryViewOwner): LibraryViewToken {
                val view = library.openView(query, sort, owner)
                entered.complete(Unit)
                release.await()
                return view
            }
        }
        controller = recovery(libraryPort = port)
        val job = launch { controller.preview(target, replacement) }
        try {
            withTimeout(10_000L) { entered.await() }
            job.cancel()
            assertFalse(job.isCompleted)
            assertEquals(1, database.libraryDao().views().size)
            expectIssue(PlaylistIssue.BUSY) { controller.preview(target, sameNamedDecoy) }
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertTrue(job.isCancelled)
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(target.view.snapshot, playlists.snapshot(target.view.snapshot.playlistId))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
    }

    @Test(timeout = 30_000L)
    fun cleanupFailureCannotUndoAcceptedSuccessOrBlockSourceIndependentReplayAndNewPreviewMustReleaseTheSameLease() = runBlocking {
        val target = target()
        var failClose = true
        var closeCalls = 0
        val port = object : LocalLibraryRepository by library {
            override suspend fun closeView(viewId: String) {
                closeCalls += 1
                if (failClose) throw LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
                library.closeView(viewId)
            }
        }
        controller = recovery(libraryPort = port, closeLibraryView = port::closeView)
        val preview = controller.preview(target, replacement)
        val accepted = controller.confirm("accepted-with-cleanup-error", preview.previewId, true)
        assertFalse(accepted.wasReplay)
        assertEquals(1, closeCalls)
        assertEquals(1, database.libraryDao().views().size)
        assertTrue(controller.confirm("accepted-with-cleanup-error", preview.previewId, true).wasReplay)
        assertEquals(1, closeCalls)
        expectIssue(PlaylistIssue.BUSY) { controller.preview(target, sameNamedDecoy) }
        assertEquals(2, closeCalls)
        assertEquals(1, database.libraryDao().views().size)
        failClose = false
        controller.closePreview(preview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        val nextTarget = targetFrom(checkNotNull(accepted.snapshot))
        val next = controller.preview(nextTarget, replacement)
        assertEquals(1, database.libraryDao().views().size)
        controller.closePreview(next.previewId)
        assertEquals(0, database.libraryDao().views().size)
    }

    @Test(timeout = 30_000L)
    fun previewExpiryRequiresFreshReviewButTheLastAcceptedReceiptCannotRestoreALaterDeletedPlaylist() = runBlocking {
        val target = target()
        val port = object : LocalLibraryRepository by library {
            override suspend fun locate(view: LibraryViewToken, trackKey: TrackKey): LibraryLocation? {
                val selected = library.locate(view, trackKey)
                elapsed += 1_000L
                return selected
            }
        }
        controller = recovery(libraryPort = port)
        val expired = controller.preview(target, replacement)
        val actualLease = database.libraryDao().views().single()
        assertEquals(elapsed - 1_000L + LibraryLimits.VIEW_LEASE_MS, actualLease.expiresElapsedMs)
        elapsed = actualLease.expiresElapsedMs
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { controller.confirm("expired-new-repair", expired.previewId, true) }
        assertNull(database.playlistDao().receipt("expired-new-repair"))
        controller.closePreview(expired.previewId)
        val freshTarget = targetFrom(target.view.snapshot)
        val fresh = controller.preview(freshTarget, replacement)
        val accepted = controller.confirm("last-accepted-repair", fresh.previewId, true)
        val repaired = checkNotNull(accepted.snapshot)
        val renamed = checkNotNull(playlists.apply(PlaylistMutationRequest("later-rename", repaired.playlistId,
            repaired.revision, PlaylistMutation.Rename("Later name"))).snapshot)
        val replayAfterRename = controller.confirm("last-accepted-repair", fresh.previewId, true)
        assertTrue(replayAfterRename.wasReplay)
        assertEquals(renamed, replayAfterRename.snapshot)
        assertEquals(accepted.appliedRevision, replayAfterRename.appliedRevision)
        playlists.apply(PlaylistMutationRequest("later-delete", renamed.playlistId, renamed.revision, PlaylistMutation.Delete(true)))
        val replayAfterDelete = controller.confirm("last-accepted-repair", fresh.previewId, true)
        assertTrue(replayAfterDelete.wasReplay)
        assertNull(replayAfterDelete.snapshot)
        assertNull(playlists.snapshot(renamed.playlistId))
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm("another-operation", fresh.previewId, true) }
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm("last-accepted-repair", fresh.previewId, false) }
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun processRecreationKeepsTheDurableReceiptButCannotRestoreAConfirmedRuntimePreviewOrInventANewWrite() = runBlocking {
        val target = target()
        val preview = controller.preview(target, replacement)
        val accepted = controller.confirm("durable-process-repair", preview.previewId, true)
        val receipt = checkNotNull(database.playlistDao().receipt("durable-process-repair"))
        reopen()
        assertEquals(receipt, database.playlistDao().receipt("durable-process-repair"))
        assertEquals(accepted.snapshot, playlists.snapshot(target.view.snapshot.playlistId))
        expectIssue(PlaylistIssue.VIEW_EXPIRED) { controller.confirm("durable-process-repair", preview.previewId, true) }
        val reviewed = targetFrom(checkNotNull(accepted.snapshot))
        val newPreview = controller.preview(reviewed, replacement)
        expectIssue(PlaylistIssue.INVALID_INPUT) { controller.confirm("fresh-explicit-review", newPreview.previewId, false) }
        assertNull(database.playlistDao().receipt("fresh-explicit-review"))
        assertEquals(accepted.snapshot, playlists.snapshot(target.view.snapshot.playlistId))
        controller.closePreview(newPreview.previewId)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun aLiveReadDenialBeforePreviewCannotUseAnOlderAvailableGrantObservation() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val before = members(old)
        val dao = database.libraryDao()
        val grant = checkNotNull(dao.grant(source.grantId))
        database.withPreservedTransaction {
            database.withTestWriterSql {
                execute("UPDATE local_grants SET readCapability = ? WHERE grantId = ?",
                    ObservedCapability.UNAVAILABLE.name, grant.grantId)
            }
        }
        val observed = checkNotNull(library.source(source.spec.sourceId))
        assertEquals(ObservedCapability.UNAVAILABLE, observed.grant.read)
        assertFalse(observed.grant.allowsFileReadValidation(observed.spec.kind))
        assertEquals(grant.observation, checkNotNull(dao.grant(grant.grantId)).observation)
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { controller.preview(target, replacement) }
        assertEquals(old, playlists.snapshot(old.playlistId))
        assertEquals(before, members(old))
        assertEquals(0, dao.views().size)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun liveUnknownTreeReadRemainsUnknownOnTheValidationDescriptorAndExplicitRecovery() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val before = members(old)
        val dao = database.libraryDao()
        val grant = checkNotNull(dao.grant(source.grantId))
        database.withPreservedTransaction {
            database.withTestWriterSql {
                execute("UPDATE local_grants SET readCapability = ? WHERE grantId = ?",
                    ObservedCapability.UNKNOWN.name, grant.grantId)
            }
        }
        val observed = checkNotNull(library.source(source.spec.sourceId))
        assertEquals(ObservedCapability.UNKNOWN, observed.grant.read)
        assertEquals(ObservedCapability.AVAILABLE, observed.grant.enumerate)
        assertTrue(observed.grant.allowsFileReadValidation(observed.spec.kind))
        val descriptor = library.resolveLocalRead(replacement)
        assertEquals(observed.spec, descriptor.source.spec)
        assertEquals(ObservedCapability.UNKNOWN, descriptor.source.grant.read)
        assertTrue(descriptor.source.grant.allowsFileReadValidation(descriptor.source.spec.kind))
        val libraryBefore = library.overview()
        val preview = controller.preview(target, replacement)
        val accepted = controller.confirm("unknown-live-repair", preview.previewId, true)
        val repaired = checkNotNull(accepted.snapshot)
        val after = members(repaired)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, accepted.result)
        assertFalse(accepted.wasReplay)
        assertEquals(before.map { it.memberId }, after.map { it.memberId })
        assertEquals(before.map { it.ordinal }, after.map { it.ordinal })
        assertEquals(replacement, after[1].reference.trackKey)
        assertEquals(libraryBefore, library.overview())
        assertEquals(grant.observation, checkNotNull(dao.grant(grant.grantId)).observation)
        assertEquals(ObservedCapability.UNKNOWN, checkNotNull(library.source(source.spec.sourceId)).grant.read)
        assertEquals(0, dao.views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun liveGrantFieldsKeepUnflattenedFactsAndInvalidReadOrFlagsCannotUpgradeCapabilities() = runBlocking {
        val target = target()
        val old = target.view.snapshot
        val before = members(old)
        val dao = database.libraryDao()
        val grant = checkNotNull(dao.grant(source.grantId))
        val jsonFacts = LocalGrantObservation(
            read = ObservedCapability.AVAILABLE,
            enumerate = ObservedCapability.AVAILABLE,
            persistedRead = ObservedCapability.UNKNOWN,
            persistedWrite = ObservedCapability.UNAVAILABLE,
            offlineRead = ObservedCapability.UNKNOWN,
            write = ObservedCapability.UNAVAILABLE,
            delete = ObservedCapability.UNKNOWN,
            rename = ObservedCapability.UNAVAILABLE,
            move = ObservedCapability.UNKNOWN,
            declaredWrite = true,
            declaredDelete = false,
            declaredRename = null,
            declaredMove = false,
            appOwned = true,
            returnedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            acceptedPersistedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            persistenceAttempted = true,
            persistenceRejected = true,
        )
        val json = LibraryCodec.grant(jsonFacts)
        for (read in listOf(ObservedCapability.UNAVAILABLE.name, "INVALID_READ_CAPABILITY")) {
            database.withPreservedTransaction {
                database.withTestWriterSql {
                    execute("UPDATE local_grants SET observation = ?, readCapability = ?, " +
                        "acceptedPersistedFlags = ?, appOwned = ? WHERE grantId = ?",
                        json, read, -1, false, grant.grantId)
                }
            }
            val observed = checkNotNull(library.source(source.spec.sourceId))
            assertEquals(jsonFacts.copy(read = ObservedCapability.UNAVAILABLE,
                acceptedPersistedFlags = 0, appOwned = false), observed.grant)
            assertEquals(source.spec, observed.spec)
            assertEquals(source.grantId, observed.grantId)
            assertEquals(source.revision, observed.revision)
            assertEquals(source.authEpoch, observed.authEpoch)
            assertFalse(observed.grant.allowsFileReadValidation(observed.spec.kind))
            assertEquals(json, checkNotNull(dao.grant(grant.grantId)).observation)
            expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) { controller.preview(target, replacement) }
            assertEquals(old, playlists.snapshot(old.playlistId))
            assertEquals(before, members(old))
            assertEquals(0, dao.views().size)
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        }
        integrity()
    }

    private data class FenceChange(val issue: PlaylistIssue, val mutate: suspend () -> Unit)
}
