package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistCoreTest : PlaylistRoomFixture() {
    @Test(timeout = 30000L)
    fun sameNormalizedNameCreatesIndependentListsAndRenameRetainsIdentityAndMembersAcrossReopen() = runBlocking {
        val first = create("first", "  Cafe\u0301  ", listOf(reference(1), reference(2)))
        val before = allMembers(first)
        assertEquals("Café", first.name)
        val duplicate = repository.apply(PlaylistMutationRequest("create-second", "second", 0L,
            PlaylistMutation.Create("CAFÉ")))
        assertEquals(1L, duplicate.sameNameCount)
        assertNotEquals(first.playlistId, duplicate.playlistId)
        assertEquals(2L, repository.overview().playlistCount)
        val renamed = checkNotNull(repository.apply(request("rename-first", first,
            PlaylistMutation.Rename("  Evening  "))).snapshot)
        assertEquals("Evening", renamed.name)
        assertEquals(first.playlistId, renamed.playlistId)
        assertEquals(first.createdEpochMs, renamed.createdEpochMs)
        assertNotEquals(first.generationId, renamed.generationId)
        assertEquals(before, allMembers(renamed))
        assertEquals("CAFÉ", repository.snapshot("second")?.name)
        reopen()
        assertEquals(renamed, repository.snapshot(first.playlistId))
        assertEquals("CAFÉ", repository.snapshot("second")?.name)
        assertEquals(before, allMembers(renamed))
        integrity()
    }

    @Test(timeout = 30000L)
    fun namesCountUnicodeCodePointsAndRejectEmptyControlsAndUnpairedSurrogatesWithoutPartialLists() = runBlocking {
        val eighty = "\uD83C\uDFB5".repeat(80)
        assertEquals(eighty, create("unicode", " $eighty ").name)
        val before = repository.overview()
        for ((index, invalid) in listOf("", " \t ", "\uD83C\uDFB5".repeat(81), "bad\nname",
            "bad\u0000name", "\uD800", "\uDC00", "x".repeat(4097)).withIndex()) {
            expectIssue(PlaylistIssue.INVALID_INPUT) {
                repository.apply(PlaylistMutationRequest("invalid-$index", "invalid-$index", 0L,
                    PlaylistMutation.Create(invalid)))
            }
            assertNull(repository.snapshot("invalid-$index"))
        }
        assertEquals(before, repository.overview())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
    }

    @Test(timeout = 30000L)
    fun explicitRelationshipDeletionPreservesOriginalFilesFavoriteAndOldLeasedMembers() = runBlocking {
        val original = File(folder.root, "original-audio.mp3")
        val bytes = "original local audio fixture".toByteArray()
        original.writeBytes(bytes)
        val key = checkNotNull(reference(1).trackKey)
        val favorite = checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID))
        repository.apply(request("favorite-original", favorite, PlaylistMutation.SetFavorite(reference(1), true)))
        val playlist = create("to-delete", references = listOf(reference(1), reference(2)))
        val oldView = repository.openView(playlist.playlistId)
        val oldMembers = allMembers(oldView)
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(request("delete-unconfirmed", playlist, PlaylistMutation.Delete(false)))
        }
        assertEquals(playlist, repository.snapshot(playlist.playlistId))
        val request = request("delete-confirmed", playlist, PlaylistMutation.Delete(true))
        val deleted = repository.apply(request)
        assertEquals(PlaylistOperationResult.DELETED, deleted.result)
        assertEquals(2L, deleted.removedCount)
        assertNull(deleted.snapshot)
        assertNull(repository.snapshot(playlist.playlistId))
        assertTrue(repository.isFavorite(key))
        assertTrue(original.exists())
        assertTrue(bytes.contentEquals(original.readBytes()))
        assertEquals(oldMembers, allMembers(oldView))
        assertTrue(repository.apply(request).wasReplay)
        expectIssue(PlaylistIssue.CONFLICT) {
            repository.apply(PlaylistMutationRequest("recreate-deleted-id", playlist.playlistId, 0L,
                PlaylistMutation.Create("New name")))
        }
        repository.closeView(oldView.viewId)
        reopen()
        assertNull(repository.snapshot(playlist.playlistId))
        assertTrue(repository.isFavorite(key))
        assertTrue(bytes.contentEquals(original.readBytes()))
        integrity()
    }

    @Test(timeout = 30000L)
    fun favoritesPersistAndRepeatedSetIsIdempotentWhileStaleIntentCannotToggleThem() = runBlocking {
        val initial = checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID))
        assertEquals(0L, initial.revision)
        val key = checkNotNull(reference(7).trackKey)
        assertFalse(repository.isFavorite(key))
        val enabled = repository.apply(request("favorite-enable", initial, PlaylistMutation.SetFavorite(reference(7), true)))
        val current = checkNotNull(enabled.snapshot)
        val members = allMembers(current)
        val global = repository.overview()
        val duplicate = repository.apply(request("favorite-enable-again", current,
            PlaylistMutation.SetFavorite(reference(7).copy(titleHint = "Different hint"), true)))
        assertEquals(PlaylistOperationResult.UNCHANGED, duplicate.result)
        assertEquals(current, duplicate.snapshot)
        assertEquals(global, repository.overview())
        assertEquals(members, allMembers(current))
        expectIssue(PlaylistIssue.CONFLICT) {
            repository.apply(request("favorite-stale-disable", initial, PlaylistMutation.SetFavorite(reference(7), false)))
        }
        reopen()
        assertTrue(repository.isFavorite(key))
        assertEquals(members, allMembers(current))
        val disabled = checkNotNull(repository.apply(request("favorite-disable", current,
            PlaylistMutation.SetFavorite(reference(7), false))).snapshot)
        assertFalse(repository.isFavorite(key))
        assertEquals(0L, disabled.memberCount)
        val disabledAgain = repository.apply(request("favorite-disable-again", disabled,
            PlaylistMutation.SetFavorite(reference(7), false)))
        assertEquals(PlaylistOperationResult.UNCHANGED, disabledAgain.result)
        assertEquals(disabled, disabledAgain.snapshot)
        assertEquals(0L, repository.overview().playlistCount)
        reopen()
        assertFalse(repository.isFavorite(key))
        integrity()
    }

    @Test(timeout = 30000L)
    fun systemFavoritesRejectOrdinaryEditingAndOrdinaryListsRejectTheFavoriteCommand() = runBlocking {
        val favorites = checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID))
        for ((index, edit) in listOf(PlaylistMutation.Rename("Custom"), PlaylistMutation.Delete(true),
            PlaylistMutation.Remove("absent", true)).withIndex()) {
            expectIssue(PlaylistIssue.INVALID_INPUT) { repository.apply(request("system-edit-$index", favorites, edit)) }
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(PlaylistMutationRequest("fake-system-create", PlaylistLimits.FAVORITES_ID, 0L,
                PlaylistMutation.Create("Favorites")))
        }
        val ordinary = create("ordinary")
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(request("ordinary-favorite", ordinary, PlaylistMutation.SetFavorite(reference(1), true)))
        }
        assertEquals(favorites, repository.snapshot(PlaylistLimits.FAVORITES_ID))
        assertEquals(ordinary, repository.snapshot(ordinary.playlistId))
    }

    @Test(timeout = 30000L)
    fun addDeduplicatesByTrackIdentityAcrossPagesAndRetainsBothUnresolvedDescriptionsInOrder() = runBlocking {
        val existing = create("append", references = listOf(reference(1), reference(2)))
        val oldMembers = allMembers(existing)
        val firstPlaceholder = PlaylistReference(placeholderId = "unresolved-a", issue = PlaylistReferenceIssue.UNMATCHED,
            titleHint = "Same title", relativePathHint = "music/missing.flac")
        val secondPlaceholder = firstPlaceholder.copy(placeholderId = "unresolved-b",
            issue = PlaylistReferenceIssue.REQUIRES_REASSOCIATION)
        val selected = List(101) { if (it % 2 == 0) reference(1).copy(titleHint = "New hint") else reference(3) } +
            listOf(firstPlaceholder, secondPlaceholder, reference(4), reference(3))
        val input = FrozenPlaylistInput("selection-with-duplicates", 4L, selected.size.toLong())
        val commit = repository.apply(request("append-selected", existing, PlaylistMutation.Add(input)), listSource(input, selected))
        val changed = checkNotNull(commit.snapshot)
        assertEquals(4L, commit.addedCount)
        assertEquals(selected.size.toLong() - 4L, commit.duplicateCount)
        val members = allMembers(changed)
        assertEquals(oldMembers, members.take(2))
        assertEquals(listOf(reference(1), reference(2), reference(3), firstPlaceholder, secondPlaceholder, reference(4)),
            members.map { it.reference })
        assertEquals((0L until 6L).toList(), members.map { it.ordinal })
        assertEquals(6, members.map { it.memberId }.distinct().size)
        reopen()
        assertEquals(members, allMembers(changed))
        integrity()
    }

    @Test(timeout = 30000L)
    fun anAllDuplicateAddVerifiesEveryPageWithoutReplacingGenerationMetadataOrMembers() = runBlocking {
        val old = create("all-duplicates", references = listOf(reference(1), reference(2)))
        val before = repository.overview()
        val original = allMembers(old)
        val input = FrozenPlaylistInput("only-existing", 3L, 205L)
        var calls = 0
        val source = PlaylistPageSource { offset, limit ->
            assertFalse(database.hasCurrentWriterTransaction())
            calls += 1
            val count = minOf(limit.toLong(), input.totalCount - offset).toInt()
            PlaylistInputPage(input, offset, List(count) { reference(1L + (offset + it) % 2L) },
                offset + count == input.totalCount)
        }
        val operation = request("append-all-duplicates", old, PlaylistMutation.Add(input))
        val commit = repository.apply(operation, source)
        assertEquals(3, calls)
        assertEquals(PlaylistOperationResult.UNCHANGED, commit.result)
        assertEquals(205L, commit.duplicateCount)
        assertEquals(0L, commit.addedCount)
        assertEquals(old, commit.snapshot)
        assertEquals(before, repository.overview())
        assertEquals(original, allMembers(old))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_generations WHERE state != 'PUBLISHED'"))
        val replay = repository.apply(operation)
        assertTrue(replay.wasReplay)
        assertEquals(205L, replay.duplicateCount)
        assertEquals(3, calls)
    }

    @Test(timeout = 30000L)
    fun moveAcrossPageBoundariesAndRemoveFirstLastAndOnlyMemberKeepStableIdsAndDenseOrder() = runBlocking {
        var current = create("ordered", references = List(105) { reference(it.toLong()) })
        val old = allMembers(current)
        current = checkNotNull(repository.apply(request("move-tail-to-head", current,
            PlaylistMutation.Move(old.last().memberId, 0L))).snapshot)
        var members = allMembers(current)
        assertEquals(listOf(old.last().memberId) + old.dropLast(1).map { it.memberId }, members.map { it.memberId })
        current = checkNotNull(repository.apply(request("move-head-to-tail", current,
            PlaylistMutation.Move(old.last().memberId, 104L))).snapshot)
        assertEquals(old.map { it.memberId }, allMembers(current).map { it.memberId })
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(request("remove-unconfirmed", current, PlaylistMutation.Remove(old.first().memberId, false)))
        }
        current = checkNotNull(repository.apply(request("remove-first", current,
            PlaylistMutation.Remove(old.first().memberId, true))).snapshot)
        current = checkNotNull(repository.apply(request("remove-last", current,
            PlaylistMutation.Remove(old.last().memberId, true))).snapshot)
        members = allMembers(current)
        assertEquals(old.subList(1, 104).map { it.memberId }, members.map { it.memberId })
        assertEquals((0L until 103L).toList(), members.map { it.ordinal })
        val missing = repository.apply(request("remove-missing", current, PlaylistMutation.Remove("absent-member", true)))
        assertEquals(PlaylistOperationResult.UNCHANGED, missing.result)
        assertEquals(current, missing.snapshot)
        val singleton = create("singleton", references = listOf(reference(999)))
        val empty = checkNotNull(repository.apply(request("remove-only", singleton,
            PlaylistMutation.Remove(allMembers(singleton).single().memberId, true))).snapshot)
        assertEquals(0L, empty.memberCount)
        assertTrue(allMembers(empty).isEmpty())
        reopen()
        assertEquals(members, allMembers(current))
        assertTrue(allMembers(empty).isEmpty())
        integrity()
    }

    @Test(timeout = 30000L)
    fun unmatchedMissingAndNetworkDescriptionsPersistWithoutInventingResolvedTrackKeys() = runBlocking {
        val references = listOf(reference(1),
            PlaylistReference(placeholderId = "missing", issue = PlaylistReferenceIssue.UNMATCHED,
                titleHint = "Saved missing title", relativePathHint = "music/missing.mp3"),
            PlaylistReference(placeholderId = "grant-lost", issue = PlaylistReferenceIssue.REQUIRES_REASSOCIATION,
                descriptionHint = "Original document needs explicit reassociation"),
            PlaylistReference(placeholderId = "network", issue = PlaylistReferenceIssue.NETWORK_REFERENCE,
                descriptionHint = "https://example.invalid/private/song.mp3", durationMs = 123_000L))
        val list = create("unresolved", references = references)
        val before = allMembers(list)
        assertEquals(references, before.map { it.reference })
        assertTrue(before.drop(1).all { it.reference.trackKey == null })
        reopen()
        assertEquals(before, allMembers(list))
        assertEquals(4L, repository.snapshot(list.playlistId)?.memberCount)
        assertFalse(repository.isFavorite(checkNotNull(reference(1).trackKey)))
    }

    @Test(timeout = 30000L)
    fun originalReceiptReplaysWithoutSourceAndDoesNotUndoLaterRenameOrDeletion() = runBlocking {
        val input = FrozenPlaylistInput("original-import", 3L, 2L)
        val create = PlaylistMutationRequest("durable-create", "receipt-list", 0L, PlaylistMutation.Create("Original", input))
        val first = repository.apply(create, generatedSource(input))
        val old = checkNotNull(first.snapshot)
        val renamed = checkNotNull(repository.apply(request("later-rename", old, PlaylistMutation.Rename("Later"))).snapshot)
        val replay = repository.apply(create)
        assertTrue(replay.wasReplay)
        assertEquals(first.appliedRevision, replay.appliedRevision)
        assertEquals(PlaylistOperationResult.CREATED, replay.result)
        assertEquals(renamed, replay.snapshot)
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(create.copy(mutation = PlaylistMutation.Create("Changed", input)),
                PlaylistPageSource { _, _ -> throw AssertionError("rejected replay must not read a source") })
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            repository.apply(create.copy(playlistId = "different-list"))
        }
        repository.apply(request("later-delete", renamed, PlaylistMutation.Delete(true)))
        reopen()
        val deletedReplay = repository.apply(create)
        assertTrue(deletedReplay.wasReplay)
        assertEquals(first.appliedRevision, deletedReplay.appliedRevision)
        assertNull(deletedReplay.snapshot)
        assertNull(repository.snapshot(old.playlistId))
        assertEquals(0L, repository.overview().playlistCount)
        assertEquals(3L, scalar("SELECT COUNT(*) FROM playlist_receipts"))
        integrity()
    }

    @Test(timeout = 30000L)
    fun databaseFlowsReportCommittedOverviewPlaylistAndFavoriteStateWithoutLibraryOrNetworkSetup() = runBlocking {
        assertEquals(PlaylistOverview(0L, 0L, 0L), repository.observeOverview().first())
        assertNull(repository.observePlaylist("observed").first())
        val key = TrackKey(TrackKey.LOCAL_PROVIDER, "observed-key")
        assertFalse(repository.observeFavorite(key).first())
        val overview = async { withTimeout(10_000L) { repository.observeOverview().first { it?.playlistCount == 1L } } }
        val list = create("observed", "Observed")
        assertEquals(1L, overview.await()?.playlistCount)
        assertEquals(list, repository.observePlaylist(list.playlistId).first())
        val favorite = checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID))
        val observed = async { withTimeout(10_000L) { repository.observeFavorite(key).first { it } } }
        repository.apply(request("observed-favorite", favorite, PlaylistMutation.SetFavorite(PlaylistReference(key), true)))
        assertTrue(observed.await())
        assertEquals(1L, repository.overview().favoriteCount)
    }
}
