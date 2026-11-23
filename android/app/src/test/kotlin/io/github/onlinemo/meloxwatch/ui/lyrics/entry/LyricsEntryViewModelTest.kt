package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import android.content.Intent
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslationMatcher
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentResolutionException
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.PreparedLyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadFixture
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadResult
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadFailure
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadRequest
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadedSource
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadSelection
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSiblingStatus
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackCommitResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackReadResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPlayPreview
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有界假边界控制时序；不将这里的会话、权限或 pin 断言当成设备验收。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsEntryViewModelTest {
    @Test fun externalClosureWaitsForItsExactNonCooperativeLoadToActuallyExit(): Unit = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        fixture.beforeLoad = {
            entered.complete(Unit)
            withContext(NonCancellable) { cleanup.await() }
        }
        val external = fixture.vm.bindExternalReading()
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            external.close()
            val retired = backgroundScope.async { external.awaitClosed() }
            runCurrent()
            assertFalse(retired.isCompleted)
            assertNull(fixture.vm.readExternal())
            cleanup.complete(Unit)
            runCurrent()
            assertTrue(retired.isCompleted)
            assertEquals(1, fixture.loads)
        } finally { cleanup.complete(Unit); external.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun authenticatedExternalReadingSurvivesPageHideAndLeaveWithoutASampler(): Unit = runTest {
        val fixture = fixture()
        var external: LyricsEntryViewModel.ExternalReading? = null
        try {
            fixture.playback.playing = true
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            external = fixture.vm.bindExternalReading()
            val before = checkNotNull(fixture.vm.readExternal())
            fixture.vm.onHidden()
            fixture.vm.leave()
            runCurrent()
            val hiddenReads = fixture.playback.clockReads
            advanceTimeBy(5000L)
            runCurrent()
            assertEquals(hiddenReads, fixture.playback.clockReads)
            assertNull(fixture.vm.mediaClock.value)
            assertEquals(before.identity, fixture.vm.readExternal()?.identity)
            assertEquals(1, fixture.loads)
            fixture.queues.selectDuplicate()
            runCurrent()
            assertEquals("entry-duplicate", fixture.vm.readExternal()?.queueEntryId)
            assertEquals(2, fixture.loads)
            external.close()
            external = null
            fixture.queues.reviseWithoutSelecting()
            runCurrent()
            assertNull(fixture.vm.readExternal())
            assertEquals(2, fixture.loads)
        } finally { external?.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun externalReadingUsesSavedOffsetAndNeverAnUnconfirmedAssociation(): Unit = runTest {
        val fixture = fixture()
        var external: LyricsEntryViewModel.ExternalReading? = null
        try {
            fixture.tracks.preferences = fixture.tracks.preferences.copy(userDelayMs = 500)
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            external = fixture.vm.bindExternalReading()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(3)
            assertEquals(800, fixture.vm.uiState.value.offsetDraftMs)
            assertEquals(500, fixture.vm.readExternal()?.mapping?.userOffset?.delayMs)
            fixture.vm.cancelOffset()
            val request = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
            fixture.vm.pickerReturned(request, EntryDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCurrent()
            assertNotNull(fixture.vm.uiState.value.associationPreview)
            assertNull(fixture.vm.readExternal())
            fixture.vm.onHidden()
            runCurrent()
            assertEquals(500, fixture.vm.readExternal()?.mapping?.userOffset?.delayMs)
            assertEquals(0, fixture.tracks.commits)
        } finally { external?.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun hiddenPresentationCannotCommitOrChangeTheExternallyReadSavedSource(): Unit = runTest {
        val fixture = fixture()
        var external: LyricsEntryViewModel.ExternalReading? = null
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            external = fixture.vm.bindExternalReading()
            fixture.vm.onHidden()
            val before = checkNotNull(fixture.vm.readExternal())
            assertFalse(fixture.vm.beginOffset())
            assertNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
            assertFalse(fixture.vm.clearAssociation(LyricsDocumentPurpose.PRIMARY))
            fixture.vm.adjustOffset(1)
            fixture.vm.cancelOffset()
            fixture.vm.retry(sourceChanged = true)
            runCurrent()
            assertEquals(before.identity, fixture.vm.readExternal()?.identity)
            assertEquals(1, fixture.loads)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { external?.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun anExternalReaderAndVisiblePageShareOneDocumentAcquisition(): Unit = runTest {
        val fixture = fixture()
        var external: LyricsEntryViewModel.ExternalReading? = null
        try {
            external = fixture.vm.bindExternalReading()
            runCurrent()
            val externalDocument = checkNotNull(fixture.vm.readExternal()).document
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertSame(externalDocument, fixture.vm.readExternal()?.document)
            assertEquals(1, fixture.loads)
            external.close()
            external = null
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
            fixture.vm.onHidden()
            runCurrent()
            assertNull(fixture.vm.readExternal())
        } finally { external?.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun hiddenPageStopsSamplingAndSameQueueReloadsWithoutLosingItsDisplayIdentity(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.playback.playing = true
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertEquals(1, fixture.loads)
            val before = fixture.vm.uiState.value
            assertTrue(before.canChoose)
            assertNotNull(fixture.vm.mediaClock.value)
            fixture.vm.onHidden()
            runCurrent()
            val hiddenReads = fixture.playback.clockReads
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(hiddenReads, fixture.playback.clockReads)
            assertNull(fixture.vm.mediaClock.value)
            assertFalse(fixture.vm.uiState.value.canChoose)
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertEquals(2, fixture.loads)
            assertEquals(before.identity, fixture.vm.uiState.value.identity)
            assertEquals(before.documentId, fixture.vm.uiState.value.documentId)
            assertTrue(fixture.vm.uiState.value.canChoose)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun aLaunchedPickerMayReturnWhileStoppedAndIsConsumedOnlyForTheSameInstance(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            val requestId = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
            assertTrue(fixture.vm.pickerLaunched(requestId))
            fixture.vm.onHidden()
            fixture.vm.pickerReturned(requestId, EntryDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCurrent()
            assertEquals(0, fixture.documents.previews)
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertEquals(1, fixture.documents.previews)
            assertNotNull(fixture.vm.uiState.value.associationPreview)
            assertEquals(0, fixture.documents.prepared)
            assertNull(fixture.tracks.preferences.primary)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun aPickerFromThePreviousDuplicateQueueInstanceCannotAssociateToItsReplacement(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            val requestId = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
            assertTrue(fixture.vm.pickerLaunched(requestId))
            fixture.vm.onHidden()
            fixture.queues.selectDuplicate()
            fixture.vm.pickerReturned(requestId, EntryDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertEquals(0, fixture.documents.previews)
            assertNull(fixture.vm.uiState.value.associationPreview)
            assertEquals("entry-duplicate", fixture.vm.uiState.value.identity?.queueEntryId)
            assertEquals(0, fixture.tracks.commits)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun cancelledAcquisitionRetainsTheWorkerUntilItsNonCooperativeCleanupEnds(): Unit = runTest {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        fixture.beforeLoad = {
            started.complete(Unit)
            withContext(NonCancellable) { cleanup.await() }
            currentCoroutineContext().ensureActive()
        }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(started.isCompleted)
            fixture.queues.selectDuplicate()
            runCurrent()
            assertEquals(1, fixture.loads)
            assertFalse(fixture.vm.uiState.value.canChoose)
            fixture.beforeLoad = null
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(2, fixture.loads)
            assertEquals("entry-duplicate", fixture.vm.uiState.value.identity?.queueEntryId)
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
        } finally {
            cleanup.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun queueObserverFailureRetiresTheSuspendedLoadBeforeItCanReplaceTheTerminalError(): Unit = runTest {
        val fixture = fixture()
        val reading = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        fixture.beforeLoad = {
            reading.complete(Unit)
            withContext(NonCancellable) { finishRead.await() }
        }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(reading.isCompleted)
            val request = fixture.requests.single()
            assertTrue(fixture.authority.isCurrent(request))
            fixture.queues.failObservation()
            runCurrent()
            assertFalse(fixture.authority.isCurrent(request))
            assertEquals(LyricsLoadIssue.SOURCE_UNAVAILABLE, fixture.vm.uiState.value.failure?.issue)
            assertFalse(fixture.vm.uiState.value.loading)
            assertFalse(fixture.vm.uiState.value.canChoose)
            finishRead.complete(Unit)
            runCurrent()
            assertEquals(LyricsLoadIssue.SOURCE_UNAVAILABLE, fixture.vm.uiState.value.failure?.issue)
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Failed)
            assertEquals(1, fixture.loads)
            assertEquals(0, fixture.tracks.commits)
            assertNull(fixture.vm.mediaClock.value)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            finishRead.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun playbackObserverFailureInvalidatesTheTicketInsideAnAlreadyEnteredAssociationCommit(): Unit = runTest {
        val fixture = fixture()
        val enteredCommit = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        fixture.tracks.beforeCommit = {
            enteredCommit.complete(Unit)
            withContext(NonCancellable) { finishCommit.await() }
        }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            val requestId = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
            assertTrue(fixture.vm.pickerLaunched(requestId))
            fixture.vm.pickerReturned(requestId, EntryDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCurrent()
            val preview = checkNotNull(fixture.vm.uiState.value.associationPreview)
            assertTrue(fixture.vm.confirmAssociation(preview.previewId))
            runCurrent()
            assertTrue(enteredCommit.isCompleted)
            assertEquals(1, fixture.documents.pins)
            fixture.playback.failObservation()
            runCurrent()
            assertEquals(LyricsLoadIssue.SOURCE_UNAVAILABLE, fixture.vm.uiState.value.failure?.issue)
            assertEquals(0, fixture.tracks.commits)
            assertEquals(1, fixture.documents.pins)
            finishCommit.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.tracks.rejectedAuthorities)
            assertEquals(0, fixture.tracks.commits)
            assertNull(fixture.tracks.preferences.primary)
            assertEquals(0, fixture.documents.pins)
            assertEquals(1, fixture.documents.releases)
            assertEquals(LyricsLoadIssue.SOURCE_UNAVAILABLE, fixture.vm.uiState.value.failure?.issue)
            assertNull(fixture.vm.uiState.value.associationPreview)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            finishCommit.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun libraryObserverFailureRetiresTheSuspendedLoadAndKeepsTheFailureAfterCleanup(): Unit = runTest {
        val fixture = fixture()
        val reading = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        fixture.beforeLoad = {
            reading.complete(Unit)
            withContext(NonCancellable) { finishRead.await() }
        }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(reading.isCompleted)
            val request = fixture.requests.single()
            fixture.failLibraryObservation()
            runCurrent()
            assertFalse(fixture.authority.isCurrent(request))
            assertEquals(LyricsLoadIssue.SOURCE_UNAVAILABLE, fixture.vm.uiState.value.failure?.issue)
            finishRead.complete(Unit)
            runCurrent()
            assertEquals(LyricsLoadIssue.SOURCE_UNAVAILABLE, fixture.vm.uiState.value.failure?.issue)
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Failed)
            assertFalse(fixture.vm.uiState.value.canChoose)
            assertEquals(0, fixture.tracks.commits)
        } finally {
            finishRead.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun unlinkingARevokedPrimarySucceedsWhenAutomaticLyricsAreAbsent(): Unit = runTest {
        val fixture = fixture()
        fixture.automaticText = null
        fixture.tracks.preferences = fixture.tracks.preferences.copy(primary = fixture.documents.badAssociation())
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertEquals(LyricsLoadIssue.PERMISSION_REVOKED, fixture.vm.uiState.value.failure?.issue)
            assertTrue(fixture.vm.uiState.value.canChoose)
            assertTrue(fixture.vm.clearAssociation(LyricsDocumentPurpose.PRIMARY))
            runCurrent()
            assertNull(fixture.tracks.preferences.primary)
            assertEquals(1, fixture.tracks.commits)
            assertEquals(1, fixture.contextValidations)
            assertSame(LyricsPageContent.Missing, fixture.vm.uiState.value.content)
            assertEquals(LyricsEntryNotice.SAVED, fixture.vm.uiState.value.notice)
            assertTrue(fixture.vm.uiState.value.canChoose)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun unlinkDoesNotBypassAudioValidationOrModifyTheRecordWhenItFails(): Unit = runTest {
        val fixture = fixture()
        val original = fixture.documents.badAssociation()
        fixture.tracks.preferences = fixture.tracks.preferences.copy(primary = original)
        fixture.contextValid = false
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertTrue(fixture.vm.clearAssociation(LyricsDocumentPurpose.PRIMARY))
            runCurrent()
            assertSame(original, fixture.tracks.preferences.primary)
            assertEquals(0, fixture.tracks.commits)
            assertEquals(LyricsLoadIssue.SOURCE_CHANGED, fixture.vm.uiState.value.failure?.issue)
            assertFalse(fixture.vm.uiState.value.canChoose)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun offsetCommitValidatesTheShownDocumentAndPreservesItsIdentityAndTimeline(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            val before = fixture.vm.uiState.value
            val document = (before.content as LyricsPageContent.Ready).document
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(3)
            assertTrue(fixture.vm.saveOffset())
            runCurrent()
            val after = fixture.vm.uiState.value
            assertEquals(300, fixture.tracks.preferences.userDelayMs)
            assertEquals(before.identity, after.identity)
            assertEquals(before.documentId, after.documentId)
            assertSame(document, (after.content as LyricsPageContent.Ready).document)
            assertEquals(1, fixture.loads)
            assertEquals(1, fixture.sourceValidations)
            assertEquals(1, fixture.contextValidations)
            assertEquals(LyricsClockMapping(userOffset = LyricsUserOffset(300)), after.mapping)
            assertEquals(LyricsEntryNotice.SAVED, after.notice)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun cancellingAfterADurableAssociationCommitKeepsTheCopyPinnedUntilTheCallEnds(): Unit = runTest {
        val fixture = fixture()
        val commitStarted = CompletableDeferred<Unit>()
        val commitEnded = CompletableDeferred<Unit>()
        fixture.tracks.afterDurableCommit = {
            commitStarted.complete(Unit)
            withContext(NonCancellable) { commitEnded.await() }
        }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            val requestId = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
            assertTrue(fixture.vm.pickerLaunched(requestId))
            fixture.vm.pickerReturned(requestId, EntryDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCurrent()
            val preview = checkNotNull(fixture.vm.uiState.value.associationPreview)
            assertTrue(fixture.vm.confirmAssociation(preview.previewId))
            runCurrent()
            assertTrue(commitStarted.isCompleted)
            assertNotNull(fixture.tracks.preferences.primary)
            assertEquals(1, fixture.documents.pins)
            fixture.vm.onHidden()
            runCurrent()
            assertEquals(1, fixture.documents.pins)
            commitEnded.complete(Unit)
            runCurrent()
            assertEquals(0, fixture.documents.pins)
            assertEquals(1, fixture.documents.releases)
            assertNotNull(fixture.tracks.preferences.primary)
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertEquals(fixture.tracks.preferences.primary, fixture.vm.uiState.value.preferences?.primary)
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
        } finally {
            commitEnded.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun startingANewReadImmediatelyRetiresAnExistingSoundConfirmation(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            val state = fixture.vm.uiState.value
            val document = (state.content as LyricsPageContent.Ready).document
            val target = checkNotNull(document.timeline.seekTarget(0, EntryPlayback.DURATION_MS, state.mapping))
            val confirmation = checkNotNull(fixture.vm.previewPlay(LyricsPlayPreview(checkNotNull(state.identity), target,
                state.mapping, EntryPlayback.DURATION_MS)))
            assertEquals(LyricsPlayPhase.CONFIRMATION, fixture.vm.playConfirmation.state.value.phase)
            fixture.vm.retry()
            assertFalse(fixture.vm.playConfirmation.confirm(confirmation))
            runCurrent()
            assertTrue(fixture.playback.dispatched.isEmpty())
            assertNull(fixture.vm.playConfirmation.state.value.confirmationId)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun aQueueRevisionWithoutATrackChangeKeepsTheLyricsDisplayIdentityAndStopsOldSamplingImmediately(): Unit = runTest {
        val fixture = fixture()
        val releaseLoad = CompletableDeferred<Unit>()
        try {
            fixture.playback.playing = true
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            val before = fixture.vm.uiState.value
            fixture.beforeLoad = { releaseLoad.await() }
            fixture.queues.reviseWithoutSelecting()
            runCurrent()
            assertTrue(fixture.vm.uiState.value.loading)
            assertNull(fixture.vm.mediaClock.value)
            val reads = fixture.playback.clockReads
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(reads, fixture.playback.clockReads)
            fixture.beforeLoad = null
            releaseLoad.complete(Unit)
            runCurrent()
            assertEquals(before.identity, fixture.vm.uiState.value.identity)
            assertEquals(before.documentId, fixture.vm.uiState.value.documentId)
            assertEquals(2, fixture.loads)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            releaseLoad.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun translationUsesTheBudgetLeftByTheRetainedPrimaryAndReportsPressureWithoutHidingTheOriginal(): Unit = runTest {
        val fixture = fixture()
        fixture.primaryAccountedBytes = LyricsLimits.ACCOUNTED_BYTES - 400_000L
        fixture.tracks.preferences = fixture.tracks.preferences.copy(translation = fixture.documents.goodAssociation())
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertEquals(2, fixture.requests.size)
            val primaryRequest = fixture.requests[0]
            val translationRequest = fixture.requests[1]
            assertTrue(primaryRequest.maxAccountedBytes < LyricsLimits.ACCOUNTED_BYTES)
            assertTrue(translationRequest.maxAccountedBytes in 1L..400_000L)
            fixture.translationExceedsBudget = true
            fixture.vm.retry()
            runCurrent()
            val state = fixture.vm.uiState.value
            assertEquals("automatic", (state.content as LyricsPageContent.Ready).document.timeline.line(0).text)
            assertEquals(LyricsLoadIssue.MEMORY_LIMIT, state.translationFailure?.issue)
            assertNull((state.content as LyricsPageContent.Ready).translation)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun actualVisibleWordBoundariesRespectOffsetAndPlaybackSpeedWithoutInventedTiming(): Unit = runTest {
        val parser = LocalLyricsParser(LyricsLimits(), StandardTestDispatcher(testScheduler))
        val document = parser.parse("[00:01]<00:01>one<00:01.100>two<00:02>\n[00:02]ordinary".toByteArray())
        val clock = LyricsMediaClock("entry-one", 1_280L, 10_000L, true, 2f, true)
        val mapping = LyricsClockMapping(userOffset = LyricsUserOffset(200))
        assertEquals(10L, lyricsNextSampleDelay(document, mapping, clock, true, listOf(0)))
        assertEquals(200L, lyricsNextSampleDelay(document, mapping, clock, true, listOf(1)))
        assertEquals(200L, lyricsNextSampleDelay(document, mapping, clock, false, listOf(0)))
        assertEquals(200L, lyricsNextSampleDelay(document, mapping, clock, true, emptyList()))
    }

    private fun TestScope.fixture() = EntryFixture(this, StandardTestDispatcher(testScheduler))
}

private class EntryFixture(scope: CoroutineScope, private val dispatcher: CoroutineDispatcher) {
    val audio = LocalLyricsLoadFixture().audio
    val queues = EntryQueues(QueueEntry("entry-one", audio.trackKey, 0L, 0L, location = audio.location))
    val tracks = EntryTracks(audio.trackKey, queues)
    val documents = EntryDocuments()
    val playback = EntryPlayback(queues)
    val authority = LyricsLoadAuthority()
    var loads = 0
    var contextValidations = 0
    var sourceValidations = 0
    val requests = ArrayList<LyricsLoadRequest>()
    var primaryAccountedBytes: Long? = null
    var translationExceedsBudget = false
    var contextValid = true
    var automaticText: String? = "[00:01]automatic\n[00:02]second"
    var beforeLoad: (suspend () -> Unit)? = null
    private val libraryFailures = MutableStateFlow<Exception?>(null)
    fun failLibraryObservation() { libraryFailures.value = IOException("Controlled library observer failure") }
    val vm = LyricsEntryViewModel(
        queues = queues,
        resolveContext = { entry ->
            check(entry.trackKey == audio.trackKey)
            LocalLyricsContext(audio, null)
        },
        validateContext = { context ->
            contextValidations++
            context.audio == audio && contextValid
        },
        tracks = tracks,
        documents = documents,
        authority = authority,
        load = { request ->
            loads++
            requests += request
            beforeLoad?.invoke()
            val input = (request.selection as? LyricsLoadSelection.TextDocument)?.input
            val text = if (input == null) automaticText else documents.text
            if (text == null) LocalLyricsLoadResult.Unavailable(request.binding, LyricsLoadFailure(LyricsLoadIssue.NO_LYRICS),
                LyricsSiblingStatus.NOT_REQUESTED, emptyList())
            else {
                val bytes = text.toByteArray()
                val document = LocalLyricsParser(LyricsLimits(), dispatcher).parse(bytes)
                val source = LyricsLoadedSource(
                    if (input == null) LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED else LyricsSourceOrigin.EXPLICIT_DOCUMENT,
                    input?.source?.sourceId ?: audio.source.spec.sourceId,
                    input?.candidate?.objectId ?: audio.objectId,
                    input?.documentId ?: "embedded-document", request.binding.sourceGeneration, entrySha256(bytes),
                    if (input == null) "embedded-document" else null,
                )
                LocalLyricsLoadResult.Loaded(request.binding, audio, source, document, emptyList(), emptyList(),
                    LyricsSiblingStatus.NOT_REQUESTED, if (input == null) primaryAccountedBytes ?: document.accountedResidentBytes
                    else if (translationExceedsBudget) request.maxAccountedBytes + 1L else document.accountedResidentBytes)
            }
        },
        validate = { request, loaded ->
            sourceValidations++
            authority.isCurrent(request) && loaded.binding == request.binding
        },
        playback = playback,
        libraryChanges = libraryFailures.transform<Exception?, Unit> { error -> if (error != null) throw error },
        translationMatcher = LyricsTranslationMatcher(LyricsLimits(), dispatcher),
        scopeOverride = scope,
        awaitDisplayFrame = {},
    )
}

private class EntryDocuments : LyricsDocuments {
    var previews = 0
    var prepared = 0
    var pins = 0
    var releases = 0
    val text = "[00:01]chosen\n[00:02]chosen second"
    private fun input(generation: Long): LyricsDocumentInput {
        val source = LocalSourceSpec("manual-source", LocalSourceKind.DOCUMENT, URI)
        val candidate = ObservedCandidate(source.sourceId, "document-one", URI, null, "chosen.lrc", "text/plain",
            LocalNodeKind.OTHER, text.toByteArray().size.toLong())
        return LyricsDocumentInput(candidate.objectId, generation, source, candidate)
    }

    override suspend fun preview(uri: String, returnedFlags: Int, generation: Long,
        encoding: LyricsEncodingChoice): LyricsDocumentInput {
        check(uri == URI && returnedFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
        previews++
        return input(generation).copy(encoding = encoding)
    }

    override suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput {
        if (association.documentId == "bad") throw LyricsDocumentResolutionException(LyricsLoadIssue.PERMISSION_REVOKED)
        return input(generation).copy(expectedContentSha256 = association.confirmedContentSha256)
    }

    override suspend fun prepareAssociation(input: LyricsDocumentInput, confirmedSha256: String): PreparedLyricsAssociation {
        check(confirmedSha256 == entrySha256(text.toByteArray()))
        prepared++
        pins++
        return PreparedLyricsAssociation(LyricsAssociation(input.documentId, input.source.sourceId, input.candidate.uri,
            1, input.encoding, confirmedSha256), input) {
            pins--
            releases++
        }
    }

    fun badAssociation() = LyricsAssociation("bad", "manual-source", URI, 1, LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
        "a".repeat(64))

    fun goodAssociation() = LyricsAssociation("document-one", "manual-source", URI, 1, LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
        entrySha256(text.toByteArray()))

    companion object { const val URI = "content://lyrics.entry.fixture/document/one" }
}

private class EntryTracks(key: TrackKey, private val queues: EntryQueues) : LyricsTrackStore {
    var preferences = LyricsTrackPreferences(key)
    var commits = 0
    var rejectedAuthorities = 0
    var beforeCommit: (suspend () -> Unit)? = null
    var afterDurableCommit: (suspend () -> Unit)? = null
    override suspend fun read(trackKey: TrackKey): LyricsTrackReadResult {
        check(trackKey == preferences.trackKey)
        return LyricsTrackReadResult.Ready(preferences)
    }

    override suspend fun commit(expectedRevision: Long, next: LyricsTrackPreferences, fence: LyricsTrackWriteFence,
        stillCurrent: suspend () -> Boolean): LyricsTrackCommitResult {
        beforeCommit?.invoke()
        if (!stillCurrent()) {
            rejectedAuthorities++
            return LyricsTrackCommitResult.Stale(preferences)
        }
        check(queues.current.currentEntryId == fence.queueEntryId && queues.current.queueRevision == fence.queueRevision)
        check(expectedRevision == preferences.revision && next.revision == expectedRevision)
        commits++
        preferences = next.copy(revision = expectedRevision + 1L)
        afterDurableCommit?.invoke()
        return LyricsTrackCommitResult.Saved(preferences)
    }
}

private class EntryQueues(val first: QueueEntry) : QueueStore {
    private val duplicate = first.copy(queueEntryId = "entry-duplicate", originalOrder = 1L, playbackOrder = 1L)
    private val state = MutableStateFlow(QueueSnapshot("queue-one", 3L, 0L, FrozenQueueView("view-one", 1L, 2L),
        QueueMode(), QueueCheckpoint(first.queueEntryId, 0L), 0L, 0L))
    private val observationFailure = MutableStateFlow<Exception?>(null)
    val current: QueueSnapshot get() = state.value
    fun failObservation() { observationFailure.value = IOException("Controlled queue observer failure") }
    fun reviseWithoutSelecting() { state.value = current.copy(queueRevision = current.queueRevision + 1L) }
    fun selectDuplicate() {
        state.value = current.copy(checkpointRevision = current.checkpointRevision + 1L,
            checkpoint = QueueCheckpoint(duplicate.queueEntryId, 0L), currentOriginalOrder = 1L, currentPlaybackOrder = 1L)
    }
    override suspend fun initialize(): QueueSnapshot = current
    override suspend fun snapshot(): QueueSnapshot = current
    override fun snapshots(): Flow<QueueSnapshot?> = combine(state, observationFailure) { queue, error ->
        if (error != null) throw error
        queue
    }
    override suspend fun window(generationId: String, currentEntryId: String) = QueueWindow(generationId,
        current.queueRevision, 2L, listOf(first, duplicate), if (currentEntryId == first.queueEntryId) 0 else 1)
    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> =
        error("Lyrics entry must not load a full queue page")
    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = error("No queue replacement")
    override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = error("No queue mutation")
    override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint): QueueSnapshot = error("No second checkpoint owner")
}

private class EntryPlayback(private val queues: EntryQueues) : LyricsPlaybackPort {
    private val observationFailure = MutableStateFlow<Exception?>(null)
    override val changes: Flow<Unit> = observationFailure.map { error ->
        if (error != null) throw error
        Unit
    }
    fun failObservation() { observationFailure.value = IOException("Controlled playback observer failure") }
    override val busy = false
    var playing = false
    var clockReads = 0
    val dispatched = mutableListOf<QueueSeekRequest>()
    override fun clock(): LyricsMediaClock {
        clockReads++
        return LyricsMediaClock(checkNotNull(queues.current.currentEntryId), queues.current.positionMs,
            DURATION_MS, playing, 1f, true)
    }
    override suspend fun acquireSeekBinding() = QueueSeekBinding("preview-one", "session-one", 0L,
        queues.current.generationId, queues.current.queueRevision, checkNotNull(queues.current.currentEntryId),
        queues.first.location?.locationId, checkNotNull(queues.first.location).contentRevision, DURATION_MS)
    override fun isSeekBindingCurrent(binding: QueueSeekBinding): Boolean =
        binding.queueEntryId == queues.current.currentEntryId && binding.queueRevision == queues.current.queueRevision
    override suspend fun dispatchSeek(request: QueueSeekRequest): Boolean {
        dispatched += request
        return true
    }
    companion object { const val DURATION_MS = 180_000L }
}

private fun entrySha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
