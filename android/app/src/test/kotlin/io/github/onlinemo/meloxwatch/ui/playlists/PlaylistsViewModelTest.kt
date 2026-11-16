package io.github.onlinemo.meloxwatch.ui.playlists

import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeException
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeIssue
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueAction
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证页面意图/草稿/取消；实际Room、云编译、文档写入与表上触控仍需各自证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaylistsViewModelTest {
    @Test(timeout = 30_000L)
    fun failedNameDraftRestoresWithoutWritingAndRetriesTheSameIntentOnceExplicitlySaved() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        var restored: PlaylistsViewModel? = null
        try {
            fixture.model.onVisible()
            fixture.await { it.catalogPage != null && !it.loading }
            fixture.model.beginCreate()
            fixture.model.changeName("  Café e\u0301 🎵  ")
            val draft = checkNotNull(fixture.model.uiState.value.nameDraft)
            fixture.ports.playlists.mutation = { _, _ -> throw PlaylistException(PlaylistIssue.STORAGE_FULL) }
            fixture.model.saveName()
            fixture.await { it.issue == PlaylistUiIssue.STORAGE_FULL }
            assertEquals(draft.name, fixture.model.uiState.value.nameDraft?.name)
            assertEquals(1, fixture.ports.playlists.requests.size)
            fixture.close()

            val next = fixture.newModel()
            restored = next
            next.onVisible()
            withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.NAME && !it.loading && it.catalogPage != null } }
            assertEquals(1, fixture.ports.playlists.requests.size)
            assertEquals(draft.operationId, next.uiState.value.nameDraft?.operationId)
            assertEquals(draft.playlistId, next.uiState.value.nameDraft?.playlistId)
            fixture.ports.playlists.mutation = { request, _ ->
                fixture.create(request.playlistId, (request.mutation as PlaylistMutation.Create).name, 0L)
            }
            next.saveName()
            val result = withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.DETAILS && it.view != null && !it.loading } }
            assertEquals("Café é 🎵", result.view?.snapshot?.name)
            assertEquals(2, fixture.ports.playlists.requests.size)
            assertEquals(fixture.ports.playlists.requests[0], fixture.ports.playlists.requests[1])
        } finally {
            val next = restored
            if (next == null) fixture.close() else fixture.close(next)
        }
    }

    @Test(timeout = 30_000L)
    fun invalidNamesNeverWriteAndUnicodeNameLimitsCountCodePoints() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.model.onVisible()
            fixture.await { it.catalogPage != null && !it.loading }
            fixture.model.beginCreate()
            for (name in listOf("", "   ", "🎵".repeat(81), "line\nname", "bad\uD800")) {
                fixture.model.changeName(name)
                fixture.model.saveName()
                assertEquals(PlaylistUiIssue.INVALID_NAME, fixture.model.uiState.value.issue)
                assertTrue(fixture.ports.playlists.requests.isEmpty())
            }
            fixture.ports.playlists.mutation = { request, _ ->
                fixture.create(request.playlistId, (request.mutation as PlaylistMutation.Create).name, 0L)
            }
            fixture.model.changeName("🎵".repeat(80))
            fixture.model.saveName()
            val state = fixture.await { it.view != null && it.screen == PlaylistScreen.DETAILS && !it.loading }
            val name = checkNotNull(state.view).snapshot.name
            assertEquals(80, name.codePointCount(0, name.length))
            assertEquals(1, fixture.ports.playlists.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun explicitReloadReviewsARenameAgainstTheCurrentRevisionWithoutDiscardingItsNameOrAutoSaving() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            fixture.model.beginRename()
            fixture.model.changeName("My edited name")
            val originalDraft = checkNotNull(fixture.model.uiState.value.nameDraft)
            val original = checkNotNull(fixture.ports.playlists.detail.value)
            fixture.ports.playlists.detail.value = original.copy(revision = original.revision + 1L, name = "Another saved name")
            fixture.ports.playlists.mutation = { request, _ ->
                val current = checkNotNull(fixture.ports.playlists.detail.value)
                if (request.expectedRevision != current.revision) throw PlaylistException(PlaylistIssue.CONFLICT)
                val updated = current.copy(revision = current.revision + 1L, name = (request.mutation as PlaylistMutation.Rename).name)
                fixture.ports.playlists.detail.value = updated
                PlaylistCommit(updated.playlistId, updated.revision, PlaylistOperationResult.RENAMED, updated, false)
            }
            fixture.model.saveName()
            fixture.await { it.issue == PlaylistUiIssue.CONFLICT }
            assertEquals(originalDraft, fixture.model.uiState.value.nameDraft)
            fixture.model.reload()
            val state = fixture.await { it.view?.snapshot?.revision == original.revision + 1L && !it.loading }
            val reviewed = checkNotNull(state.nameDraft)
            assertEquals("My edited name", reviewed.name)
            assertEquals("Another saved name", state.view?.snapshot?.name)
            assertEquals(original.revision + 1L, reviewed.expectedRevision)
            assertNotEquals(originalDraft.operationId, reviewed.operationId)
            assertEquals(1, fixture.ports.playlists.requests.size)
            fixture.model.saveName()
            val saved = fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            assertEquals("My edited name", saved.view?.snapshot?.name)
            assertEquals(original.memberCount, saved.view?.snapshot?.memberCount)
            assertEquals(2, fixture.ports.playlists.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun cancelledDeletionDoesNotWriteAndDoubleConfirmationSubmitsOnlyOneConfirmedDelete() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.showDetails()
            fixture.model.beginDelete()
            fixture.model.returnToDetails()
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            fixture.ports.playlists.mutation = { request, _ ->
                release.await()
                fixture.ports.playlists.detail.value = null
                PlaylistCommit(request.playlistId, request.expectedRevision + 1L, PlaylistOperationResult.DELETED, null, false)
            }
            fixture.model.beginDelete()
            fixture.model.confirmDelete()
            fixture.model.confirmDelete()
            fixture.await { it.saving }
            assertEquals(1, fixture.ports.playlists.requests.size)
            assertEquals(PlaylistMutation.Delete(true), fixture.ports.playlists.requests.single().mutation)
            release.complete(Unit)
            fixture.await { it.screen == PlaylistScreen.CATALOG && !it.loading }
            assertNull(fixture.ports.playlists.detail.value)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun movePreviewNeverWritesUntilSaveAndConflictKeepsTheDestinationAndOperationIdentity() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginMove()
            fixture.model.moveUp()
            assertEquals("1", fixture.model.uiState.value.moveDraft?.destination)
            fixture.model.moveDown()
            assertEquals("2", fixture.model.uiState.value.moveDraft?.destination)
            fixture.model.returnToDetails()
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginMove()
            fixture.model.changeMoveDestination("205")
            val draft = checkNotNull(fixture.model.uiState.value.moveDraft)
            fixture.ports.playlists.mutation = { _, _ -> throw PlaylistException(PlaylistIssue.CONFLICT) }
            fixture.model.saveMove()
            fixture.await { it.issue == PlaylistUiIssue.CONFLICT }
            assertEquals(draft, fixture.model.uiState.value.moveDraft)
            assertEquals(PlaylistMutation.Move("member-0", 204L), fixture.ports.playlists.requests.single().mutation)
            fixture.ports.playlists.mutation = { request, _ -> PlaylistCommit(request.playlistId, request.expectedRevision,
                PlaylistOperationResult.UNCHANGED, fixture.ports.playlists.detail.value, false) }
            fixture.model.saveMove()
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            assertEquals(2, fixture.ports.playlists.requests.size)
            assertEquals(fixture.ports.playlists.requests[0], fixture.ports.playlists.requests[1])
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun failedMoveRestoresItsPureIntentAndOnlyAnExplicitSaveRetriesTheSameRequest() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        var recreated: PlaylistsViewModel? = null
        try {
            fixture.showDetails()
            val originalView = checkNotNull(fixture.model.uiState.value.view)
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginMove()
            fixture.model.changeMoveDestination("205")
            val draft = checkNotNull(fixture.model.uiState.value.moveDraft)
            fixture.ports.playlists.mutation = { _, _ -> throw PlaylistException(PlaylistIssue.STORAGE_FULL) }
            fixture.model.saveMove()
            fixture.await { it.issue == PlaylistUiIssue.STORAGE_FULL }
            val handle = fixture.copySavedState()
            fixture.close()

            val next = fixture.newModel(handle)
            recreated = next
            assertEquals(draft, next.uiState.value.moveDraft)
            assertNull(next.uiState.value.selected)
            next.onVisible()
            val state = withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.MOVE && it.view != null && !it.loading } }
            assertEquals(draft, state.moveDraft)
            assertNotEquals(originalView.viewId, state.view?.viewId)
            assertEquals(1, fixture.ports.playlists.requests.size)
            fixture.ports.playlists.mutation = { request, _ -> PlaylistCommit(request.playlistId, request.expectedRevision,
                PlaylistOperationResult.UNCHANGED, fixture.ports.playlists.detail.value, false) }
            next.saveMove()
            withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.DETAILS && !it.loading } }
            assertEquals(2, fixture.ports.playlists.requests.size)
            assertEquals(fixture.ports.playlists.requests[0], fixture.ports.playlists.requests[1])
        } finally {
            val next = recreated
            if (next == null) fixture.close() else fixture.close(next)
        }
    }

    @Test(timeout = 30_000L)
    fun aHiddenMoveDraftReturnsWithoutWritingAndExplicitCancelRemovesItFromRecreation() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        var recreated: PlaylistsViewModel? = null
        try {
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginMove()
            fixture.model.changeMoveDestination("99")
            val draft = checkNotNull(fixture.model.uiState.value.moveDraft)
            fixture.model.onHidden()
            fixture.model.onVisible()
            val state = fixture.await { it.screen == PlaylistScreen.MOVE && it.view != null && !it.loading }
            assertEquals(draft, state.moveDraft)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            fixture.model.returnToDetails()
            val handle = fixture.copySavedState()
            fixture.close()
            val next = fixture.newModel(handle)
            recreated = next
            next.onVisible()
            withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.DETAILS && it.view != null && !it.loading } }
            assertNull(next.uiState.value.moveDraft)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
        } finally {
            val next = recreated
            if (next == null) fixture.close() else fixture.close(next)
        }
    }

    @Test(timeout = 30_000L)
    fun aRestoredMoveRemainsEditableWhenThePlaylistIsNowEmptyAndDoesNotSubmit() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        var recreated: PlaylistsViewModel? = null
        try {
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginMove()
            fixture.model.changeMoveDestination("205")
            val draft = checkNotNull(fixture.model.uiState.value.moveDraft)
            val handle = fixture.copySavedState()
            fixture.close()
            val snapshot = checkNotNull(fixture.ports.playlists.detail.value)
            fixture.ports.playlists.detail.value = snapshot.copy(revision = snapshot.revision + 1L, memberCount = 0L)

            val next = fixture.newModel(handle)
            recreated = next
            next.onVisible()
            withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.MOVE && it.view != null && !it.loading } }
            next.moveUp()
            next.moveDown()
            assertEquals(draft, next.uiState.value.moveDraft)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            next.saveMove()
            assertEquals(PlaylistUiIssue.INVALID_POSITION, next.uiState.value.issue)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            next.changeMoveDestination("1")
            assertEquals("1", next.uiState.value.moveDraft?.destination)
            assertEquals(draft.memberId, next.uiState.value.moveDraft?.memberId)
            assertEquals(draft.expectedRevision, next.uiState.value.moveDraft?.expectedRevision)
        } finally {
            val next = recreated
            if (next == null) fixture.close() else fixture.close(next)
        }
    }

    @Test(timeout = 30_000L)
    fun aSuspendedReloadCannotSaveAMoveAgainstThePreviousViewAndRequiresAnExplicitRetry() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.showDetails()
            val originalView = checkNotNull(fixture.model.uiState.value.view)
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginMove()
            fixture.model.changeMoveDestination("205")
            val draft = checkNotNull(fixture.model.uiState.value.moveDraft)
            fixture.ports.playlists.beforeOpenView = {
                opened.complete(Unit)
                release.await()
            }
            fixture.model.reload()
            withTimeout(5_000L) { opened.await() }
            val waiting = fixture.model.uiState.value
            assertTrue(waiting.loading)
            assertTrue(waiting.showingPreviousData)
            assertEquals(originalView, waiting.view)
            fixture.model.saveMove()
            assertFalse(fixture.model.uiState.value.saving)
            assertEquals(draft, fixture.model.uiState.value.moveDraft)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            release.complete(Unit)
            val reloaded = fixture.await { it.screen == PlaylistScreen.MOVE && it.view != null && !it.loading }
            assertTrue(reloaded.canUseView)
            assertNotEquals(originalView.viewId, reloaded.view?.viewId)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
            fixture.ports.playlists.mutation = { request, _ -> PlaylistCommit(request.playlistId, request.expectedRevision,
                PlaylistOperationResult.UNCHANGED, fixture.ports.playlists.detail.value, false) }
            fixture.model.saveMove()
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            val request = fixture.ports.playlists.requests.single()
            assertEquals(draft.operationId, request.operationId)
            assertEquals(draft.expectedRevision, request.expectedRevision)
            assertEquals(PlaylistMutation.Move(draft.memberId, 204L), request.mutation)
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun favoriteFeedbackIsImmediateFailureRollsBackAndRetryUsesTheSameSetValueIntent() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.await { it.favorite == false }
            fixture.ports.playlists.mutation = { _, _ ->
                release.await()
                throw PlaylistException(PlaylistIssue.STORAGE_FULL)
            }
            fixture.model.toggleFavorite()
            assertEquals(true, fixture.model.uiState.value.favorite)
            assertTrue(fixture.model.uiState.value.favoritePending)
            release.complete(Unit)
            fixture.await { it.issue == PlaylistUiIssue.STORAGE_FULL }
            assertEquals(false, fixture.model.uiState.value.favorite)
            fixture.ports.playlists.mutation = { request, _ ->
                fixture.ports.playlists.favoriteState.value = true
                val snapshot = fixture.ports.playlists.favoriteSnapshot.copy(revision = 1L, memberCount = 1L)
                fixture.ports.playlists.favoriteSnapshot = snapshot
                PlaylistCommit(request.playlistId, 1L, PlaylistOperationResult.FAVORITE_SET, snapshot, false)
            }
            fixture.model.toggleFavorite()
            fixture.await { it.favorite == true && !it.favoritePending && !it.saving }
            assertEquals(2, fixture.ports.playlists.requests.size)
            assertEquals(fixture.ports.playlists.requests[0], fixture.ports.playlists.requests[1])
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun pagedNavigationReplacesTheVisibleWindowAndUsesEveryMemberOrdinal() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            assertEquals(100, fixture.model.uiState.value.rows.size)
            fixture.model.nextMemberPage()
            fixture.await { it.pageStart == 100L && !it.loading }
            assertEquals(100, fixture.model.uiState.value.rows.size)
            fixture.model.nextMemberPage()
            fixture.await { it.pageStart == 200L && !it.loading }
            assertEquals(5, fixture.model.uiState.value.rows.size)
            fixture.model.previousMemberPage()
            fixture.await { it.pageStart == 100L && !it.loading }
            assertEquals(100, fixture.model.uiState.value.rows.size)
            assertEquals(listOf(0L, 100L, 200L, 100L), fixture.ports.playlists.pages)
            assertTrue(fixture.ports.mappedSizes.all { it <= 100 })
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun leavingOrReplacingTheMemberPageDropsItsSelectionAndFavoriteObserver() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.await { it.favorite == false }
            assertEquals(1, fixture.ports.playlists.favoriteObserverCount.value)
            fixture.model.returnToDetails()
            withTimeout(5_000L) { fixture.ports.playlists.favoriteObserverCount.first { it == 0 } }
            assertNull(fixture.model.uiState.value.selected)
            assertNull(fixture.model.uiState.value.favorite)

            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.await { it.favorite == false }
            fixture.model.nextMemberPage()
            fixture.await { it.pageStart == 100L && !it.loading }
            withTimeout(5_000L) { fixture.ports.playlists.favoriteObserverCount.first { it == 0 } }
            assertEquals(PlaylistScreen.DETAILS, fixture.model.uiState.value.screen)
            assertNull(fixture.model.uiState.value.selected)

            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.await { it.favorite == false }
            fixture.model.reload()
            fixture.await { it.pageStart == 100L && !it.loading && !it.showingPreviousData }
            withTimeout(5_000L) { fixture.ports.playlists.favoriteObserverCount.first { it == 0 } }
            assertEquals(PlaylistScreen.DETAILS, fixture.model.uiState.value.screen)
            assertNull(fixture.model.uiState.value.selected)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun playbackNeedsAnExplicitConfirmationAndAckAndRetryCannotChangeTheFrozenCommand() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val commands = mutableListOf<PlaylistQueueCommand>()
        try {
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows[2])
            fixture.model.preparePlay(PlaylistQueueBinding(null, 0L, null, 0L), QueueMode(), PlaylistQueueAction.REPLACE_COMPLETE_LIST, true)
            assertTrue(commands.isEmpty())
            fixture.model.confirmPlay { commands.add(it) }
            fixture.model.confirmPlay { commands.add(it) }
            assertEquals(1, commands.size)
            assertEquals(205L, commands.single().view.snapshot.memberCount)
            assertEquals(2L, commands.single().selection?.ordinal)
            assertTrue(fixture.model.uiState.value.playPending)
            fixture.model.acknowledgePlay("other", null)
            assertTrue(fixture.model.uiState.value.playPending)
            fixture.model.acknowledgePlay(commands.single().operationId, PlaylistUiIssue.CONFLICT)
            fixture.model.confirmPlay { commands.add(it) }
            assertEquals(2, commands.size)
            assertEquals(commands[0], commands[1])
            fixture.model.acknowledgePlay(commands[0].operationId, null)
            assertNull(fixture.model.uiState.value.playDraft)
            fixture.model.onHidden()
            fixture.model.onVisible()
            fixture.await { it.view != null && !it.loading && !it.showingPreviousData }
            assertEquals(2, commands.size)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun aLateIgnoringKeywordPreviewIsDiscardedAndItsLeaseClosesBeforeTheNextVisibleSession() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.model.onVisible()
            fixture.await { it.catalogPage != null && !it.loading }
            fixture.model.beginKeywordCreate()
            fixture.model.changeName("Keyword list")
            fixture.model.changeKeyword("%_[]🎵")
            fixture.keywords.previewResult = { keyword ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                KeywordPlaylistPreview("late-preview", keyword, fixture.revision(), 1L, false)
            }
            fixture.model.previewKeyword()
            withTimeout(5_000L) { entered.await() }
            val opened = fixture.ports.playlists.opened.size
            fixture.model.onHidden()
            fixture.model.onVisible()
            assertEquals(opened, fixture.ports.playlists.opened.size)
            release.complete(Unit)
            fixture.await { !it.loading && !it.showingPreviousData && it.keywordDraft?.preview == null }
            assertNull(fixture.model.uiState.value.keywordDraft?.preview)
            assertEquals(listOf("late-preview"), fixture.keywords.closed)
            assertEquals(listOf("%_[]🎵"), fixture.keywords.previews)
            assertTrue(fixture.keywords.creates.isEmpty())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun keywordGenerationRequiresSeparateBroadAndEmptyConfirmationsAndPassesTheLiteralKeyword() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.model.onVisible()
            fixture.await { it.catalogPage != null && !it.loading }
            fixture.model.beginKeywordCreate()
            fixture.model.changeName("Empty keyword list")
            fixture.model.previewKeyword()
            assertEquals(PlaylistUiIssue.INVALID_KEYWORD, fixture.model.uiState.value.issue)
            assertTrue(fixture.keywords.previews.isEmpty())
            fixture.keywords.broad = true
            fixture.keywords.count = 0L
            fixture.model.changeKeyword("%_[literal]")
            fixture.model.previewKeyword()
            fixture.await { it.keywordDraft?.preview != null && !it.loading }
            fixture.model.confirmKeyword()
            assertTrue(fixture.keywords.creates.isEmpty())
            fixture.model.confirmBroadKeyword(true)
            fixture.model.confirmKeyword()
            assertTrue(fixture.keywords.creates.isEmpty())
            fixture.model.confirmEmptyKeyword(true)
            fixture.model.confirmKeyword()
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            assertEquals(listOf("%_[literal]"), fixture.keywords.previews)
            assertTrue(fixture.keywords.creates.single().broadMatchConfirmed)
            assertTrue(fixture.keywords.creates.single().emptyMatchConfirmed)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun aCancelledLateInitializationCannotReplaceTheCurrentGenerationLibraryRevision() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val firstJob = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        try {
            val oldOverview = fixture.ports.library.overviewState.value
            fixture.ports.library.initialization = {
                calls += 1
                if (calls == 1) {
                    firstJob.complete(checkNotNull(currentCoroutineContext()[Job]))
                    withContext(NonCancellable) { release.await() }
                    oldOverview
                } else fixture.ports.library.overviewState.value
            }
            fixture.model.onVisible()
            val cancelled = withTimeout(5_000L) { firstJob.await() }
            fixture.model.onHidden()
            fixture.ports.library.overviewState.value = oldOverview.copy(revision = oldOverview.revision + 1L)
            fixture.model.onVisible()
            fixture.await { it.catalogPage != null && !it.loading }
            withTimeout(5_000L) { fixture.ports.library.overviewState.subscriptionCount.first { it > 0 } }
            fixture.model.beginKeywordCreate()
            fixture.model.changeName("Current revision list")
            fixture.model.changeKeyword("literal")
            fixture.model.previewKeyword()
            fixture.await { it.keywordDraft?.preview != null && !it.loading }
            release.complete(Unit)
            withTimeout(5_000L) { cancelled.join() }
            assertTrue(cancelled.isCancelled)
            fixture.model.confirmKeyword()
            assertEquals(1, fixture.keywords.creates.size)
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun folderRebindingAndMemberRulesNeedExplicitActions() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            val original = checkNotNull(fixture.ports.playlists.detail.value)
            fixture.ports.playlists.detail.value = original.copy(kind = PlaylistKind.FOLDER,
                folderBinding = PlaylistFolderBinding("source", "old-folder"))
            fixture.showDetails()
            fixture.model.selectMember(fixture.model.uiState.value.rows.first())
            fixture.model.beginRemove()
            assertNull(fixture.model.uiState.value.removeDraft)
            fixture.model.beginMove()
            assertNotNull(fixture.model.uiState.value.moveDraft)
            fixture.model.returnToDetails()
            val choice = PlaylistFolderChoice(PlaylistFolderBinding("source", "new-folder"), "New folder")
            fixture.model.beginFolderRebind()
            fixture.model.chooseFolder(choice)
            fixture.await { it.folderDraft?.preview != null && !it.loading }
            fixture.model.returnToDetails()
            assertTrue(fixture.folders.rebindings.isEmpty())
            fixture.model.beginFolderRebind()
            fixture.model.chooseFolder(choice)
            val preview = checkNotNull(fixture.await { it.folderDraft?.preview != null && !it.loading }.folderDraft?.preview)
            fixture.model.confirmFolder()
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            assertEquals(preview.previewId, fixture.folders.rebindings.single().previewId)
            assertEquals(original.revision, fixture.folders.rebindings.single().expectedRevision)
            assertEquals(choice.binding, fixture.model.uiState.value.view?.snapshot?.folderBinding)
            assertEquals(PlaylistKind.FOLDER, fixture.model.uiState.value.view?.snapshot?.kind)
            assertEquals(original.revision + 1L, fixture.model.uiState.value.view?.snapshot?.revision)
            assertTrue(fixture.ports.playlists.requests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun completeMultiSelectionIsPassedToTheStoreWithoutKeepingAllReferencesInThePage() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val selection = TestAddSelection()
        try {
            fixture.model.onVisible()
            fixture.await { it.catalogPage != null && !it.loading }
            assertTrue(fixture.model.beginAdd(selection))
            fixture.await { it.screen == PlaylistScreen.ADD_TARGETS && !it.loading }
            assertEquals(10_005L, fixture.model.uiState.value.addCount)
            fixture.model.chooseAddTarget(checkNotNull(fixture.ports.playlists.detail.value))
            fixture.ports.playlists.mutation = { request, source ->
                assertSame(selection.source, source)
                assertEquals(10_005L, (request.mutation as PlaylistMutation.Add).input.totalCount)
                PlaylistCommit(request.playlistId, request.expectedRevision, PlaylistOperationResult.UNCHANGED,
                    fixture.ports.playlists.detail.value, false)
            }
            fixture.model.confirmAdd()
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            assertEquals(1, fixture.ports.playlists.requests.size)
            assertEquals(1, selection.closes)
            assertEquals(0, selection.reads)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun importPreviewPreservesAllPagesAndUnresolvedMembersRequireConfirmation() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            fixture.model.beginImport(PlaylistTransferFormat.JSON)
            fixture.model.inspectImportDocument(AuthorizedPlaylistDocument("selected", "Playlist document"))
            fixture.await { it.importPage != null && !it.loading }
            assertEquals(100, fixture.model.uiState.value.importPage?.rows?.size)
            fixture.model.confirmImport()
            assertTrue(fixture.exchange.confirmations.isEmpty())
            fixture.model.nextImportPage()
            fixture.await { it.importPage?.firstOrdinal == 100L && !it.loading }
            assertEquals(100, fixture.model.uiState.value.importPage?.rows?.size)
            fixture.model.nextImportPage()
            fixture.await { it.importPage?.firstOrdinal == 200L && !it.loading }
            assertEquals(5, fixture.model.uiState.value.importPage?.rows?.size)
            assertEquals(listOf(200L, 201L, 202L, 203L, 204L), fixture.model.uiState.value.importPage?.rows?.map { it.ordinal })
            fixture.model.confirmUnresolvedImport(true)
            fixture.model.confirmImport()
            fixture.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            assertTrue(fixture.exchange.confirmations.single().unresolvedConfirmed)
            assertTrue(fixture.exchange.confirmations.single().destination is PlaylistImportDestination.New)
            assertEquals(listOf("import-preview-1"), fixture.exchange.closed)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun importRecreationKeepsNameFormatAndExactMergeIntentButRestoresNoDocumentOrPreviewAuthority() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        var recreated: PlaylistsViewModel? = null
        try {
            fixture.showDetails()
            fixture.model.beginImport(PlaylistTransferFormat.M3U, PlaylistTransferEncoding.GB18030, mergeIntoCurrent = true)
            val document = AuthorizedPlaylistDocument("selected-document", "Input")
            fixture.model.inspectImportDocument(document)
            fixture.await { it.importPage != null && !it.loading }
            fixture.model.changeImportName("Preserved import name")
            fixture.model.confirmUnresolvedImport(true)
            val draft = checkNotNull(fixture.model.uiState.value.importDraft)
            val handle = fixture.copySavedState()
            assertTrue(handle.keys().none { handle.get<Any?>(it) == document || handle.get<Any?>(it) == document.selectionId })
            fixture.close()

            val next = fixture.newModel(handle)
            recreated = next
            next.onVisible()
            val state = withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.IMPORT && it.view != null && !it.loading } }
            val restored = checkNotNull(state.importDraft)
            assertEquals(draft.operationId, restored.operationId)
            assertEquals(draft.newPlaylistId, restored.newPlaylistId)
            assertEquals(draft.name, restored.name)
            assertEquals(draft.format, restored.format)
            assertEquals(draft.encoding, restored.encoding)
            assertEquals(draft.target, restored.target)
            assertNull(restored.document)
            assertNull(restored.preview)
            assertFalse(restored.unresolvedConfirmed)
            assertNull(state.importPage)
            assertEquals(1, fixture.exchange.imports.size)
            assertTrue(fixture.exchange.confirmations.isEmpty())
            next.confirmImport()
            assertTrue(fixture.exchange.confirmations.isEmpty())
            next.inspectImportDocument(AuthorizedPlaylistDocument("reselected-document", null))
            withTimeout(5_000L) { next.uiState.first { it.importPage != null && !it.loading } }
            assertEquals("Preserved import name", next.uiState.value.importDraft?.name)
            next.confirmImport()
            assertTrue(fixture.exchange.confirmations.isEmpty())
            next.confirmUnresolvedImport(true)
            next.confirmImport()
            withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.DETAILS && !it.loading } }
            assertEquals(PlaylistImportDestination.Merge(checkNotNull(draft.target)), fixture.exchange.confirmations.single().destination)
        } finally {
            val next = recreated
            if (next == null) fixture.close() else fixture.close(next)
        }
    }

    @Test(timeout = 30_000L)
    fun aMalformedSavedMergeRevisionCannotSilentlyRestoreAnImportAsANewPlaylist() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val recreated = mutableListOf<PlaylistsViewModel>()
        try {
            fixture.showDetails()
            fixture.model.beginImport(PlaylistTransferFormat.JSON, mergeIntoCurrent = true)
            val handles = listOf<Any?>("invalid", -1L, null).map { value ->
                fixture.copySavedState().also { it["playlists.importTargetRevision"] = value }
            }
            fixture.close()
            for (handle in handles) {
                val next = fixture.newModel(handle)
                recreated.add(next)
                assertNull(next.uiState.value.importDraft)
                next.onVisible()
                withTimeout(5_000L) { next.uiState.first { it.view != null && !it.loading } }
                next.confirmImport()
                assertTrue(fixture.exchange.imports.isEmpty())
                assertTrue(fixture.exchange.confirmations.isEmpty())
                next.onHidden()
            }
        } finally {
            fixture.close(*recreated.toTypedArray())
        }
    }

    @Test(timeout = 30_000L)
    fun selectingAnotherDocumentWaitsForTheCancelledPreviewAndKeepsOnlyTheNewSelection() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.showDetails()
            fixture.model.beginImport(PlaylistTransferFormat.JSON)
            val originalResult = fixture.exchange.inspectResult
            fixture.exchange.inspectResult = { format, encoding, document, target ->
                if (document.selectionId == "slow") {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                originalResult(format, encoding, document, target)
            }
            val original = AuthorizedPlaylistDocument("slow", "Earlier selection")
            val replacement = AuthorizedPlaylistDocument("replacement", "Current selection")
            fixture.model.inspectImportDocument(original)
            withTimeout(5_000L) { entered.await() }
            fixture.model.inspectImportDocument(replacement)
            assertEquals(listOf(original), fixture.exchange.imports)
            assertEquals(replacement, fixture.model.uiState.value.importDraft?.document)
            release.complete(Unit)
            val state = fixture.await { it.importDraft?.preview?.previewId == "import-preview-2" && !it.loading }
            assertEquals(replacement, state.importDraft?.document)
            assertEquals(listOf(original, replacement), fixture.exchange.imports)
            assertEquals(listOf("import-preview-1"), fixture.exchange.closed)
            assertTrue(fixture.exchange.confirmations.isEmpty())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun importRepreviewKeepsNamesEditedWhileReadingAndDoesNotReplaceAnIntentionalEmptyName() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.showDetails()
            fixture.model.beginImport(PlaylistTransferFormat.JSON)
            fixture.model.inspectImportDocument(AuthorizedPlaylistDocument("selected", null))
            fixture.await { it.importPage != null && !it.loading }
            fixture.model.changeImportName("Before preview")
            val originalResult = fixture.exchange.inspectResult
            fixture.exchange.inspectResult = { format, encoding, document, target ->
                entered.complete(Unit)
                release.await()
                originalResult(format, encoding, document, target)
            }
            fixture.model.previewImportAgain()
            withTimeout(5_000L) { entered.await() }
            fixture.model.changeImportName("Edited during preview")
            release.complete(Unit)
            val state = fixture.await { it.importDraft?.preview?.previewId == "import-preview-2" && !it.loading }
            assertEquals("Edited during preview", state.importDraft?.name)
            fixture.exchange.inspectResult = originalResult
            fixture.model.changeImportName("")
            fixture.model.previewImportAgain()
            fixture.await { it.importDraft?.preview?.previewId == "import-preview-3" && !it.loading }
            assertEquals("", fixture.model.uiState.value.importDraft?.name)
            fixture.model.confirmUnresolvedImport(true)
            fixture.model.confirmImport()
            assertEquals(PlaylistUiIssue.INVALID_NAME, fixture.model.uiState.value.issue)
            assertTrue(fixture.exchange.confirmations.isEmpty())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun catalogChangesInvalidateAnImportPreviewWithoutChangingTheOriginalPlaylist() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            val original = fixture.ports.playlists.detail.value
            fixture.model.beginImport(PlaylistTransferFormat.JSON, mergeIntoCurrent = true)
            fixture.model.inspectImportDocument(AuthorizedPlaylistDocument("selected", null))
            fixture.await { it.importPage != null && !it.loading }
            fixture.model.confirmUnresolvedImport(true)
            fixture.ports.library.overviewState.value = fixture.ports.library.overviewState.value.copy(revision = 10L)
            fixture.await { it.previewStale }
            fixture.model.confirmImport()
            assertEquals(PlaylistUiIssue.SOURCE_CHANGED, fixture.model.uiState.value.issue)
            assertTrue(fixture.exchange.confirmations.isEmpty())
            assertEquals(original, fixture.ports.playlists.detail.value)
            val firstId = fixture.model.uiState.value.importDraft?.operationId
            fixture.model.previewImportAgain()
            fixture.await { it.importPage != null && !it.loading && !it.previewStale }
            assertNotEquals(firstId, fixture.model.uiState.value.importDraft?.operationId)
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun exportShowsSuccessOnlyAfterTheControllerReturnsAVerifiedDocumentReceipt() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.showDetails()
            fixture.model.beginExport(PlaylistTransferFormat.JSON)
            val originalResult = fixture.exchange.exportResult
            fixture.exchange.exportResult = { id, view ->
                release.await()
                originalResult(id, view)
            }
            fixture.model.exportTo(AuthorizedPlaylistDocument("destination", "Output"))
            assertTrue(fixture.model.uiState.value.saving)
            assertNull(fixture.model.uiState.value.exportReceipt)
            release.complete(Unit)
            fixture.await { it.exportReceipt != null && !it.saving }
            assertEquals(205L, fixture.model.uiState.value.exportReceipt?.memberCount)
            fixture.model.beginExport(PlaylistTransferFormat.M3U8)
            fixture.exchange.exportResult = { _, _ -> throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED) }
            fixture.model.exportTo(AuthorizedPlaylistDocument("another-destination", null))
            fixture.await { it.issue == PlaylistUiIssue.DOCUMENT_WRITE }
            assertNull(fixture.model.uiState.value.exportReceipt)
            assertEquals(205L, fixture.model.uiState.value.view?.snapshot?.memberCount)
            val failedOperation = fixture.exchange.exports.last()
            fixture.model.exportSelectedDocument()
            fixture.await { it.issue == PlaylistUiIssue.DOCUMENT_WRITE }
            assertEquals(failedOperation, fixture.exchange.exports.last())
            fixture.exchange.exportResult = originalResult
            fixture.model.exportTo(AuthorizedPlaylistDocument("replacement-destination", null))
            fixture.await { it.exportReceipt != null && !it.saving }
            assertNotEquals(failedOperation, fixture.exchange.exports.last())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun aPickerResultReceivedWhileHiddenContinuesOnceVisibleButRecreationCannotReplayExport() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        var recreated: PlaylistsViewModel? = null
        try {
            fixture.showDetails()
            fixture.model.beginExport(PlaylistTransferFormat.JSON)
            fixture.model.onHidden()
            fixture.model.exportTo(AuthorizedPlaylistDocument("destination", null))
            assertTrue(fixture.exchange.exports.isEmpty())
            fixture.model.onVisible()
            fixture.await { it.exportReceipt != null && !it.saving }
            assertEquals(1, fixture.exchange.exports.size)
            fixture.close()
            val next = fixture.newModel()
            recreated = next
            next.onVisible()
            withTimeout(5_000L) { next.uiState.first { it.screen == PlaylistScreen.DETAILS && it.view != null && !it.loading } }
            assertEquals(1, fixture.exchange.exports.size)
        } finally {
            val next = recreated
            if (next == null) fixture.close() else fixture.close(next)
        }
    }

    @Test(timeout = 30_000L)
    fun failedLeaseCleanupBlocksNewViewsUntilExplicitReloadCanReleaseTheOriginalLease() = runBlocking {
        val fixture = PlaylistUiTestFixture(this)
        try {
            fixture.showDetails()
            val opened = fixture.ports.playlists.opened.size
            fixture.ports.playlists.closeFailures = 100
            fixture.model.reload()
            fixture.await { it.issue == PlaylistUiIssue.BUSY && !it.loading }
            assertEquals(opened, fixture.ports.playlists.opened.size)
            assertTrue(fixture.model.uiState.value.showingPreviousData)
            fixture.ports.playlists.closeFailures = 0
            fixture.model.reload()
            fixture.await { it.view != null && !it.loading && !it.showingPreviousData }
            assertEquals(opened + 1, fixture.ports.playlists.opened.size)
        } finally {
            fixture.ports.playlists.closeFailures = 0
            fixture.close()
        }
    }

    private class TestAddSelection : PlaylistAddSelection {
        override val input = FrozenPlaylistInput("selection", 1L, 10_005L)
        override val label = "Selected tracks"
        var reads = 0
        var closes = 0
        override val source = PlaylistPageSource { offset, limit ->
            reads += 1
            val end = minOf(input.totalCount, offset + limit)
            PlaylistInputPage(input, offset, (offset until end).map { ordinal ->
                PlaylistReference(trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "selected-$ordinal"))
            }, end == input.totalCount)
        }

        override suspend fun close() { closes += 1 }
    }
}
