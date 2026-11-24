package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportConfirmation
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportDestination
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportMemberIssue
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportTarget
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferEncoding
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistImportControllerTest : RoomPlaylistExchangeFixture() {
    @Test fun previewRequiresConfirmationAndExactRetryCannotCreateASecondPlaylist() = runBlocking {
        val document = input(knownM3u())
        val original = documentFile.readBytes()
        val overview = library.overview()
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
        assertEquals(2L, preview.memberCount)
        assertEquals(2L, preview.matchedCount)
        assertEquals(0L, preview.unresolvedCount)
        assertEquals("Imported playlist", preview.nameHint)
        assertNull(playlists.snapshot("import-new"))
        assertEquals(overview, library.overview())
        val rows = exchange.importPage(preview.previewId, 0L)
        assertEquals(listOf(0L, 1L), rows.rows.map { it.ordinal })
        assertTrue(rows.rows.all { it.issue == PlaylistImportMemberIssue.NONE })
        val request = PlaylistImportConfirmation("import-operation", preview.previewId,
            PlaylistImportDestination.New("import-new", "Confirmed name"), false)
        val accepted = exchange.confirmImport(request)
        assertFalse(accepted.wasReplay)
        assertEquals(listOf(replacement, sameNamedDecoy), members(checkNotNull(accepted.snapshot)).map { it.reference.trackKey })
        val replay = exchange.confirmImport(request)
        assertTrue(replay.wasReplay)
        assertEquals(accepted.appliedRevision, replay.appliedRevision)
        expectIssue(PlaylistIssue.INVALID_INPUT) {
            exchange.confirmImport(request.copy(destination = PlaylistImportDestination.New("different-id", "Confirmed name")))
        }
        expectIssue(PlaylistIssue.INVALID_INPUT) { exchangeController().confirmImport(request) }
        assertEquals(1L, playlists.overview().playlistCount)
        assertTrue(documentFile.readBytes().contentEquals(original))
        registry.discard(document.selectionId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun completePreviewAndImportKeepEveryPlaceholderBeyondTwoPageBoundaries() = runBlocking {
        val text = buildString {
            append("#EXTM3U\n#PLAYLIST:Full placeholder list\n")
            repeat(205) { append("#EXTINF:-1,Unresolved $it\nmissing/$it.mp3\n") }
        }
        val document = input(text)
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
        assertEquals(205L, preview.memberCount)
        assertEquals(205L, preview.unresolvedCount)
        val first = exchange.importPage(preview.previewId, 0L)
        val second = exchange.importPage(preview.previewId, checkNotNull(first.nextOrdinal))
        val third = exchange.importPage(preview.previewId, checkNotNull(second.nextOrdinal))
        assertEquals(listOf(100, 100, 5), listOf(first.rows.size, second.rows.size, third.rows.size))
        assertEquals("Unresolved 204", third.rows.last().title)
        assertNull(third.nextOrdinal)
        val request = PlaylistImportConfirmation("full-import", preview.previewId,
            PlaylistImportDestination.New("full-list", "Full list"), false)
        expectIssue(PlaylistIssue.INVALID_INPUT) { exchange.confirmImport(request) }
        assertNull(playlists.snapshot("full-list"))
        val accepted = exchange.confirmImport(request.copy(unresolvedConfirmed = true))
        val all = members(checkNotNull(accepted.snapshot))
        assertEquals(205, all.size)
        assertEquals((0L..204L).toList(), all.map { it.ordinal })
        assertEquals(205, all.map { it.memberId }.toSet().size)
        assertTrue(all.all { it.reference.trackKey == null && it.reference.placeholderId != null })
        assertEquals("Unresolved 204", all.last().reference.titleHint)
        registry.discard(document.selectionId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun explicitRootAmbiguityAndUnsafeReferencesRemainOrderedWithoutOpeningAudio() = runBlocking {
        val document = input("#EXTM3U\nSame.mp3\n../escape.mp3\n/outside/song.mp3\nhttps://invalid.example/song.mp3\n",
            rootBinding())
        val original = documentFile.readBytes()
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
        assertEquals(4L, preview.memberCount)
        assertEquals(0L, preview.matchedCount)
        assertEquals(1L, preview.ambiguousCount)
        assertEquals(3L, preview.blockedReferenceCount)
        val page = exchange.importPage(preview.previewId, 0L)
        assertEquals(listOf(PlaylistImportMemberIssue.AMBIGUOUS, PlaylistImportMemberIssue.BLOCKED_REFERENCE,
            PlaylistImportMemberIssue.BLOCKED_REFERENCE, PlaylistImportMemberIssue.BLOCKED_REFERENCE), page.rows.map { it.issue })
        val accepted = exchange.confirmImport(PlaylistImportConfirmation("safe-import", preview.previewId,
            PlaylistImportDestination.New("safe-list", "Safe list"), true))
        val all = members(checkNotNull(accepted.snapshot))
        assertEquals(4, all.size)
        assertTrue(all.all { it.reference.trackKey == null })
        assertTrue(all.drop(1).all { it.reference.relativePathHint == null && it.reference.descriptionHint == null })
        assertTrue(documentFile.readBytes().contentEquals(original))
        registry.discard(document.selectionId)
        assertProviderClosed()
        assertNoPrivateStages()
        integrity()
    }

    @Test fun mergeKeepsOldOrderAndAppendsOnlyNewIdentitiesAtTheExactTargetRevision() = runBlocking {
        val missing = TrackKey(TrackKey.LOCAL_PROVIDER, "retained-old-missing")
        val old = createOrdinary("merge-target", listOf(PlaylistReference(sameNamedDecoy), PlaylistReference(missing)))
        val oldMembers = members(old)
        val document = input(knownM3u())
        val target = PlaylistImportTarget(old.playlistId, old.revision)
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document, target)
        val accepted = exchange.confirmImport(PlaylistImportConfirmation("merge-operation", preview.previewId,
            PlaylistImportDestination.Merge(target), false))
        assertEquals(1L, accepted.addedCount)
        assertEquals(1L, accepted.duplicateCount)
        val all = members(checkNotNull(accepted.snapshot))
        assertEquals(listOf(sameNamedDecoy, missing, replacement), all.map { it.reference.trackKey })
        assertEquals(oldMembers.map { it.memberId }, all.take(2).map { it.memberId })
        assertEquals(listOf(0L, 1L, 2L), all.map { it.ordinal })
        registry.discard(document.selectionId)
        assertProviderClosed()
        integrity()
    }

    @Test fun renamedTargetRejectsAnOldPreviewAndPreservesBothTheRenameAndMembers() = runBlocking {
        val old = createOrdinary("changed-target")
        val document = input(knownM3u())
        val target = PlaylistImportTarget(old.playlistId, old.revision)
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document, target)
        val renamed = checkNotNull(playlists.apply(PlaylistMutationRequest("rename-target", old.playlistId, old.revision,
            PlaylistMutation.Rename("User's later name"))).snapshot)
        expectIssue(PlaylistIssue.CONFLICT) {
            exchange.confirmImport(PlaylistImportConfirmation("stale-target-import", preview.previewId,
                PlaylistImportDestination.Merge(target), false))
        }
        assertEquals(renamed, playlists.snapshot(old.playlistId))
        assertEquals(listOf(sameNamedDecoy, replacement), members(renamed).map { it.reference.trackKey })
        exchange.closePreview(preview.previewId)
        registry.discard(document.selectionId)
        assertProviderClosed()
        integrity()
    }

    @Test fun libraryChangeDuringCoreStagingIsRejectedByTheFinalRoomFence() = runBlocking {
        var changed = false
        val port = object : LocalPlaylistRepository by playlists {
            override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit {
                val wrapped = source?.let { input -> PlaylistPageSource { offset, limit ->
                    val page = input.read(offset, limit)
                    if (!changed) {
                        changed = true
                        val rules = library.rules()
                        library.setShortAudioFilter(rules.revision, !rules.shortAudioFilter)
                    }
                    page
                } }
                return playlists.apply(request, wrapped)
            }
        }
        exchange = exchangeController(port)
        val document = input(knownM3u())
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
        expectIssue(PlaylistIssue.SOURCE_CHANGED) {
            exchange.confirmImport(PlaylistImportConfirmation("late-fence-import", preview.previewId,
                PlaylistImportDestination.New("unpublished-import", "Must stay absent"), false))
        }
        assertTrue(changed)
        assertNull(playlists.snapshot("unpublished-import"))
        assertEquals(0L, playlists.overview().playlistCount)
        assertNull(database.playlistDao().receipt("late-fence-import"))
        exchange.closePreview(preview.previewId)
        registry.discard(document.selectionId)
        assertProviderClosed()
        integrity()
    }

    @Test fun cancellationBeforeCoreCommitClosesThePreviewAndDoesNotApplyOldConsent() = runBlocking {
        withTimeout(10_000L) {
            val entered = CompletableDeferred<Unit>()
            val port = object : LocalPlaylistRepository by playlists {
                override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            exchange = exchangeController(port)
            val document = input(knownM3u())
            val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            val request = PlaylistImportConfirmation("cancelled-import", preview.previewId,
                PlaylistImportDestination.New("cancelled-list", "Cancelled list"), false)
            val caller = async(Dispatchers.Default) { exchange.confirmImport(request) }
            entered.await()
            caller.cancelAndJoin()
            assertNull(playlists.snapshot("cancelled-list"))
            assertNull(database.playlistDao().receipt("cancelled-import"))
            expectIssue(PlaylistIssue.VIEW_EXPIRED) { exchange.confirmImport(request) }
            val fresh = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, document)
            assertEquals(2L, fresh.memberCount)
            exchange.closePreview(fresh.previewId)
            registry.discard(document.selectionId)
            assertProviderClosed()
            assertNoPrivateStages()
            integrity()
        }
    }

    @Test fun malformedJsonAndExpiredPreviewsNeverPublishPartialMembers() = runBlocking {
        val document = input(exchangeJson() + " trailing-invalid-data")
        expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
            exchange.inspectImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8, document)
        }
        assertEquals(0L, playlists.overview().playlistCount)
        assertNoPrivateStages()
        registry.discard(document.selectionId)
        val nextDocument = input(knownM3u())
        val preview = exchange.inspectImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8, nextDocument)
        elapsed += 10 * 60 * 1000L
        expectIssue(PlaylistIssue.VIEW_EXPIRED) {
            exchange.confirmImport(PlaylistImportConfirmation("expired-import", preview.previewId,
                PlaylistImportDestination.New("expired-list", "Expired list"), false))
        }
        assertNull(playlists.snapshot("expired-list"))
        exchange.closePreview(preview.previewId)
        registry.discard(nextDocument.selectionId)
        assertProviderClosed()
        integrity()
    }
}
