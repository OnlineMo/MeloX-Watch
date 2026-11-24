package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** 测试定义待云端编译/执行；真实应用曲库的扫描范围验证由集成层另验。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistFolderTest : PlaylistRoomFixture() {
    @Test(timeout = 30000L)
    fun completeFolderCreationKeepsSourceOrderDeduplicatesAcrossPagesAndCanCreateAnExplicitlyEmptyFolder() = runBlocking {
        val verifier = useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "authorized-node")
        val selected = List(204) { reference(it.toLong()) } + reference(3)
        val input = FrozenPlaylistInput("complete-folder-selection", 5L, selected.size.toLong())
        val synchronization = PlaylistFolderSynchronization(binding, fence, input)
        val operation = PlaylistMutationRequest("create-complete-folder", "complete-folder", 0L,
            PlaylistMutation.CreateFolder(" Complete folder ", synchronization))
        val commit = repository.apply(operation, listSource(input, selected))
        val created = checkNotNull(commit.snapshot)
        assertEquals(PlaylistOperationResult.CREATED, commit.result)
        assertEquals(204L, commit.addedCount)
        assertEquals(1L, commit.duplicateCount)
        assertEquals(PlaylistKind.FOLDER, created.kind)
        assertEquals(binding, created.folderBinding)
        assertEquals(selected.take(204), allMembers(created).map { it.reference })
        assertEquals(1, verifier.folderChecks)
        val emptyInput = FrozenPlaylistInput("explicit-empty-folder", 5L, 0L)
        val empty = createFolder("empty-folder", synchronization.copy(input = emptyInput),
            listSource(emptyInput, emptyList()), created.name)
        assertEquals(0L, empty.memberCount)
        assertEquals(binding, empty.folderBinding)
        assertNotEquals(created.playlistId, empty.playlistId)
        assertEquals(1L, database.playlistDao().receipt("create-empty-folder")?.sameNameCount)
        reopen()
        assertEquals(created, repository.snapshot(created.playlistId))
        assertEquals(empty, repository.snapshot(empty.playlistId))
        assertTrue(repository.apply(operation).wasReplay)
        integrity()
    }

    @Test(timeout = 30000L)
    fun synchronizationPreservesRetainedMemberIdsAndManualOrderThenAppendsNewMembersInFrozenSourceOrder() = runBlocking {
        useFenceVerifier()
        val originalFence = prepareFence()
        val binding = PlaylistFolderBinding(originalFence.sourceId, "music-node")
        val initial = List(105) { reference(it.toLong()) }
        val initialInput = FrozenPlaylistInput("folder-original", 1L, initial.size.toLong())
        var current = createFolder("synchronize", PlaylistFolderSynchronization(binding, originalFence, initialInput),
            listSource(initialInput, initial))
        val original = allMembers(current)
        current = checkNotNull(repository.apply(request("folder-manual-tail-first", current,
            PlaylistMutation.Move(original.last().memberId, 0L))).snapshot)
        val manual = allMembers(current)
        val ui = repository.openView(current.playlistId)
        val queue = repository.adoptView(ui.viewId)
        repository.closeView(ui.viewId)
        val fence = prepareFence(scanId = "next-complete-scan", catalogRevision = 8L, sourceRevision = 5L)
        val sourceOrder = List(205) { reference(204L - it) }
            .filter { it.trackKey?.trackId !in setOf("track-2", "track-4") } + reference(150)
        val input = FrozenPlaylistInput("folder-next", 2L, sourceOrder.size.toLong())
        val operation = request("synchronize-complete", current,
            PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input)))
        val commit = repository.apply(operation, listSource(input, sourceOrder))
        val changed = checkNotNull(commit.snapshot)
        assertEquals(PlaylistOperationResult.SYNCHRONIZED, commit.result)
        assertEquals(100L, commit.addedCount)
        assertEquals(2L, commit.removedCount)
        assertEquals(1L, commit.duplicateCount)
        assertEquals(203L, changed.memberCount)
        val members = allMembers(changed)
        val retained = manual.filter { it.reference.trackKey?.trackId !in setOf("track-2", "track-4") }
        assertEquals(retained.map { it.memberId }, members.take(retained.size).map { it.memberId })
        assertEquals(retained.map { it.reference }, members.take(retained.size).map { it.reference })
        assertEquals(sourceOrder.filter { checkNotNull(it.trackKey).trackId.removePrefix("track-").toLong() >= 105L }
            .distinctBy { it.trackKey }, members.drop(retained.size).map { it.reference })
        assertEquals((0L until 203L).toList(), members.map { it.ordinal })
        assertEquals(manual, allMembers(queue))
        repository.closeView(queue.viewId)
        reopen()
        assertEquals(changed, repository.snapshot(changed.playlistId))
        assertEquals(members, allMembers(changed))
        integrity()
    }

    @Test(timeout = 30000L)
    fun repeatedCompleteSynchronizationKeepsRevisionAndReplaysItsOriginalSemanticAfterLaterRenameAndDeletion() = runBlocking {
        val verifier = useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "repeat-node")
        val originalInput = FrozenPlaylistInput("repeat-original", 1L, 3L)
        val old = createFolder("repeat-folder", PlaylistFolderSynchronization(binding, fence, originalInput),
            generatedSource(originalInput))
        val members = allMembers(old)
        val before = repository.overview()
        val input = FrozenPlaylistInput("repeat-complete-input", 2L, 205L)
        var reads = 0
        val operation = request("repeat-synchronization", old,
            PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input)))
        val commit = repository.apply(operation, PlaylistPageSource { offset, limit ->
            reads += 1
            val count = minOf(limit.toLong(), input.totalCount - offset).toInt()
            PlaylistInputPage(input, offset, List(count) { reference((offset + it) % 3L).copy(titleHint = "New metadata hint") },
                offset + count == input.totalCount)
        })
        assertEquals(3, reads)
        assertEquals(2, verifier.folderChecks)
        assertEquals(PlaylistOperationResult.SYNCHRONIZED, commit.result)
        assertEquals(202L, commit.duplicateCount)
        assertEquals(old, commit.snapshot)
        assertEquals(before, repository.overview())
        assertEquals(members, allMembers(old))
        val renamed = checkNotNull(repository.apply(request("repeat-later-rename", old,
            PlaylistMutation.Rename("Later name"))).snapshot)
        val replay = repository.apply(operation)
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.SYNCHRONIZED, replay.result)
        assertEquals(commit.appliedRevision, replay.appliedRevision)
        assertEquals(renamed, replay.snapshot)
        assertEquals(2, verifier.folderChecks)
        repository.apply(request("repeat-later-delete", renamed, PlaylistMutation.Delete(true)))
        reopen()
        val deletedReplay = repository.apply(operation)
        assertTrue(deletedReplay.wasReplay)
        assertEquals(PlaylistOperationResult.SYNCHRONIZED, deletedReplay.result)
        assertEquals(202L, deletedReplay.duplicateCount)
        assertNull(deletedReplay.snapshot)
        assertEquals(3, reads)
        while (repository.maintain()) { /* 未发布代次按有界批次回收，receipt永久保留。 */ }
        integrity()
    }

    @Test(timeout = 30000L)
    fun incompleteChangedOrFailedFolderInputNeverPublishesADeletionOrASecondPartialMembership() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "input-errors-node")
        val originalInput = FrozenPlaylistInput("folder-kept-original", 1L, 3L)
        val old = createFolder("input-errors", PlaylistFolderSynchronization(binding, fence, originalInput),
            generatedSource(originalInput))
        val original = allMembers(old)
        val overview = repository.overview()
        val input = FrozenPlaylistInput("folder-complete-required", 2L, 205L)
        val issues = listOf(PlaylistIssue.SOURCE_CHANGED, PlaylistIssue.SOURCE_CHANGED, PlaylistIssue.INCOMPLETE,
            PlaylistIssue.INCOMPLETE, PlaylistIssue.SOURCE_UNAVAILABLE, PlaylistIssue.INVALID_INPUT)
        for ((mode, issue) in issues.withIndex()) {
            val operation = request("folder-input-error-$mode", old,
                PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input)))
            expectIssue(issue) {
                repository.apply(operation, PlaylistPageSource { offset, limit ->
                    val page = generatedSource(input).read(offset, limit)
                    if (offset == 0L) page else when (mode) {
                        0 -> page.copy(view = input.copy(id = "changed-input"))
                        1 -> page.copy(view = input.copy(revision = 3L))
                        2 -> page.copy(references = page.references.dropLast(1))
                        3 -> page.copy(endReached = true)
                        4 -> throw IOException("fixture incomplete directory source")
                        else -> page.copy(references = page.references.dropLast(1) + PlaylistReference(
                            placeholderId = "not-an-indexed-folder-track", issue = PlaylistReferenceIssue.UNMATCHED))
                    }
                })
            }
            assertEquals(old, repository.snapshot(old.playlistId))
            assertEquals(original, allMembers(old))
            assertEquals(overview, repository.overview())
            assertNull(database.playlistDao().receipt(operation.operationId))
            while (repository.maintain()) { /* 失败只回收本次暂存，不碰活动成员。 */ }
            assertEquals(3L, scalar("SELECT COUNT(*) FROM playlist_members"))
        }
        val empty = FrozenPlaylistInput("folder-false-empty", 3L, 0L)
        expectIssue(PlaylistIssue.INCOMPLETE) {
            repository.apply(request("folder-no-complete-empty", old,
                PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, empty))),
                PlaylistPageSource { _, _ -> PlaylistInputPage(empty, 0L, emptyList(), false) })
        }
        assertEquals(original, allMembers(old))
        integrity()
    }

    @Test(timeout = 30000L)
    fun everyPersistedFenceChangeAfterTheLastInputPageRejectsEmptySynchronizationInTheFinalRoomTransaction() = runBlocking {
        val verifier = useFenceVerifier()
        var fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "late-fence-node")
        val originalInput = FrozenPlaylistInput("late-fence-original", 1L, 2L)
        val old = createFolder("late-fence", PlaylistFolderSynchronization(binding, fence, originalInput),
            generatedSource(originalInput))
        val original = allMembers(old)
        val overview = repository.overview()
        val changes = listOf(
            "UPDATE playlist_fixture_library SET catalogRevision = catalogRevision + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_library SET rulesRevision = rulesRevision + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_sources SET sourceRevision = sourceRevision + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_sources SET authEpoch = authEpoch + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_sources SET authorization = 'REVOKED'" to PlaylistIssue.SOURCE_UNAVAILABLE,
            "UPDATE playlist_fixture_scans SET sourceId = 'another-source'" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_scans SET sourceRevision = sourceRevision + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_scans SET authEpoch = authEpoch + 1" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_scans SET rulesRevision = rulesRevision + 1" to PlaylistIssue.SOURCE_CHANGED,
            "DELETE FROM playlist_fixture_scans" to PlaylistIssue.SOURCE_CHANGED,
            "UPDATE playlist_fixture_scans SET status = 'FAILED'" to PlaylistIssue.INCOMPLETE,
            "UPDATE playlist_fixture_scans SET full = 0" to PlaylistIssue.INCOMPLETE,
        )
        for ((index, change) in changes.withIndex()) {
            fence = prepareFence()
            val input = FrozenPlaylistInput("late-fence-empty-$index", 2L, 0L)
            val operation = request("late-fence-reject-$index", old,
                PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input)))
            expectIssue(change.second) {
                repository.apply(operation, PlaylistPageSource { offset, limit ->
                    assertFalse(database.hasCurrentWriterTransaction())
                    assertEquals(0L, offset)
                    assertEquals(1, limit)
                    execute(change.first)
                    PlaylistInputPage(input, offset, emptyList(), true)
                })
            }
            assertEquals(index + 2, verifier.folderChecks)
            assertEquals(old, repository.snapshot(old.playlistId))
            assertEquals(original, allMembers(old))
            assertEquals(overview, repository.overview())
            assertNull(database.playlistDao().receipt(operation.operationId))
        }
        reopen()
        assertEquals(original, allMembers(old))
        integrity()
    }

    @Test(timeout = 30000L)
    fun missingVerifierAndIncompleteOrRevokedCreationLeaveNoHeadReceiptOrVisibleEmptyFolder() = runBlocking {
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "creation-fence-node")
        val input = FrozenPlaylistInput("creation-fence-input", 1L, 0L)
        val synchronization = PlaylistFolderSynchronization(binding, fence, input)
        val operation = PlaylistMutationRequest("creation-no-verifier", "creation-no-verifier", 0L,
            PlaylistMutation.CreateFolder("No verifier", synchronization))
        var reads = 0
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) {
            repository.apply(operation, PlaylistPageSource { _, _ -> reads += 1; throw AssertionError("no verifier must reject before reading") })
        }
        assertEquals(0, reads)
        val verifier = useFenceVerifier()
        for ((index, change) in listOf(
            "UPDATE playlist_fixture_scans SET status = 'CANCELLED'" to PlaylistIssue.INCOMPLETE,
            "UPDATE playlist_fixture_sources SET authorization = 'REVOKED'" to PlaylistIssue.SOURCE_UNAVAILABLE,
        ).withIndex()) {
            prepareFence()
            execute(change.first)
            val request = operation.copy(operationId = "creation-rejected-$index", playlistId = "creation-rejected-$index")
            expectIssue(change.second) { repository.apply(request, listSource(input, emptyList())) }
            assertNull(database.playlistDao().head(request.playlistId))
            assertNull(database.playlistDao().receipt(request.operationId))
            assertNull(repository.snapshot(request.playlistId))
        }
        assertEquals(2, verifier.folderChecks)
        assertEquals(PlaylistOverview(0L, 0L, 0L), repository.overview())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_members"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun confirmedRebindingRebuildsMembershipInNewSourceOrderAndKeepsTheOldQueueAndStablePlaylistIdentity() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "old-bound-node")
        val originalInput = FrozenPlaylistInput("rebind-original", 1L, 3L)
        val old = createFolder("rebind", PlaylistFolderSynchronization(binding, fence, originalInput), generatedSource(originalInput))
        val original = allMembers(old)
        val moved = checkNotNull(repository.apply(request("rebind-manual-order", old,
            PlaylistMutation.Move(original.last().memberId, 0L))).snapshot)
        val manual = allMembers(moved)
        val queue = repository.openView(moved.playlistId, PlaylistViewOwner.QUEUE)
        val nextFence = prepareFence(sourceId = "new-folder-source", scanId = "new-folder-scan", catalogRevision = 8L)
        val nextBinding = PlaylistFolderBinding(nextFence.sourceId, "new-bound-node", false)
        val selected = listOf(reference(2), reference(9), reference(2))
        val input = FrozenPlaylistInput("new-bound-members", 2L, selected.size.toLong())
        val synchronization = PlaylistFolderSynchronization(nextBinding, nextFence, input)
        val operation = request("confirmed-rebind", moved, PlaylistMutation.RebindFolder(synchronization, true))
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(operation.copy(operationId = "unconfirmed-rebind", mutation = PlaylistMutation.RebindFolder(synchronization, false)),
                PlaylistPageSource { _, _ -> throw AssertionError("unconfirmed rebind must not read") })
        }
        expectIssue(PlaylistIssue.CONFLICT) {
            repository.apply(operation.copy(operationId = "stale-rebind", expectedRevision = old.revision),
                PlaylistPageSource { _, _ -> throw AssertionError("stale rebind must not read") })
        }
        val commit = repository.apply(operation, listSource(input, selected))
        val rebound = checkNotNull(commit.snapshot)
        val members = allMembers(rebound)
        assertEquals(PlaylistOperationResult.REBOUND, commit.result)
        assertEquals(2L, commit.addedCount)
        assertEquals(3L, commit.removedCount)
        assertEquals(1L, commit.duplicateCount)
        assertEquals(nextBinding, rebound.folderBinding)
        assertEquals(old.playlistId, rebound.playlistId)
        assertEquals(old.createdEpochMs, rebound.createdEpochMs)
        assertEquals(listOf(reference(2), reference(9)), members.map { it.reference })
        assertTrue(members.none { member -> original.any { it.memberId == member.memberId } })
        assertEquals(manual, allMembers(queue))
        repository.closeView(queue.viewId)
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(operation.copy(mutation = PlaylistMutation.RebindFolder(synchronization, false)))
        }
        reopen()
        val replay = repository.apply(operation)
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.REBOUND, replay.result)
        assertEquals(rebound, replay.snapshot)
        assertEquals(members, allMembers(rebound))
        integrity()
    }

    @Test(timeout = 30000L)
    fun failedRebindingAndManualReferenceEditingLeaveTheOriginalBindingAndMembersIntact() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "kept-binding-node")
        val originalInput = FrozenPlaylistInput("kept-binding-input", 1L, 2L)
        val old = createFolder("kept-binding", PlaylistFolderSynchronization(binding, fence, originalInput), generatedSource(originalInput))
        val members = allMembers(old)
        val input = FrozenPlaylistInput("rebind-failing-empty", 2L, 0L)
        val synchronization = PlaylistFolderSynchronization(binding.copy(nodeId = "different-node"), fence, input)
        val operation = request("rebind-failing", old, PlaylistMutation.RebindFolder(synchronization, true))
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) {
            repository.apply(operation, PlaylistPageSource { offset, _ ->
                execute("UPDATE playlist_fixture_sources SET authorization = 'REVOKED'")
                PlaylistInputPage(input, offset, emptyList(), true)
            })
        }
        expectIssue(PlaylistIssue.AUTOMATIC_MEMBERS) {
            repository.apply(request("manual-folder-reference", old,
                PlaylistMutation.ReplaceReference(members.first().memberId, reference(99), true)))
        }
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(binding, repository.snapshot(old.playlistId)?.folderBinding)
        assertEquals(members, allMembers(old))
        assertNull(database.playlistDao().receipt(operation.operationId))
        integrity()
    }

    @Test(timeout = 30000L)
    fun cancellationDuringFolderInputKeepsWriterOwnershipUntilTheSourceReturnsAndNeverCommitsAPartialSynchronization() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "cancel-node")
        val originalInput = FrozenPlaylistInput("cancel-original", 1L, 3L)
        val old = createFolder("cancel-folder", PlaylistFolderSynchronization(binding, fence, originalInput), generatedSource(originalInput))
        val members = allMembers(old)
        val input = FrozenPlaylistInput("cancel-complete-input", 2L, 205L)
        val operation = request("cancel-sync", old,
            PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            repository.apply(operation, PlaylistPageSource { offset, limit ->
                if (offset == 100L) withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                generatedSource(input).read(offset, limit)
            })
        }
        try {
            withTimeout(10_000L) { entered.await() }
            job.cancel()
            assertFalse(job.isCompleted)
            expectIssue(PlaylistIssue.BUSY) {
                repository.apply(request("cancelled-sync-still-owns-writer", old, PlaylistMutation.Rename("Too early")))
            }
            assertEquals(old, repository.snapshot(old.playlistId))
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertEquals(members, allMembers(old))
        assertNull(database.playlistDao().receipt(operation.operationId))
        while (repository.maintain()) { /* 取消后的两个暂存代次都不可见并可分批回收。 */ }
        assertEquals(3L, scalar("SELECT COUNT(*) FROM playlist_members"))
        val retried = repository.apply(operation, generatedSource(input))
        assertEquals(PlaylistOperationResult.SYNCHRONIZED, retried.result)
        assertEquals(205L, retried.snapshot?.memberCount)
        assertEquals(members.map { it.memberId }, allMembers(checkNotNull(retried.snapshot)).take(3).map { it.memberId })
        integrity()
    }

    @Test(timeout = 180000L)
    fun full10005FolderSynchronizationUsesBoundedPagesAndPreservesAnExistingTailTrackAtItsManualPosition() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "full-folder-node")
        val originalInput = FrozenPlaylistInput("full-folder-original", 1L, 1L)
        val old = createFolder("full-folder", PlaylistFolderSynchronization(binding, fence, originalInput),
            listSource(originalInput, listOf(reference(10_004))))
        val original = allMembers(old).single()
        val input = FrozenPlaylistInput("full-folder-10005", 2L, 10_005L)
        var reads = 0
        var lastRead: Pair<Long, Int>? = null
        val commit = repository.apply(request("full-folder-sync", old,
            PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input))),
            PlaylistPageSource { offset, limit ->
                assertTrue(limit in 1..100)
                reads += 1
                lastRead = offset to limit
                generatedSource(input).read(offset, limit)
            })
        val changed = checkNotNull(commit.snapshot)
        assertEquals(101, reads)
        assertEquals(10_000L to 5, lastRead)
        assertEquals(10_005L, changed.memberCount)
        assertEquals(10_004L, commit.addedCount)
        val view = repository.openView(changed.playlistId)
        assertEquals(original, repository.page(view).members.first())
        val tail = repository.page(view, 10_000L)
        assertEquals(5, tail.members.size)
        assertEquals("track-10003", tail.members.last().reference.trackKey?.trackId)
        assertNull(tail.nextOrdinal)
        repository.closeView(view.viewId)
        while (repository.maintain()) { /* 每次回收至多100行，不保留完整输入于内存。 */ }
        assertEquals(10_005L, scalar("SELECT COUNT(*) FROM playlist_members"))
        reopen()
        val restored = repository.openView(changed.playlistId)
        assertEquals(original, repository.page(restored).members.first())
        assertEquals(tail.members, repository.page(restored, 10_000L).members)
        repository.closeView(restored.viewId)
        integrity()
    }

    @Test(timeout = 30000L)
    fun aRealDeferredForeignKeyFailureAfterEmptySynchronizationPublicationRollsBackEveryRelationshipAndAllowsExactRetry() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val binding = PlaylistFolderBinding(fence.sourceId, "commit-node")
        val originalInput = FrozenPlaylistInput("commit-folder-original", 1L, 3L)
        val old = createFolder("commit-folder", PlaylistFolderSynchronization(binding, fence, originalInput), generatedSource(originalInput))
        val members = allMembers(old)
        val overview = repository.overview()
        execute("""CREATE TABLE playlist_folder_commit_guard(id TEXT NOT NULL PRIMARY KEY, receiptId TEXT NOT NULL,
            FOREIGN KEY(receiptId) REFERENCES playlist_receipts(operationId) DEFERRABLE INITIALLY DEFERRED)""")
        execute("""CREATE TRIGGER reject_folder_commit AFTER INSERT ON playlist_receipts BEGIN
            INSERT INTO playlist_folder_commit_guard(id, receiptId) VALUES(NEW.operationId, 'absent-receipt'); END""")
        val input = FrozenPlaylistInput("commit-folder-empty", 2L, 0L)
        val operation = request("commit-folder-failure", old,
            PlaylistMutation.SynchronizeFolder(PlaylistFolderSynchronization(binding, fence, input)))
        expectIssue(PlaylistIssue.RECOVERY_REQUIRED) { repository.apply(operation, listSource(input, emptyList())) }
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(old))
        assertEquals(overview, repository.overview())
        assertNull(database.playlistDao().receipt(operation.operationId))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_folder_commit_guard"))
        execute("DROP TRIGGER reject_folder_commit")
        execute("DROP TABLE playlist_folder_commit_guard")
        val commit = repository.apply(operation, listSource(input, emptyList()))
        assertEquals(PlaylistOperationResult.SYNCHRONIZED, commit.result)
        assertEquals(3L, commit.removedCount)
        assertEquals(0L, commit.snapshot?.memberCount)
        assertEquals(binding, commit.snapshot?.folderBinding)
        assertTrue(repository.apply(operation).wasReplay)
        integrity()
    }

    @Test(timeout = 30000L)
    fun aSavedFolderReceiptRejectsChangedBindingEverySourceFenceFieldAndEveryFrozenInputFieldBeforeReading() = runBlocking {
        useFenceVerifier()
        val fence = prepareFence()
        val input = FrozenPlaylistInput("fingerprint-folder-input", 1L, 2L)
        val synchronization = PlaylistFolderSynchronization(PlaylistFolderBinding(fence.sourceId, "fingerprint-node"), fence, input)
        val operation = PlaylistMutationRequest("fingerprint-folder-create", "fingerprint-folder", 0L,
            PlaylistMutation.CreateFolder("Fingerprint folder", synchronization))
        val first = repository.apply(operation, generatedSource(input))
        val variants = listOf(
            synchronization.copy(binding = synchronization.binding.copy(nodeId = "different-node")),
            synchronization.copy(binding = synchronization.binding.copy(subtree = false)),
            synchronization.copy(binding = synchronization.binding.copy(sourceId = "different-source"),
                fence = fence.copy(sourceId = "different-source")),
            synchronization.copy(fence = fence.copy(catalogRevision = fence.catalogRevision + 1L)),
            synchronization.copy(fence = fence.copy(rulesRevision = fence.rulesRevision + 1L)),
            synchronization.copy(fence = fence.copy(sourceRevision = fence.sourceRevision + 1L)),
            synchronization.copy(fence = fence.copy(authEpoch = fence.authEpoch + 1L)),
            synchronization.copy(fence = fence.copy(scanId = "different-scan")),
            synchronization.copy(input = input.copy(id = "different-input")),
            synchronization.copy(input = input.copy(revision = input.revision + 1L)),
            synchronization.copy(input = input.copy(totalCount = input.totalCount + 1L)),
        )
        for (variant in variants) {
            expectIssue(PlaylistIssue.INVALID_INPUT) {
                repository.apply(operation.copy(mutation = PlaylistMutation.CreateFolder("Fingerprint folder", variant)),
                    PlaylistPageSource { _, _ -> throw AssertionError("changed receipt fingerprint must reject before reading") })
            }
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(operation.copy(expectedLibraryRevision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)))
        }
        assertEquals(first.snapshot, repository.snapshot(operation.playlistId))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        assertTrue(repository.apply(operation).wasReplay)
        integrity()
    }
}
