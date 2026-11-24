package io.github.onlinemo.meloxwatch.ui.playlists

import android.content.Intent
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeException
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeIssue
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** Regression definitions for real host contracts. Execution belongs to the cloud build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaylistEntryViewModelTest {
    @Test(timeout = 30_000L)
    fun unknownOrFailedQueueKeepsPlaylistManagementAvailableAndObservedEmptyIsDistinct() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        val gate = CompletableDeferred<Unit>()
        try {
            fixture.queues.gate = gate
            fixture.queues.readFailure = LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
            fixture.showCatalog()
            assertNull(fixture.entry.uiState.value.queue)
            fixture.ui.model.openPlaylist(checkNotNull(fixture.ui.ports.playlists.detail.value))
            val details = fixture.ui.await { it.canUseView }
            assertTrue(details.canEditMembers)
            assertFalse(playlistQueueControlsEnabled(details, null, null, false))
            fixture.ui.model.beginRename()
            assertEquals(PlaylistScreen.NAME, fixture.ui.model.uiState.value.screen)
            gate.complete(Unit)
            fixture.await { it.queueIssue == PlaylistUiIssue.STORAGE_UNAVAILABLE }
            assertNull(fixture.entry.uiState.value.queue)
            fixture.queues.readFailure = null
            fixture.entry.retryQueueRead()
            val empty = fixture.await { it.queue != null }
            assertEquals(PlaylistQueueBinding(null, 0L, null, 0L), empty.queue)
            assertEquals(QueueMode(), empty.mode)
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { gate.complete(Unit); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun pickerRequestIsConsumedOnceAndTheSameRuntimeFlightSurvivesStop() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            val request = fixture.importRequest()
            assertEquals(request, fixture.entry.takeDocumentRequest(request.requestId))
            assertNull(fixture.entry.takeDocumentRequest(request.requestId))
            fixture.entry.onHidden()
            fixture.ui.model.onHidden()
            fixture.ui.model.onVisible()
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
            fixture.ui.await { !it.loading && it.catalogPage != null }
            assertNull(fixture.entry.uiState.value.documentRequest)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/input"), READ)
            fixture.ui.await { it.importDraft?.preview != null && !it.loading }
            assertEquals(1, fixture.documents.registered.size)
            assertEquals(READ, fixture.documents.registered.single().flags)
            assertNull(fixture.documents.registered.single().root)
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun freshViewModelRestoresTextWithoutReplayingAnOldDocumentResultOrGrant() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        var restored: PlaylistsViewModel? = null
        var restoredEntry: PlaylistEntryViewModel? = null
        try {
            fixture.showCatalog()
            fixture.ui.model.beginImport(PlaylistTransferFormat.JSON)
            fixture.ui.model.changeImportName("Kept text")
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            val request = checkNotNull(fixture.entry.uiState.value.documentRequest)
            assertNotNull(fixture.entry.takeDocumentRequest(request.requestId))
            val handle = fixture.ui.copySavedState()
            fixture.entry.onHidden()
            fixture.ui.model.onHidden()
            val next = fixture.ui.newModel(handle)
            val nextEntry = PlaylistEntryViewModel(next, fixture.ui.ports.playlists, fixture.library,
                fixture.queues, fixture.documents, fixture.recovery, fixture.scope,
                closeLibraryView = fixture.library::closeView)
            restored = next
            restoredEntry = nextEntry
            next.onVisible()
            nextEntry.onVisible(PlaylistEntrySurface.PLAYLISTS)
            nextEntry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/late"), READ)
            awaitEntryCondition { !next.uiState.value.loading && next.uiState.value.catalogPage != null }
            assertEquals("Kept text", next.uiState.value.importDraft?.name)
            assertNull(next.uiState.value.importDraft?.document)
            assertNull(nextEntry.uiState.value.documentRequest)
            assertTrue(fixture.documents.registered.isEmpty())
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally {
            restoredEntry?.onVisible(PlaylistEntrySurface.OTHER)
            restoredEntry?.onHidden()
            restored?.onHidden()
            fixture.close()
        }
    }

    @Test(timeout = 30_000L)
    fun oldFlightIsATombstoneUntilItsResultArrivesAndCannotAttachToANewDraft() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            val first = fixture.importRequest()
            fixture.entry.takeDocumentRequest(first.requestId)
            fixture.entry.onVisible(PlaylistEntrySurface.OTHER)
            fixture.ui.model.onVisible()
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
            fixture.ui.await { !it.loading && it.catalogPage != null }
            fixture.ui.model.beginImport(PlaylistTransferFormat.JSON)
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            assertEquals(PlaylistUiIssue.BUSY, fixture.entry.uiState.value.issue)
            fixture.entry.documentResult(PlaylistDocumentPurpose.EXPORT, Uri.parse("content://picker/wrong"), READ)
            assertTrue(fixture.entry.uiState.value.pickerPending)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/stale"), READ)
            assertTrue(fixture.documents.registered.isEmpty())
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            val next = checkNotNull(fixture.entry.uiState.value.documentRequest)
            assertNotEquals(first.requestId, next.requestId)
            assertNotEquals(first.generation, next.generation)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun changedFormatRejectsThePreviousPickerResultAndCancellationKeepsTheNewDraft() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            val request = fixture.importRequest()
            fixture.entry.takeDocumentRequest(request.requestId)
            fixture.ui.model.beginImport(PlaylistTransferFormat.M3U)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/old-json"), READ)
            assertTrue(fixture.documents.registered.isEmpty())
            assertEquals(PlaylistTransferFormat.M3U, fixture.ui.model.uiState.value.importDraft?.format)
            fixture.ui.model.beginImport(PlaylistTransferFormat.JSON)
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            val second = checkNotNull(fixture.entry.uiState.value.documentRequest)
            fixture.entry.takeDocumentRequest(second.requestId)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, null, 0)
            assertEquals(PlaylistEntryNotice.PICKER_CANCELLED, fixture.entry.uiState.value.notice)
            assertNotNull(fixture.ui.model.uiState.value.importDraft)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun m3uRequiresAnExplicitIndexedAuthorizedRootAndNeverUsesTheDocumentParent() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            fixture.ui.model.beginImport(PlaylistTransferFormat.M3U8)
            fixture.entry.chooseImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8)
            fixture.await { it.folders != null && !it.loading }
            assertNull(fixture.entry.uiState.value.documentRequest)
            fixture.entry.browseFolder(fixture.library.root)
            fixture.await { it.folder == fixture.library.root && !it.loading }
            fixture.entry.selectFolder()
            val request = checkNotNull(fixture.await { it.documentRequest != null && !it.loading }.documentRequest)
            assertEquals(PlaylistFolderBinding("tree", "indexed-root"), request.root?.binding)
            fixture.entry.takeDocumentRequest(request.requestId)
            val uri = Uri.parse("content://another-provider/unrelated/playlists/file.m3u8")
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, uri, READ)
            fixture.ui.await { it.importDraft?.preview != null && !it.loading }
            val registration = fixture.documents.registered.single()
            assertEquals(uri, registration.uri)
            assertEquals(request.root?.binding, registration.root)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun revokedRootPreventsDocumentLaunchAndFolderCancellationClosesItsIndexView() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            fixture.ui.model.beginImport(PlaylistTransferFormat.M3U8)
            fixture.entry.chooseImport(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8)
            fixture.await { it.folders != null && !it.loading }
            fixture.entry.browseFolder(fixture.library.root)
            fixture.await { it.folder == fixture.library.root && !it.loading }
            fixture.library.sourceRead = io.github.onlinemo.meloxwatch.data.local.ObservedCapability.UNAVAILABLE
            fixture.entry.selectFolder()
            fixture.await { it.issue == PlaylistUiIssue.SOURCE_UNAVAILABLE && !it.loading }
            assertNull(fixture.entry.uiState.value.documentRequest)
            fixture.entry.closePicker()
            awaitEntryCondition { fixture.ui.ports.library.closed.isNotEmpty() }
            assertTrue(fixture.documents.registered.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun lateRegistrationAfterNavigationDiscardsTheReturnedTokenAndCannotRestoreAccess() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        val registered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.documents.registration = { document -> withContext(NonCancellable) {
                registered.complete(Unit)
                release.await()
                document
            } }
            fixture.showCatalog()
            val request = fixture.importRequest()
            fixture.entry.takeDocumentRequest(request.requestId)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/late"), READ)
            registered.await()
            fixture.entry.onVisible(PlaylistEntrySurface.OTHER)
            release.complete(Unit)
            awaitEntryCondition { fixture.documents.discarded == listOf("document-1") }
            assertNull(fixture.ui.model.uiState.value.importDraft?.document)
            assertTrue(fixture.ui.exchange.imports.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun stopRetainsTheExactTokenButNavigationClearsAccessWhileKeepingImportTextAndFormat() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            val request = fixture.importRequest()
            fixture.entry.takeDocumentRequest(request.requestId)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/input"), READ)
            fixture.ui.await { it.importDraft?.preview != null && !it.loading }
            fixture.ui.model.changeImportName("Edited name")
            val draft = checkNotNull(fixture.ui.model.uiState.value.importDraft)
            fixture.entry.onHidden()
            fixture.ui.model.onHidden()
            assertEquals(draft.document, fixture.ui.model.uiState.value.importDraft?.document)
            assertTrue(fixture.documents.discarded.isEmpty())
            fixture.ui.model.onVisible()
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
            fixture.ui.await { it.importDraft?.preview != null && !it.loading }
            fixture.entry.onVisible(PlaylistEntrySurface.OTHER)
            awaitEntryCondition { fixture.documents.discarded == listOf("document-1") }
            val kept = checkNotNull(fixture.ui.model.uiState.value.importDraft)
            assertEquals("Edited name", kept.name)
            assertEquals(draft.format, kept.format)
            assertNull(kept.document)
            assertNull(kept.preview)
            assertFalse(kept.unresolvedConfirmed)
            assertNotEquals(draft.operationId, kept.operationId)
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun readFailureRetriesTheSameAuthorizedDocumentAndSuccessfulImportRetiresIt() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            val normalInspect = fixture.ui.exchange.inspectResult
            fixture.ui.exchange.inspectResult = { _, _, _, _ -> throw PlaylistExchangeException(PlaylistExchangeIssue.READ_FAILED) }
            val request = fixture.importRequest()
            fixture.entry.takeDocumentRequest(request.requestId)
            fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/input"), READ)
            fixture.ui.await { it.issue == PlaylistUiIssue.DOCUMENT_READ && !it.loading }
            val document = checkNotNull(fixture.ui.model.uiState.value.importDraft?.document)
            assertTrue(fixture.documents.discarded.isEmpty())
            fixture.ui.exchange.inspectResult = normalInspect
            fixture.ui.model.previewImportAgain()
            fixture.ui.await { it.importDraft?.preview != null && !it.loading }
            assertEquals(document, fixture.ui.model.uiState.value.importDraft?.document)
            assertEquals(1, fixture.documents.registered.size)
            fixture.ui.model.confirmUnresolvedImport(true)
            fixture.ui.model.confirmImport()
            fixture.ui.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            awaitEntryCondition { fixture.documents.discarded == listOf(document.selectionId) }
            assertEquals(1, fixture.ui.exchange.confirmations.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun exportRegistrationBindsTheExactOperationAndAReplacementDestinationGetsANewIntent() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.ui.showDetails()
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
            fixture.ui.model.beginExport(PlaylistTransferFormat.JSON)
            fixture.ui.exchange.exportResult = { _, _ -> throw PlaylistExchangeException(PlaylistExchangeIssue.WRITE_FAILED) }
            fixture.entry.chooseExport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            val first = checkNotNull(fixture.entry.uiState.value.documentRequest)
            fixture.entry.takeDocumentRequest(first.requestId)
            fixture.entry.documentResult(PlaylistDocumentPurpose.EXPORT, Uri.parse("content://picker/output-one"), WRITE)
            fixture.ui.await { it.issue == PlaylistUiIssue.DOCUMENT_WRITE && !it.saving }
            assertEquals(first.operationId, fixture.documents.registered.single().operationId)
            fixture.entry.chooseExport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            val second = checkNotNull(fixture.entry.uiState.value.documentRequest)
            assertNotEquals(first.operationId, second.operationId)
            fixture.entry.takeDocumentRequest(second.requestId)
            fixture.entry.documentResult(PlaylistDocumentPurpose.EXPORT, Uri.parse("content://picker/output-two"), WRITE)
            fixture.ui.await { it.exportDraft?.destination?.selectionId == "document-2" && !it.saving }
            awaitEntryCondition { "document-1" in fixture.documents.discarded }
            assertEquals(second.operationId, fixture.documents.registered.last().operationId)
            assertEquals(listOf(first.operationId, second.operationId), fixture.ui.exchange.exports)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun failedTokenCleanupIsRetainedAndBoundedUntilExplicitCleanupSucceeds() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            fixture.documents.discardFailures = 100
            repeat(2) {
                val request = fixture.importRequest()
                fixture.entry.takeDocumentRequest(request.requestId)
                fixture.entry.documentResult(PlaylistDocumentPurpose.IMPORT, Uri.parse("content://picker/input-$it"), READ)
                fixture.ui.await { it.importDraft?.preview != null && !it.loading }
                fixture.ui.model.showCatalog()
                fixture.ui.await { it.catalogPage != null && !it.loading }
                fixture.await { it.issue == PlaylistUiIssue.STORAGE_UNAVAILABLE }
            }
            fixture.ui.model.beginImport(PlaylistTransferFormat.JSON)
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            assertNull(fixture.entry.uiState.value.documentRequest)
            assertEquals(PlaylistUiIssue.BUSY, fixture.entry.uiState.value.issue)
            fixture.documents.discardFailures = 0
            fixture.entry.retryCleanup()
            awaitEntryCondition { fixture.documents.discarded.toSet() == setOf("document-1", "document-2") }
            fixture.await { it.issue == null }
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            assertNotNull(fixture.entry.uiState.value.documentRequest)
        } finally { fixture.documents.discardFailures = 0; fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun completeLibrarySelectionStreamsBeyondTenThousandAndRetriesTheSameLeaseAtZero() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        var selection: LibraryPlaylistAddSelection? = null
        try {
            val view = fixture.ui.ports.library.view
            val lease = fixture.library.openQueueView(view.viewId)
            val selected = LibraryPlaylistAddSelection(fixture.library, lease, "Complete view",
                closeLibraryView = fixture.library::closeView)
            selection = selected
            val first = selected.source.read(0L, 100)
            assertSame(first, selected.source.read(0L, 100))
            assertEquals(listOf(0L), fixture.library.queueOffsets)
            fixture.library.failQueueOffset = 100L
            try { selected.source.read(100L, 100); error("Expected interrupted source") }
            catch (error: LibraryException) { assertEquals(LibraryIssue.STORAGE_UNAVAILABLE, error.issue) }
            fixture.library.failQueueOffset = null
            var total = 0L
            while (total < selected.input.totalCount) {
                val page = selected.source.read(total, 100)
                total += page.references.size
                assertEquals(total == selected.input.totalCount, page.endReached)
            }
            assertEquals(20_005L, total)
            assertTrue(fixture.library.queueSizes.all { it <= 100 })
            assertEquals(1, fixture.ui.ports.library.queueOpens)
            assertTrue(fixture.library.queueSourceViews.all { it == lease })
            assertEquals(2, fixture.library.queueSourceViews.size)
        } finally { selection?.close(); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun wrongSourceFenceOrOrdinalRejectsTheWholePageAndCanRestartWithoutAdoptingLatest() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        var selection: LibraryPlaylistAddSelection? = null
        try {
            val lease = fixture.library.openQueueView(fixture.ui.ports.library.view.viewId)
            val selected = LibraryPlaylistAddSelection(fixture.library, lease, null,
                closeLibraryView = fixture.library::closeView)
            selection = selected
            fixture.library.pageTransform = { it.copy(view = FrozenQueueView("another-view", it.view.revision, it.view.totalCount)) }
            try { selected.source.read(0L, 100); error("Expected source mismatch") }
            catch (error: PlaylistException) { assertEquals(PlaylistIssue.INCOMPLETE, error.issue) }
            fixture.library.pageTransform = { it.copy(entries = it.entries.mapIndexed { index, row ->
                if (index == 3) row.copy(originalOrder = 4L) else row
            }) }
            try { selected.source.read(0L, 100); error("Expected ordinal mismatch") }
            catch (error: PlaylistException) { assertEquals(PlaylistIssue.INCOMPLETE, error.issue) }
            fixture.library.pageTransform = { it }
            assertEquals(100, selected.source.read(0L, 100).references.size)
            assertEquals(1, fixture.ui.ports.library.queueOpens)
        } finally { selection?.close(); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun libraryToPlaylistHandoffTransfersTheCompleteLeaseAndClosesItAfterCommit() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.entry.onVisible(PlaylistEntrySurface.LIBRARY)
            val view = fixture.ui.ports.library.view
            fixture.entry.addFromLibrary(view, null, "Entire list") {
                fixture.ui.model.onVisible()
                fixture.entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
            }
            fixture.ui.await { it.screen == PlaylistScreen.ADD_TARGETS && !it.loading }
            assertEquals(20_005L, fixture.ui.model.uiState.value.addCount)
            fixture.library.closeView(view.viewId)
            var total = 0L
            fixture.ui.ports.playlists.mutation = { request, source ->
                val input = (request.mutation as PlaylistMutation.Add).input
                while (total < input.totalCount) total += checkNotNull(source).read(total, 100).references.size
                val snapshot = checkNotNull(fixture.ui.ports.playlists.detail.value).copy(revision = 8L)
                fixture.ui.ports.playlists.detail.value = snapshot
                PlaylistCommit(snapshot.playlistId, snapshot.revision, PlaylistOperationResult.ADDED, snapshot, false, addedCount = total)
            }
            fixture.ui.model.chooseAddTarget(checkNotNull(fixture.ui.ports.playlists.detail.value))
            fixture.ui.model.confirmAdd()
            fixture.ui.await { it.screen == PlaylistScreen.DETAILS && !it.loading }
            awaitEntryCondition { fixture.ui.ports.library.closed.any { it.startsWith("library-adopt-") } }
            assertEquals(20_005L, total)
            assertEquals(1, fixture.ui.ports.library.queueOpens)
            assertTrue(fixture.library.queueSizes.all { it <= 100 })
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun failedAdoptionCleanupKeepsItsLeaseAndBlocksAnotherAddUntilReleased() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.entry.onVisible(PlaylistEntrySurface.LIBRARY)
            fixture.library.missingTrack = true
            fixture.ui.ports.library.closeFailures = 1
            val view = fixture.ui.ports.library.view
            fixture.entry.addFromLibrary(view, fixture.library.track().trackKey, "One") { error("Unexpected navigation") }
            fixture.await { it.issue != null && !it.addPreparing }
            fixture.entry.addFromLibrary(view, null, null) { error("Unexpected navigation") }
            assertEquals(PlaylistUiIssue.BUSY, fixture.entry.uiState.value.issue)
            assertEquals(1, fixture.ui.ports.library.queueOpens)
            fixture.entry.retryCleanup()
            awaitEntryCondition { fixture.ui.ports.library.closed.any { it.startsWith("library-adopt-") } }
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun favoriteRollsBackOnFailureAndExplicitRetryReusesTheOriginalMutation() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.entry.onVisible(PlaylistEntrySurface.LIBRARY)
            fixture.entry.setLibraryFavorite(fixture.library.track().playlistReference())
            fixture.await { it.favorite == false }
            fixture.ui.ports.playlists.mutation = { _, _ ->
                saving.complete(Unit)
                release.await()
                throw PlaylistException(PlaylistIssue.STORAGE_FULL)
            }
            fixture.entry.toggleFavorite()
            saving.await()
            assertEquals(true, fixture.entry.uiState.value.favorite)
            assertTrue(fixture.entry.uiState.value.favoritePending)
            release.complete(Unit)
            fixture.await { it.favoriteIssue == PlaylistUiIssue.STORAGE_FULL && !it.favoritePending }
            assertEquals(false, fixture.entry.uiState.value.favorite)
            fixture.ui.ports.playlists.mutation = { _, _ ->
                fixture.ui.ports.playlists.favoriteState.value = true
                val snapshot = fixture.ui.ports.playlists.favoriteSnapshot
                PlaylistCommit(snapshot.playlistId, snapshot.revision, PlaylistOperationResult.FAVORITE_SET, snapshot, false)
            }
            fixture.entry.toggleFavorite()
            fixture.await { it.favorite == true && !it.favoritePending }
            assertEquals(2, fixture.ui.ports.playlists.requests.size)
            assertEquals(fixture.ui.ports.playlists.requests[0], fixture.ui.ports.playlists.requests[1])
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun playingMetadataAndFavoriteResolveTheExactQueueInstanceThroughTheRealLibraryPort() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.queues.current(fixture.library.track(4L).trackKey)
            fixture.entry.setPlayingEntry("current")
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYBACK)
            val playing = fixture.await { it.favoriteReference != null && it.favorite != null }
            assertEquals(fixture.library.track(4L), playing.playingTrack)
            assertEquals(fixture.library.track(4L).trackKey, playing.favoriteReference?.trackKey)
            fixture.entry.setPlayingEntry("different-instance")
            assertNull(fixture.entry.uiState.value.favoriteReference)
            assertNull(fixture.entry.uiState.value.playingTrack)
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun wrongPlayingWindowGenerationRevisionOrCountCannotSupplyMetadataAndExplicitReadCanRecover() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.queues.current()
            fixture.queues.windowTransform = { it.copy(generationId = "another-generation") }
            fixture.entry.setPlayingEntry("current")
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYBACK)
            fixture.await { it.favoriteIssue == PlaylistUiIssue.SOURCE_CHANGED }
            assertTrue(fixture.library.locateCalls.isEmpty())
            fixture.queues.windowTransform = { it.copy(queueRevision = it.queueRevision + 1L) }
            fixture.entry.retryFavoriteRead()
            fixture.await { it.favoriteIssue == PlaylistUiIssue.SOURCE_CHANGED }
            assertTrue(fixture.library.locateCalls.isEmpty())
            fixture.queues.windowTransform = { it.copy(totalCount = it.totalCount + 1L) }
            fixture.entry.retryFavoriteRead()
            fixture.await { it.favoriteIssue == PlaylistUiIssue.SOURCE_CHANGED }
            assertNull(fixture.entry.uiState.value.playingTrack)
            assertNull(fixture.entry.uiState.value.favoriteReference)
            assertTrue(fixture.library.locateCalls.isEmpty())
            fixture.queues.windowTransform = { it }
            fixture.entry.retryFavoriteRead()
            fixture.await { it.favoriteReference != null && it.favorite != null }
            assertEquals(fixture.library.track(), fixture.entry.uiState.value.playingTrack)
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun aLatePlayingLookupCannotPublishAfterTheQueueRevisionChangesWithTheSameCurrentInstance() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val original = fixture.queues.current(fixture.library.track(1L).trackKey)
            fixture.library.beforeLocate = { key ->
                if (key == fixture.library.track(1L).trackKey) withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                }
            }
            fixture.entry.setPlayingEntry("current")
            fixture.entry.onVisible(PlaylistEntrySurface.PLAYBACK)
            started.await()
            fixture.queues.windowTransform = { window -> window.copy(entries = window.entries.map {
                it.copy(trackKey = fixture.library.track(2L).trackKey)
            }) }
            fixture.queues.state.value = original.copy(queueRevision = original.queueRevision + 1L)
            fixture.await { it.queue?.revision == original.queueRevision + 1L }
            assertNull(fixture.entry.uiState.value.playingTrack)
            release.complete(Unit)
            val resolved = fixture.await { it.favoriteReference?.trackKey == fixture.library.track(2L).trackKey && it.favorite != null }
            assertEquals(fixture.library.track(2L), resolved.playingTrack)
            assertEquals(listOf(fixture.library.track(1L).trackKey, fixture.library.track(2L).trackKey), fixture.library.locateCalls)
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun reassociationNeedsASeparateConfirmationAndFailedCommitRetriesTheSameOperation() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            showRecovery(fixture)
            fixture.entry.previewReplacement(fixture.library.track())
            fixture.await { it.reassociation != null && !it.loading }
            fixture.entry.confirmReassociation()
            assertTrue(fixture.recovery.confirmations.isEmpty())
            fixture.entry.confirmReassociationChoice(true)
            val normalConfirm = fixture.recovery.confirm
            fixture.recovery.confirm = { _, _ -> throw PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE) }
            fixture.entry.confirmReassociation()
            fixture.await { it.issue == PlaylistUiIssue.STORAGE_UNAVAILABLE && !it.saving }
            fixture.recovery.confirm = normalConfirm
            fixture.entry.confirmReassociation()
            fixture.await { it.notice == PlaylistEntryNotice.REASSOCIATED }
            awaitEntryCondition { fixture.recovery.closed.isNotEmpty() }
            assertEquals(2, fixture.recovery.confirmations.size)
            assertEquals(fixture.recovery.confirmations[0], fixture.recovery.confirmations[1])
            assertEquals(1, fixture.recovery.previews.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun catalogOrRulesChangeClearsReassociationConfirmationAndPreventsTheOldCommit() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            showRecovery(fixture)
            fixture.entry.previewReplacement(fixture.library.track())
            fixture.await { it.reassociation != null && !it.loading }
            fixture.entry.confirmReassociationChoice(true)
            fixture.ui.ports.library.overviewState.value = fixture.ui.ports.library.overviewState.value.copy(rulesRevision = 4L)
            fixture.await { it.previewStale }
            assertFalse(fixture.entry.uiState.value.reassociationConfirmed)
            fixture.entry.confirmReassociation()
            assertTrue(fixture.recovery.confirmations.isEmpty())
            fixture.entry.closePicker()
            awaitEntryCondition { fixture.recovery.closed.isNotEmpty() }
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun aChangedReassociationCommitClearsConfirmationAndChoosingAgainRequiresAFreshPreview() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            showRecovery(fixture)
            fixture.entry.previewReplacement(fixture.library.track())
            fixture.await { it.reassociation != null && !it.loading }
            val original = checkNotNull(fixture.entry.uiState.value.reassociation)
            fixture.entry.confirmReassociationChoice(true)
            fixture.recovery.confirm = { _, _ -> throw PlaylistException(PlaylistIssue.SOURCE_CHANGED) }
            fixture.entry.confirmReassociation()
            fixture.await { it.previewStale && !it.saving }
            assertFalse(fixture.entry.uiState.value.reassociationConfirmed)
            fixture.entry.confirmReassociation()
            assertEquals(1, fixture.recovery.confirmations.size)
            fixture.entry.chooseAnotherReplacement()
            fixture.await { it.screen == PlaylistPickerScreen.REPLACEMENT && it.replacements != null && !it.loading }
            assertTrue(original.previewId in fixture.recovery.closed)
            fixture.entry.previewReplacement(fixture.library.track())
            fixture.await { it.reassociation != null && !it.loading }
            assertNotEquals(original.previewId, fixture.entry.uiState.value.reassociation?.previewId)
            assertFalse(fixture.entry.uiState.value.reassociationConfirmed)
            assertFalse(fixture.entry.uiState.value.previewStale)
        } finally { fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun previewCleanupFailureStillClosesTheChooserIndexAndRetainsThePreviewForExplicitRetry() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            showRecovery(fixture)
            fixture.entry.previewReplacement(fixture.library.track())
            fixture.await { it.reassociation != null && !it.loading }
            val preview = checkNotNull(fixture.entry.uiState.value.reassociation)
            val closedBefore = fixture.ui.ports.library.closed.size
            fixture.recovery.closeFailures = 1
            fixture.entry.closePicker()
            awaitEntryCondition { fixture.ui.ports.library.closed.size > closedBefore }
            assertTrue(fixture.recovery.closed.isEmpty())
            assertEquals(PlaylistUiIssue.STORAGE_UNAVAILABLE, fixture.entry.uiState.value.issue)
            fixture.entry.recover(preview.target)
            assertEquals(PlaylistUiIssue.BUSY, fixture.entry.uiState.value.issue)
            fixture.entry.retryCleanup()
            awaitEntryCondition { preview.previewId in fixture.recovery.closed && fixture.entry.uiState.value.issue == null }
            assertTrue(fixture.recovery.confirmations.isEmpty())
            assertTrue(fixture.ui.ports.playlists.requests.isEmpty())
        } finally { fixture.recovery.closeFailures = 0; fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun lateReassociationPreviewAfterBackIsClosedWithoutShowingItOrWriting() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            showRecovery(fixture)
            fixture.recovery.beforePreview = { withContext(NonCancellable) { started.complete(Unit); release.await() } }
            fixture.entry.previewReplacement(fixture.library.track())
            started.await()
            assertTrue(fixture.entry.back())
            release.complete(Unit)
            awaitEntryCondition { fixture.recovery.closed.isNotEmpty() }
            assertEquals(PlaylistPickerScreen.NONE, fixture.entry.uiState.value.screen)
            assertNull(fixture.entry.uiState.value.reassociation)
            assertTrue(fixture.recovery.confirmations.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test(timeout = 30_000L)
    fun unavailablePickerClearsOnlyItsRuntimeFlightAndPreservesTheImportDraft() = runBlocking {
        val fixture = PlaylistEntryTestFixture(this)
        try {
            fixture.showCatalog()
            val request = fixture.importRequest()
            val draft = fixture.ui.model.uiState.value.importDraft
            fixture.entry.takeDocumentRequest(request.requestId)
            fixture.entry.pickerUnavailable(request.requestId)
            assertEquals(PlaylistEntryNotice.NO_PICKER, fixture.entry.uiState.value.notice)
            assertFalse(fixture.entry.uiState.value.pickerPending)
            assertEquals(draft, fixture.ui.model.uiState.value.importDraft)
            fixture.entry.chooseImport(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8)
            assertNotNull(fixture.entry.uiState.value.documentRequest)
            assertTrue(fixture.documents.registered.isEmpty())
        } finally { fixture.close() }
    }

    private suspend fun showRecovery(fixture: PlaylistEntryTestFixture) {
        val normalMap = fixture.ui.ports.mapRows
        fixture.ui.ports.mapRows = { rows -> normalMap(rows).map { it.copy(availability = PlaylistQueueAvailability.MISSING) } }
        fixture.ui.showDetails()
        fixture.entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
        fixture.ui.model.selectMember(fixture.ui.model.uiState.value.rows.first())
        val target = checkNotNull(fixture.ui.model.recoveryTarget(PlaylistRecoveryAction.REASSOCIATE))
        fixture.entry.recover(target)
        fixture.await { it.replacements != null && !it.loading }
    }

    companion object {
        private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        private const val WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
