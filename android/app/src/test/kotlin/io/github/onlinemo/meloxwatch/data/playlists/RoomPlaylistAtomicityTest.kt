package io.github.onlinemo.meloxwatch.data.playlists

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistAtomicityTest : PlaylistRoomFixture() {
    @Test(timeout = 30000L)
    fun changedMissingOrPrematureSourcePagesAndIoFailureCannotPublishAnyPartOfAnAddition() = runBlocking {
        val old = create("source-errors", references = listOf(reference(900), reference(901)))
        val original = allMembers(old)
        val overview = repository.overview()
        val input = FrozenPlaylistInput("complete-selection", 8L, 205L)
        val normal = generatedSource(input)
        val modes = listOf(PlaylistIssue.SOURCE_CHANGED, PlaylistIssue.SOURCE_CHANGED, PlaylistIssue.INCOMPLETE,
            PlaylistIssue.INCOMPLETE, PlaylistIssue.SOURCE_UNAVAILABLE)
        for ((mode, issue) in modes.withIndex()) {
            var calls = 0
            val source = PlaylistPageSource { offset, limit ->
                assertFalse(database.inTransaction())
                calls += 1
                val page = normal.read(offset, limit)
                if (offset == 0L) page else when (mode) {
                    0 -> page.copy(view = input.copy(revision = 9L))
                    1 -> page.copy(offset = offset + 1L)
                    2 -> page.copy(references = page.references.dropLast(1))
                    3 -> page.copy(endReached = true)
                    else -> throw IOException("fixture source unavailable")
                }
            }
            val operation = request("failed-source-$mode", old, PlaylistMutation.Add(input))
            expectIssue(issue) { repository.apply(operation, source) }
            assertEquals(2, calls)
            assertEquals(old, repository.snapshot(old.playlistId))
            assertEquals(original, allMembers(old))
            assertEquals(overview, repository.overview())
            assertNull(database.playlistDao().receipt(operation.operationId))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        }
        reopen()
        assertEquals(original, allMembers(old))
        integrity()
    }

    @Test(timeout = 30000L)
    fun anEmptyExplicitInputStillRequiresOneCompleteEndPageAndSourcePairingIsValidatedBeforeStaging() = runBlocking {
        val empty = FrozenPlaylistInput("empty-input", 0L, 0L)
        var calls = 0
        val source = PlaylistPageSource { offset, limit ->
            assertFalse(database.inTransaction())
            assertEquals(0L, offset)
            assertEquals(1, limit)
            calls += 1
            PlaylistInputPage(empty, offset, emptyList(), true)
        }
        val operation = PlaylistMutationRequest("empty-create", "empty", 0L, PlaylistMutation.Create("Empty", empty))
        expectIssue(PlaylistIssue.INVALID_INPUT) { repository.apply(operation) }
        val created = repository.apply(operation, source)
        assertEquals(1, calls)
        assertEquals(0L, created.snapshot?.memberCount)
        assertTrue(repository.apply(operation).wasReplay)
        assertEquals(1, calls)
        for ((index, page) in listOf(PlaylistInputPage(empty, 0L, emptyList(), false),
            PlaylistInputPage(empty, 0L, listOf(reference(1)), true)).withIndex()) {
            expectIssue(PlaylistIssue.INCOMPLETE) {
                repository.apply(PlaylistMutationRequest("invalid-empty-$index", "invalid-empty-$index", 0L,
                    PlaylistMutation.Create("Empty", empty)), PlaylistPageSource { _, _ -> page })
            }
            assertNull(repository.snapshot("invalid-empty-$index"))
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(PlaylistMutationRequest("source-without-input", "unexpected-source", 0L,
                PlaylistMutation.Create("No input")), source)
        }
        assertEquals(1L, repository.overview().playlistCount)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
    }

    @Test(timeout = 30000L)
    fun cancellationLeavesNoPublishedHalfListAndTheSameOperationCanRetryBeforeAllAbandonedRowsAreReclaimed() = runBlocking {
        val input = FrozenPlaylistInput("retry-input", 2L, 401L)
        val operation = PlaylistMutationRequest("retry-after-cancel", "retry", 0L, PlaylistMutation.Create("Retry", input))
        val reached = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val normal = generatedSource(input)
        val job = launch {
            repository.apply(operation, PlaylistPageSource { offset, limit ->
                if (offset == 300L) {
                    reached.complete(Unit)
                    blocked.await()
                }
                normal.read(offset, limit)
            })
        }
        withTimeout(10_000L) { reached.await() }
        assertNull(repository.snapshot(operation.playlistId))
        assertEquals(PlaylistOverview(0L, 0L, 0L), repository.overview())
        withTimeout(10_000L) { job.cancelAndJoin() }
        assertNull(database.playlistDao().receipt(operation.operationId))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state = 'ABORTED'"))
        assertEquals(200L, scalar("SELECT COUNT(*) FROM playlist_members"))
        val retried = repository.apply(operation, normal)
        assertFalse(retried.wasReplay)
        assertEquals(401L, retried.snapshot?.memberCount)
        assertEquals(401L, retried.addedCount)
        assertTrue(repository.apply(operation).wasReplay)
        while (repository.maintain()) { /* 一次调用只回收一个有界批次。 */ }
        assertEquals(401L, scalar("SELECT COUNT(*) FROM playlist_members"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        reopen()
        assertTrue(repository.apply(operation).wasReplay)
        assertEquals(401L, allMembers(checkNotNull(retried.snapshot)).size.toLong())
        integrity()
    }

    @Test(timeout = 30000L)
    fun cancelledNoncancellableSourceRetainsWriterOwnershipUntilItActuallyReturns() = runBlocking {
        val old = create("original", references = listOf(reference(1), reference(2)))
        val input = FrozenPlaylistInput("blocked-source", 1L, 2L)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = PlaylistMutationRequest("cancel-blocked", "blocked", 0L, PlaylistMutation.Create("Blocked", input))
        val job = launch {
            repository.apply(operation, PlaylistPageSource { offset, limit ->
                withContext(NonCancellable) {
                    assertFalse(database.inTransaction())
                    reached.complete(Unit)
                    release.await()
                    generatedSource(input).read(offset, limit)
                }
            })
        }
        try {
            withTimeout(10_000L) { reached.await() }
            job.cancel()
            assertFalse(job.isCompleted)
            expectIssue(PlaylistIssue.BUSY) {
                repository.apply(request("while-source-blocked", old, PlaylistMutation.Rename("Too early")))
            }
            assertEquals(old, repository.snapshot(old.playlistId))
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertNull(repository.snapshot(operation.playlistId))
        assertNull(database.playlistDao().receipt(operation.operationId))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        val renamed = repository.apply(request("after-source-returned", old, PlaylistMutation.Rename("Accepted")))
        assertEquals("Accepted", renamed.snapshot?.name)
        integrity()
    }

    @Test(timeout = 30000L)
    fun aConcurrentAcceptedEditInvalidatesTheOriginalCompareAndSetAfterLongSourceReading() = runBlocking {
        val old = create("cas", references = listOf(reference(900), reference(901)))
        val before = allMembers(old)
        val input = FrozenPlaylistInput("cas-selection", 1L, 2L)
        val operation = request("stale-add", old, PlaylistMutation.Add(input))
        var winner: PlaylistSnapshot? = null
        expectIssue(PlaylistIssue.CONFLICT) {
            repository.apply(operation, PlaylistPageSource { offset, limit ->
                assertFalse(database.inTransaction())
                winner = newRepository().apply(request("accepted-rename", old, PlaylistMutation.Rename("Winner"))).snapshot
                generatedSource(input).read(offset, limit)
            })
        }
        val current = checkNotNull(winner)
        assertEquals(current, repository.snapshot(old.playlistId))
        assertEquals(before, allMembers(current))
        assertNull(database.playlistDao().receipt(operation.operationId))
        expectIssue(PlaylistIssue.CONFLICT) {
            repository.apply(request("stale-second-edit", old, PlaylistMutation.Rename("Stale")))
        }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        reopen()
        assertEquals(current, repository.snapshot(old.playlistId))
        integrity()
    }

    @Test(timeout = 30000L)
    fun aDeferredSqlForeignKeyFailureAtCommitRollsBackHeadRevisionReceiptAndPublicationBeforeCleanup() = runBlocking {
        val old = create("commit-failure", references = listOf(reference(900), reference(901)))
        val before = allMembers(old)
        val overview = repository.overview()
        execute("""CREATE TABLE playlist_commit_guard(id TEXT NOT NULL PRIMARY KEY, receiptId TEXT NOT NULL,
            FOREIGN KEY(receiptId) REFERENCES playlist_receipts(operationId) DEFERRABLE INITIALLY DEFERRED)""")
        execute("""CREATE TRIGGER reject_playlist_commit AFTER INSERT ON playlist_receipts BEGIN
            INSERT INTO playlist_commit_guard(id, receiptId) VALUES(NEW.operationId, 'absent-receipt'); END""")
        val input = FrozenPlaylistInput("commit-selection", 1L, 2L)
        val operation = request("commit-must-fail", old, PlaylistMutation.Add(input))
        expectIssue(PlaylistIssue.RECOVERY_REQUIRED) { repository.apply(operation, generatedSource(input)) }
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(before, allMembers(old))
        assertEquals(overview, repository.overview())
        assertNull(database.playlistDao().receipt(operation.operationId))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_commit_guard"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        assertEquals(before.size.toLong(), scalar("SELECT COUNT(*) FROM playlist_members"))
        execute("DROP TRIGGER reject_playlist_commit")
        execute("DROP TABLE playlist_commit_guard")
        val accepted = repository.apply(operation, generatedSource(input))
        assertFalse(accepted.wasReplay)
        assertEquals(4L, accepted.snapshot?.memberCount)
        assertEquals(before, allMembers(checkNotNull(accepted.snapshot)).take(2))
        assertTrue(repository.apply(operation).wasReplay)
        integrity()
    }

    @Test(timeout = 30000L)
    fun cancellationAfterEnteringTheShortCommitPointPreservesTheAcceptedOperationAndReplay() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val realDao = database.playlistDao()
        val gated = object : PlaylistDao by realDao {
            override suspend fun insertReceipt(receipt: PlaylistReceiptEntity) {
                realDao.insertReceipt(receipt)
                entered.complete(Unit)
                release.await()
            }
        }
        repository = newRepository(gated)
        val input = FrozenPlaylistInput("accepted-before-cancel", 1L, 3L)
        val operation = PlaylistMutationRequest("accepted-cancel", "accepted", 0L, PlaylistMutation.Create("Accepted", input))
        val job = launch { repository.apply(operation, generatedSource(input)) }
        try {
            withTimeout(10_000L) { entered.await() }
            job.cancel()
            assertFalse(job.isCompleted)
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        repository = newRepository()
        val replay = repository.apply(operation)
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.CREATED, replay.result)
        assertEquals(3L, replay.snapshot?.memberCount)
        assertEquals(1L, repository.overview().revision)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        reopen()
        assertTrue(repository.apply(operation).wasReplay)
        integrity()
    }

    @Test(timeout = 30000L)
    fun realSqliteFullDuringMemberCopyPreservesTheOldCompleteListAndAllowsTheSameIntentToRetry() = runBlocking {
        val old = create("full-storage", references = List(205) { reference(it.toLong()).copy(titleHint = "x".repeat(4096)) })
        val original = allMembers(old)
        val operation = request("move-on-full-storage", old, PlaylistMutation.Move(original.last().memberId, 0L))
        val maximum = database.withTransaction {
            val previous = writerPragma("max_page_count")
            val cap = writerPragma("page_count") + 8L
            assertEquals(cap, writerPragma("max_page_count = " + cap))
            previous
        }
        try {
            try { repository.apply(operation); throw AssertionError("member copy must hit the actual SQLite page ceiling") }
            catch (error: PlaylistException) {
                assertEquals(PlaylistIssue.STORAGE_FULL, error.issue)
                assertTrue(error.cause is SQLiteFullException)
            }
            assertEquals(old, repository.snapshot(old.playlistId))
            assertNull(database.playlistDao().receipt(operation.operationId))
        } finally {
            database.withTransaction { writerPragma("max_page_count = " + maximum) }
        }
        reopen()
        assertEquals(original, allMembers(old))
        val retried = checkNotNull(repository.apply(operation).snapshot)
        assertEquals(listOf(original.last().memberId) + original.dropLast(1).map { it.memberId },
            allMembers(retried).map { it.memberId })
        integrity()
    }

    @Test(timeout = 30000L)
    fun corruptActiveMemberIsReportedForRecoveryWithoutDeletingTheListOrInventingAReplacement() = runBlocking {
        val old = create("corrupt", references = listOf(reference(1)))
        execute("UPDATE playlist_members SET referenceKey = 'invalid-persisted-key'")
        val view = repository.openView(old.playlistId)
        expectIssue(PlaylistIssue.RECOVERY_REQUIRED) { repository.page(view) }
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_members"))
        repository.closeView(view.viewId)
        repository.maintain()
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_members"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_receipts WHERE result != 'CREATED'"))
    }

    @Test(timeout = 30000L)
    fun revisionExhaustionRollsBackTheProspectiveRenameAndLeavesNoAcceptedReceipt() = runBlocking {
        val old = create("exhausted", references = listOf(reference(1)))
        database.withTransaction {
            val meta = checkNotNull(database.playlistDao().meta())
            database.playlistDao().updateMeta(meta.copy(revision = Long.MAX_VALUE))
        }
        val operation = request("exhausted-rename", old, PlaylistMutation.Rename("Cannot advance"))
        expectIssue(PlaylistIssue.REVISION_EXHAUSTED) { repository.apply(operation) }
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(Long.MAX_VALUE, repository.overview().revision)
        assertNull(database.playlistDao().receipt(operation.operationId))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        integrity()
    }

    private fun writerPragma(value: String): Long = database.openHelper.writableDatabase.query("PRAGMA $value").use {
        check(it.moveToFirst())
        it.getLong(0)
    }
}
