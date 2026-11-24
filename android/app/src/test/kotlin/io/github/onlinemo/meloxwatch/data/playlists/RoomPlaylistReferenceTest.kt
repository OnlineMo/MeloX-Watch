package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 测试定义待云端编译/执行；资源选择/实际重新授权另由应用和设备验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistReferenceTest : PlaylistRoomFixture() {
    @Test(timeout = 30000L)
    fun confirmedReassociationKeepsEveryMemberIdAndOrdinalAndDoesNotChangeAnAlreadyLeasedQueue() = runBlocking {
        val unresolved = PlaylistReference(placeholderId = "explicit-missing", issue = PlaylistReferenceIssue.REQUIRES_REASSOCIATION,
            titleHint = "Original missing title", relativePathHint = "music/missing.flac")
        val old = create("reference-repair", references = listOf(reference(1), unresolved, reference(3)))
        val original = allMembers(old)
        val queue = repository.openView(old.playlistId, PlaylistViewOwner.QUEUE)
        val operation = request("reference-repair-confirmed", old,
            PlaylistMutation.ReplaceReference(original[1].memberId, reference(2), true))
        val commit = repository.apply(operation)
        val repaired = checkNotNull(commit.snapshot)
        val members = allMembers(repaired)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, commit.result)
        assertEquals(0L, commit.addedCount)
        assertEquals(0L, commit.removedCount)
        assertEquals(0L, commit.duplicateCount)
        assertEquals(original.map { it.memberId }, members.map { it.memberId })
        assertEquals(original.map { it.ordinal }, members.map { it.ordinal })
        assertEquals(listOf(reference(1), reference(2), reference(3)), members.map { it.reference })
        assertEquals(original, allMembers(queue))
        repository.closeView(queue.viewId)
        reopen()
        assertEquals(repaired, repository.snapshot(repaired.playlistId))
        assertEquals(members, allMembers(repaired))
        assertTrue(repository.apply(operation).wasReplay)
        integrity()
    }

    @Test(timeout = 30000L)
    fun aDuplicateTargetIsAnExplicitFailureAndCannotSilentlyDeleteTheMemberBeingRepaired() = runBlocking {
        val missing = PlaylistReference(placeholderId = "duplicate-repair-placeholder", issue = PlaylistReferenceIssue.UNMATCHED)
        val old = create("duplicate-reference", references = listOf(reference(1), missing, reference(3)))
        val members = allMembers(old)
        val overview = repository.overview()
        val operation = request("duplicate-reference-repair", old,
            PlaylistMutation.ReplaceReference(members[1].memberId, reference(3).copy(titleHint = "Same track different hint"), true))
        expectIssue(PlaylistIssue.DUPLICATE_REFERENCE) { repository.apply(operation) }
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(old))
        assertEquals(overview, repository.overview())
        assertNull(database.playlistDao().receipt(operation.operationId))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        val retry = operation.copy(mutation = PlaylistMutation.ReplaceReference(members[1].memberId, reference(2), true))
        val accepted = checkNotNull(repository.apply(retry).snapshot)
        assertEquals(3L, accepted.memberCount)
        assertEquals(members.map { it.memberId }, allMembers(accepted).map { it.memberId })
        integrity()
    }

    @Test(timeout = 30000L)
    fun unconfirmedStaleOrUnknownMemberRepairCannotChangeTheListAndTheSystemFavoriteListRejectsReplacement() = runBlocking {
        val old = create("guarded-reference", references = listOf(reference(1), reference(2)))
        val members = allMembers(old)
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(request("reference-unconfirmed", old,
                PlaylistMutation.ReplaceReference(members[0].memberId, reference(3), false)))
        }
        expectIssue(PlaylistIssue.NOT_FOUND) {
            repository.apply(request("reference-unknown-member", old,
                PlaylistMutation.ReplaceReference("absent-member", reference(3), true)))
        }
        val renamed = checkNotNull(repository.apply(request("reference-later-rename", old, PlaylistMutation.Rename("Later"))).snapshot)
        expectIssue(PlaylistIssue.CONFLICT) {
            repository.apply(request("reference-stale-revision", old,
                PlaylistMutation.ReplaceReference(members[0].memberId, reference(3), true)))
        }
        val favorites = checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID))
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(request("reference-system-replace", favorites,
                PlaylistMutation.ReplaceReference("absent-system-member", reference(3), true)))
        }
        assertEquals(renamed, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(renamed))
        assertEquals(favorites, repository.snapshot(PlaylistLimits.FAVORITES_ID))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun replacementCanRefreshHintsForTheSameTrackAndIdenticalConfirmedRetryKeepsItsSemanticWithoutChangingRevision() = runBlocking {
        val old = create("same-reference", references = listOf(reference(1), reference(2)))
        val original = allMembers(old)
        val refreshed = reference(1).copy(titleHint = "Explicitly selected title", artistHint = "Saved artist", durationMs = 42_000L)
        val changed = checkNotNull(repository.apply(request("reference-refresh-hints", old,
            PlaylistMutation.ReplaceReference(original[0].memberId, refreshed, true))).snapshot)
        val members = allMembers(changed)
        assertEquals(original.map { it.memberId }, members.map { it.memberId })
        assertEquals(original.map { it.ordinal }, members.map { it.ordinal })
        assertEquals(refreshed, members[0].reference)
        assertEquals(original[1], members[1])
        val overview = repository.overview()
        val repeated = repository.apply(request("reference-identical-confirmed", changed,
            PlaylistMutation.ReplaceReference(original[0].memberId, refreshed, true)))
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, repeated.result)
        assertEquals(changed, repeated.snapshot)
        assertEquals(overview, repository.overview())
        assertEquals(members, allMembers(changed))
        reopen()
        assertEquals(members, allMembers(changed))
        integrity()
    }

    @Test(timeout = 30000L)
    fun aRealConcurrentAcceptedRenameDuringMemberCopyInvalidatesReplacementInsteadOfOverwritingTheWinner() = runBlocking {
        val old = create("reference-cas", references = List(105) { reference(it.toLong()) })
        val members = allMembers(old)
        val real = database.playlistDao()
        val oldDataGeneration = checkNotNull(real.generation(old.generationId)).dataGenerationId
        var winner: PlaylistSnapshot? = null
        var triggered = false
        val racingDao = object : PlaylistDao by real {
            override suspend fun membersPage(generation: String, first: Long, limit: Int): List<PlaylistMemberEntity> {
                val page = real.membersPage(generation, first, limit)
                if (!triggered && generation == oldDataGeneration && first == 0L) {
                    triggered = true
                    assertFalse(database.hasCurrentWriterTransaction())
                    winner = newRepository().apply(request("reference-cas-winner", old, PlaylistMutation.Rename("Winner"))).snapshot
                }
                return page
            }
        }
        repository = newRepository(racingDao)
        val operation = request("reference-cas-loser", old,
            PlaylistMutation.ReplaceReference(members[100].memberId, reference(999), true))
        expectIssue(PlaylistIssue.CONFLICT) { repository.apply(operation) }
        val current = checkNotNull(winner)
        assertEquals(current, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(current))
        assertNull(real.receipt(operation.operationId))
        while (repository.maintain()) { /* 105行失败副本需要两个有界回收批次。 */ }
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        reopen()
        assertEquals(current, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(current))
        integrity()
    }

    @Test(timeout = 30000L)
    fun aLibraryRevisionChangedDuringRepairCopyIsCheckedInsideTheFinalTransactionAndPreservesTheOriginalReference() = runBlocking {
        val fence = prepareFence()
        val revision = PlaylistLibraryRevision(fence.catalogRevision, fence.rulesRevision)
        val old = create("reference-library-fence", references = List(105) { reference(it.toLong()) })
        val members = allMembers(old)
        val overview = repository.overview()
        val real = database.playlistDao()
        val oldDataGeneration = checkNotNull(real.generation(old.generationId)).dataGenerationId
        var changed = false
        val changingDao = object : PlaylistDao by real {
            override suspend fun membersPage(generation: String, first: Long, limit: Int): List<PlaylistMemberEntity> {
                val page = real.membersPage(generation, first, limit)
                if (!changed && generation == oldDataGeneration && first == 100L) {
                    changed = true
                    assertFalse(database.hasCurrentWriterTransaction())
                    execute("UPDATE playlist_fixture_library SET rulesRevision = rulesRevision + 1")
                }
                return page
            }
        }
        val verifier = useFenceVerifier(changingDao)
        val operation = request("reference-library-changed", old,
            PlaylistMutation.ReplaceReference(members[100].memberId, reference(999), true)).copy(expectedLibraryRevision = revision)
        expectIssue(PlaylistIssue.SOURCE_CHANGED) { repository.apply(operation) }
        assertEquals(1, verifier.libraryChecks)
        assertEquals(old, repository.snapshot(old.playlistId))
        assertEquals(members, allMembers(old))
        assertEquals(overview, repository.overview())
        assertNull(real.receipt(operation.operationId))
        while (repository.maintain()) { /* 旧成员仍是唯一活动关系，失败副本不参与恢复。 */ }
        assertEquals(105L, scalar("SELECT COUNT(*) FROM playlist_members"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun replacementReceiptsRetainOriginalSemanticAndBindConfirmationMemberIdentityTargetAndAllHintFields() = runBlocking {
        val old = create("reference-receipt", references = listOf(reference(1), reference(2)))
        val original = allMembers(old)
        val target = reference(3).copy(artistHint = "Artist", albumHint = "Album", relativePathHint = "music/third.flac",
            descriptionHint = "Explicit saved description", durationMs = 123_000L)
        val operation = request("reference-first-receipt", old,
            PlaylistMutation.ReplaceReference(original[0].memberId, target, true))
        val first = repository.apply(operation)
        val repaired = checkNotNull(first.snapshot)
        val variants = listOf(
            PlaylistMutation.ReplaceReference(original[0].memberId, target, false),
            PlaylistMutation.ReplaceReference(original[1].memberId, target, true),
            PlaylistMutation.ReplaceReference(original[0].memberId, reference(4), true),
            PlaylistMutation.ReplaceReference(original[0].memberId, target.copy(titleHint = "Other title"), true),
            PlaylistMutation.ReplaceReference(original[0].memberId, target.copy(artistHint = "Other artist"), true),
            PlaylistMutation.ReplaceReference(original[0].memberId, target.copy(albumHint = "Other album"), true),
            PlaylistMutation.ReplaceReference(original[0].memberId, target.copy(relativePathHint = "music/other.flac"), true),
            PlaylistMutation.ReplaceReference(original[0].memberId, target.copy(descriptionHint = "Other description"), true),
            PlaylistMutation.ReplaceReference(original[0].memberId, target.copy(durationMs = 124_000L), true),
        )
        for (variant in variants) expectIssue(PlaylistIssue.INVALID_INPUT) { repository.apply(operation.copy(mutation = variant)) }
        val later = checkNotNull(repository.apply(request("reference-later-replacement", repaired,
            PlaylistMutation.ReplaceReference(original[0].memberId, reference(5), true))).snapshot)
        val replay = repository.apply(operation)
        assertTrue(replay.wasReplay)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, replay.result)
        assertEquals(first.appliedRevision, replay.appliedRevision)
        assertEquals(later, replay.snapshot)
        assertEquals(reference(5), allMembers(later)[0].reference)
        repository.apply(request("reference-later-deletion", later, PlaylistMutation.Delete(true)))
        reopen()
        val deletedReplay = repository.apply(operation)
        assertTrue(deletedReplay.wasReplay)
        assertEquals(PlaylistOperationResult.REFERENCE_REPLACED, deletedReplay.result)
        assertEquals(first.appliedRevision, deletedReplay.appliedRevision)
        assertNull(deletedReplay.snapshot)
        assertEquals(4L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }
}
