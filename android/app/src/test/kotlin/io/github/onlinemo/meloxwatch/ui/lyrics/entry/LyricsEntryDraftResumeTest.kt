package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslationMatcher
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.PreparedLyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadFixture
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadResult
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadFailure
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadRequest
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadSelection
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadedSource
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSiblingStatus
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackCommitResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackReadResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
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
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runtime lifetime/byte/CAS risk definitions. Provider, Activity and device acceptance remain separate. */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsEntryDraftResumeTest {
    @Test fun presentationCloseReleasesParsedContentAndStopsHiddenSampling(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.playback.playing = true
            val presentation = fixture.show(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(3)
            val originalRequest = fixture.requests.single()
            assertNotNull(fixture.vm.mediaClock.value)
            presentation.close()
            runCurrent()
            val hidden = fixture.vm.uiState.value
            assertEquals(LyricsPageContent.Missing, hidden.content)
            assertNull(hidden.identity)
            assertNull(hidden.documentId)
            assertNull(hidden.preferences)
            assertNull(hidden.offsetDraftMs)
            assertNull(fixture.vm.mediaClock.value)
            assertFalse(fixture.authority.isCurrent(originalRequest))
            val clockReads = fixture.playback.clockReads
            val loads = fixture.loads
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(clockReads, fixture.playback.clockReads)
            assertEquals(loads, fixture.loads)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun associationResumeReobservesBytesAndRequiresTheNewPreviewForExplicitConfirmation(): Unit = runTest {
        val fixture = fixture()
        try {
            val first = fixture.show()
            runCurrent()
            val originalPreview = preview(fixture)
            val originalRequest = fixture.requests.last()
            val originalInput = (originalRequest.selection as LyricsLoadSelection.TextDocument).input
            first.close()
            fixture.show()
            runCurrent()
            val resumedPreview = checkNotNull(fixture.vm.uiState.value.associationPreview)
            val resumedRequest = fixture.requests.last()
            val resumedInput = (resumedRequest.selection as LyricsLoadSelection.TextDocument).input
            assertEquals(LyricsDocumentPurpose.PRIMARY, resumedPreview.purpose)
            assertFalse(originalPreview == resumedPreview.previewId)
            assertNotSame(originalRequest, resumedRequest)
            assertTrue(resumedRequest.binding.requestGeneration > originalRequest.binding.requestGeneration)
            assertFalse(originalInput.source.sourceId == resumedInput.source.sourceId)
            assertEquals(draftResumeSha256(fixture.documents.text.toByteArray()), resumedInput.expectedContentSha256)
            assertEquals(2, fixture.documents.previews)
            assertFalse(fixture.authority.isCurrent(originalRequest))
            assertTrue(fixture.authority.isCurrent(resumedRequest))
            assertEquals(0, fixture.documents.prepared)
            assertEquals(0, fixture.tracks.commits)
            assertNull(fixture.tracks.preferences.primary)
            assertFalse(fixture.vm.confirmAssociation(originalPreview))
            assertTrue(fixture.vm.confirmAssociation(resumedPreview.previewId))
            runCurrent()
            assertEquals(1, fixture.documents.prepared)
            assertEquals(1, fixture.tracks.commits)
            assertNotNull(fixture.tracks.preferences.primary)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun translationResumeReacquiresBothDocumentsWithoutSavingTheProposal(): Unit = runTest {
        val fixture = fixture()
        try {
            val first = fixture.show()
            runCurrent()
            val originalPreview = preview(fixture, LyricsDocumentPurpose.TRANSLATION)
            val oldRequests = fixture.requests.takeLast(2)
            first.close()
            fixture.show()
            runCurrent()
            val state = fixture.vm.uiState.value
            val resumed = checkNotNull(state.associationPreview)
            assertEquals(LyricsDocumentPurpose.TRANSLATION, resumed.purpose)
            assertFalse(resumed.previewId == originalPreview)
            assertEquals("automatic", (state.content as LyricsPageContent.Ready).document.timeline.line(0).text)
            assertNotNull((state.content as LyricsPageContent.Ready).translation)
            assertEquals(2, fixture.documents.previews)
            oldRequests.forEach { assertFalse(fixture.authority.isCurrent(it)) }
            fixture.requests.takeLast(2).forEach { assertTrue(fixture.authority.isCurrent(it)) }
            assertEquals(0, fixture.documents.prepared)
            assertEquals(0, fixture.tracks.commits)
            assertNull(fixture.tracks.preferences.primary)
            assertNull(fixture.tracks.preferences.translation)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun offsetResumeReloadsTheOriginalDocumentAndKeepsTheValueUnsaved(): Unit = runTest {
        val fixture = fixture()
        try {
            val first = fixture.show(LyricsEntrySurface.PAGE)
            runCurrent()
            val original = fixture.vm.uiState.value
            val originalDocument = (original.content as LyricsPageContent.Ready).document
            val originalRequest = fixture.requests.single()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(3)
            first.close()
            fixture.show(LyricsEntrySurface.PAGE)
            runCurrent()
            val resumed = fixture.vm.uiState.value
            val resumedRequest = fixture.requests.last()
            assertEquals(2, fixture.loads)
            assertEquals(original.identity, resumed.identity)
            assertEquals(original.documentId, resumed.documentId)
            assertNotSame(originalDocument, (resumed.content as LyricsPageContent.Ready).document)
            assertEquals(300, resumed.offsetDraftMs)
            assertEquals(0, fixture.tracks.preferences.userDelayMs)
            assertEquals(0, fixture.tracks.commits)
            assertNotSame(originalRequest, resumedRequest)
            assertTrue(resumedRequest.binding.requestGeneration > originalRequest.binding.requestGeneration)
            assertFalse(fixture.authority.isCurrent(originalRequest))
            assertTrue(fixture.authority.isCurrent(resumedRequest))
            assertTrue(fixture.vm.saveOffset())
            runCurrent()
            assertEquals(1, fixture.tracks.commits)
            assertEquals(300, fixture.tracks.preferences.userDelayMs)
            assertEquals(resumedRequest.binding.requestGeneration, fixture.tracks.fences.single().audio.requestGeneration)
            assertEquals(1, fixture.sourceValidations)
            assertEquals(0, fixture.documents.prepared)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun changedBytesDiscardBothDraftKindsAndFailedCloseDoesNotRecaptureThem(): Unit = runTest {
        for (association in listOf(false, true)) {
            val fixture = fixture()
            try {
                val first = fixture.show()
                runCurrent()
                if (association) preview(fixture) else {
                    assertTrue(fixture.vm.beginOffset())
                    fixture.vm.adjustOffset(2)
                }
                first.close()
                if (association) fixture.documents.text = fixture.documents.text.replace("chosen", "edited")
                else fixture.automaticText = fixture.automaticText.replace("automatic", "automated")
                val second = fixture.show()
                runCurrent()
                assertEquals(LyricsLoadIssue.SOURCE_CHANGED, fixture.vm.uiState.value.failure?.issue)
                assertNull(fixture.vm.uiState.value.associationPreview)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertEquals(0, fixture.tracks.commits)
                assertEquals(0, fixture.documents.prepared)
                second.close()
                fixture.documents.text = DraftResumeDocuments.TEXT
                fixture.automaticText = DraftResumeFixture.AUTOMATIC_TEXT
                val reobservations = fixture.documents.previews
                fixture.show()
                runCurrent()
                assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
                assertNull(fixture.vm.uiState.value.associationPreview)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertEquals(reobservations, fixture.documents.previews)
            } finally {
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test fun sameUriAndBytesWithAReplacementObjectCannotRestoreAnAssociation(): Unit = runTest {
        val fixture = fixture()
        try {
            val first = fixture.show()
            runCurrent()
            val original = preview(fixture)
            val loads = fixture.loads
            first.close()
            fixture.documents.objectId = "replacement-object"
            fixture.show()
            runCurrent()
            assertEquals(2, fixture.documents.previews)
            assertEquals(loads, fixture.loads)
            assertEquals(LyricsLoadIssue.SOURCE_CHANGED, fixture.vm.uiState.value.failure?.issue)
            assertNull(fixture.vm.uiState.value.associationPreview)
            assertFalse(fixture.vm.confirmAssociation(original))
            assertEquals(0, fixture.documents.prepared)
            assertEquals(0, fixture.tracks.commits)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun aSavedDocumentMetadataRevisionChangeDiscardsItsOffsetEvenWhenBytesMatch(): Unit = runTest {
        val fixture = fixture()
        fixture.tracks.preferences = fixture.tracks.preferences.copy(primary = fixture.documents.association())
        try {
            val first = fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(2)
            first.close()
            fixture.documents.providerContentToken = "revision-two"
            fixture.show()
            runCurrent()
            assertEquals(2, fixture.loads)
            assertEquals(LyricsLoadIssue.SOURCE_CHANGED, fixture.vm.uiState.value.failure?.issue)
            assertNull(fixture.vm.uiState.value.offsetDraftMs)
            assertEquals(0, fixture.tracks.preferences.userDelayMs)
            assertEquals(0, fixture.tracks.commits)
            assertEquals(0, fixture.documents.prepared)
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun aPreferencesRevisionChangeRejectsBothDraftKindsBeforeReacquisition(): Unit = runTest {
        for (association in listOf(false, true)) {
            val fixture = fixture()
            try {
                val first = fixture.show()
                runCurrent()
                if (association) preview(fixture) else assertTrue(fixture.vm.beginOffset())
                val loads = fixture.loads
                val previews = fixture.documents.previews
                first.close()
                fixture.tracks.preferences = fixture.tracks.preferences.copy(revision = fixture.tracks.preferences.revision + 1L)
                fixture.show()
                runCurrent()
                assertEquals(LyricsEntryNotice.CONFLICT, fixture.vm.uiState.value.notice)
                assertNull(fixture.vm.uiState.value.associationPreview)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertEquals(loads, fixture.loads)
                assertEquals(previews, fixture.documents.previews)
                assertEquals(0, fixture.tracks.commits)
            } finally {
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test fun duplicateSelectionAndQueueRevisionEachDiscardAnOffsetFromTheOldQueueInstance(): Unit = runTest {
        for (duplicate in listOf(false, true)) {
            val fixture = fixture()
            try {
                val first = fixture.show()
                runCurrent()
                val originalIdentity = fixture.vm.uiState.value.identity
                assertTrue(fixture.vm.beginOffset())
                fixture.vm.adjustOffset(4)
                first.close()
                if (duplicate) fixture.queues.selectDuplicate() else fixture.queues.reviseWithoutSelecting()
                fixture.show()
                runCurrent()
                assertEquals(2, fixture.loads)
                assertEquals(!duplicate, fixture.vm.uiState.value.identity == originalIdentity)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertNull(fixture.vm.uiState.value.associationPreview)
                assertEquals(fixture.tracks.preferences.trackKey, fixture.requests.last().binding.trackKey)
                assertEquals(0, fixture.tracks.preferences.userDelayMs)
                assertEquals(0, fixture.tracks.commits)
            } finally {
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test fun leavingTheActualEntryRoutesDropsBothDraftKindsAndTheGateStartsNoWork(): Unit = runTest {
        for (association in listOf(false, true)) {
            val fixture = fixture()
            try {
                val first = fixture.show()
                runCurrent()
                if (association) preview(fixture) else assertTrue(fixture.vm.beginOffset())
                fixture.vm.setActualEntryRoute(false)
                first.close()
                runCurrent()
                val loads = fixture.loads
                val clockReads = fixture.playback.clockReads
                val previews = fixture.documents.previews
                fixture.vm.setActualEntryRoute(true)
                runCurrent()
                assertEquals(loads, fixture.loads)
                assertEquals(clockReads, fixture.playback.clockReads)
                fixture.show()
                runCurrent()
                assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
                assertNull(fixture.vm.uiState.value.associationPreview)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertEquals(previews, fixture.documents.previews)
                assertEquals(0, fixture.tracks.commits)
            } finally {
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test fun anExplicitSourceChangeAndObservedAudioRevisionEachInvalidateAnOffset(): Unit = runTest {
        for (explicitSignal in listOf(false, true)) {
            val fixture = fixture()
            try {
                val first = fixture.show()
                runCurrent()
                val original = fixture.vm.uiState.value
                val originalRequest = fixture.requests.single()
                assertTrue(fixture.vm.beginOffset())
                fixture.vm.adjustOffset(1)
                first.close()
                if (!explicitSignal) fixture.audio = fixture.audio.copy(
                    location = fixture.audio.location.copy(contentRevision = fixture.audio.location.contentRevision + 1L),
                )
                fixture.show()
                if (explicitSignal) fixture.vm.retry(sourceChanged = true)
                runCurrent()
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertFalse(fixture.authority.isCurrent(originalRequest))
                if (explicitSignal) {
                    assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
                    assertTrue(checkNotNull(fixture.vm.uiState.value.identity).sourceGeneration > checkNotNull(original.identity).sourceGeneration)
                } else {
                    assertEquals(LyricsLoadIssue.SOURCE_CHANGED, fixture.vm.uiState.value.failure?.issue)
                    assertEquals(1, fixture.loads)
                }
                assertEquals(0, fixture.tracks.commits)
            } finally {
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test fun aLaunchedPickerSurvivesPresentationStopButCannotReturnAfterActualRouteExit(): Unit = runTest {
        for (actualExit in listOf(false, true)) {
            val fixture = fixture()
            try {
                val first = fixture.show()
                runCurrent()
                val request = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
                assertTrue(fixture.vm.pickerLaunched(request))
                if (actualExit) fixture.vm.setActualEntryRoute(false)
                first.close()
                assertEquals(!actualExit, fixture.vm.uiState.value.documentRequest != null)
                fixture.vm.pickerReturned(request, DraftResumeDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                fixture.show()
                runCurrent()
                assertEquals(if (actualExit) 0 else 1, fixture.documents.previews)
                assertEquals(!actualExit, fixture.vm.uiState.value.associationPreview != null)
                assertEquals(0, fixture.documents.prepared)
                assertEquals(0, fixture.tracks.commits)
                assertTrue(fixture.playback.dispatched.isEmpty())
            } finally {
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test fun oldPresentationCloseCannotHideASuccessorOrReplaceItsLatestOffsetDraft(): Unit = runTest {
        val fixture = fixture()
        try {
            val old = fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(1)
            old.close()
            val successor = fixture.show()
            runCurrent()
            assertEquals(100, fixture.vm.uiState.value.offsetDraftMs)
            fixture.vm.adjustOffset(2)
            val request = fixture.requests.last()
            old.close()
            fixture.vm.setActualEntryRoute(true)
            runCurrent()
            assertTrue(successor.isCurrent)
            assertTrue(fixture.authority.isCurrent(request))
            assertEquals(300, fixture.vm.uiState.value.offsetDraftMs)
            assertEquals(2, fixture.loads)
            successor.close()
            fixture.show()
            runCurrent()
            assertEquals(300, fixture.vm.uiState.value.offsetDraftMs)
            assertEquals(3, fixture.loads)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test fun lateNonCooperativeResumeCannotPublishAndItsSuccessorWaitsForPhysicalCleanup(): Unit = runTest {
        val fixture = fixture()
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        try {
            val first = fixture.show()
            runCurrent()
            val originalPreview = preview(fixture)
            first.close()
            fixture.nonCooperativeLoad = true
            fixture.beforeLoad = {
                loadStarted.complete(Unit)
                releaseLoad.await()
            }
            val interrupted = fixture.show()
            runCurrent()
            assertTrue(loadStarted.isCompleted)
            assertEquals(1, fixture.activeLoads)
            val retired = fixture.requests.last()
            fixture.nonCooperativeLoad = false
            fixture.beforeLoad = null
            interrupted.close()
            assertFalse(fixture.authority.isCurrent(retired))
            val successor = fixture.show()
            runCurrent()
            assertTrue(successor.isCurrent)
            assertFalse(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
            assertNull(fixture.vm.uiState.value.associationPreview)
            assertEquals(3, fixture.loads)
            assertEquals(2, fixture.documents.previews)
            assertEquals(1, fixture.activeLoads)
            assertEquals(1, fixture.peakActiveLoads)
            interrupted.close()
            releaseLoad.complete(Unit)
            runCurrent()
            assertTrue(successor.isCurrent)
            assertEquals(4, fixture.loads)
            assertEquals(3, fixture.documents.previews)
            assertEquals(0, fixture.activeLoads)
            assertEquals(1, fixture.peakActiveLoads)
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
            assertFalse(checkNotNull(fixture.vm.uiState.value.associationPreview).previewId == originalPreview)
            assertFalse(fixture.authority.isCurrent(retired))
            assertTrue(fixture.authority.isCurrent(fixture.requests.last()))
            assertEquals(0, fixture.documents.prepared)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally {
            releaseLoad.complete(Unit)
            fixture.vm.close()
            runCurrent()
        }
    }

    @Test(timeout = 20_000L)
    fun lateClearValidationCannotRetireTheSuccessorOrRemoveEitherAssociation(): Unit = runTest {
        for (purpose in listOf(LyricsDocumentPurpose.PRIMARY, LyricsDocumentPurpose.TRANSLATION)) {
            for (lateResult in listOf(false, true)) {
                val fixture = fixture()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var activeValidations = 0
                var peakActiveValidations = 0
                var calls = 0
                try {
                    fixture.tracks.preferences = fixture.tracks.preferences.copy(
                        primary = fixture.documents.association(), translation = fixture.documents.association())
                    val originalPreferences = fixture.tracks.preferences
                    val retiredPresentation = fixture.show()
                    runCurrent()
                    val retiredRequests = fixture.requests.toList()
                    fixture.validationOverride = { request ->
                        if (request == null) withContext(NonCancellable) {
                            calls++
                            activeValidations++
                            peakActiveValidations = maxOf(peakActiveValidations, activeValidations)
                            try {
                                entered.complete(Unit)
                                release.await()
                                lateResult
                            } finally { activeValidations-- }
                        } else null
                    }
                    assertTrue(fixture.vm.clearAssociation(purpose))
                    runCurrent()
                    assertTrue(entered.isCompleted)
                    assertEquals(1, calls)
                    assertEquals(1, activeValidations)
                    assertTrue(fixture.vm.uiState.value.saving)
                    val loadsAtValidation = fixture.loads
                    fixture.vm.setActualEntryRoute(false)
                    retiredPresentation.close()
                    fixture.validationOverride = null
                    val successor = fixture.show()
                    fixture.vm.retry()
                    runCurrent()
                    assertTrue(successor.isCurrent)
                    assertEquals(loadsAtValidation, fixture.loads)
                    assertEquals(1, activeValidations)
                    assertEquals(originalPreferences, fixture.tracks.preferences)
                    retiredRequests.forEach { assertFalse(fixture.authority.isCurrent(it)) }
                    release.complete(Unit)
                    runCurrent()
                    assertEquals(0, activeValidations)
                    assertEquals(1, peakActiveValidations)
                    assertEquals(loadsAtValidation + 2, fixture.loads)
                    assertEquals(1, fixture.peakActiveLoads)
                    assertTrue(successor.isCurrent)
                    val state = fixture.vm.uiState.value
                    assertTrue(state.content is LyricsPageContent.Ready)
                    assertNotNull((state.content as LyricsPageContent.Ready).translation)
                    assertEquals(originalPreferences, state.preferences)
                    assertEquals(originalPreferences, fixture.tracks.preferences)
                    assertNull(state.failure)
                    assertNull(state.notice)
                    assertFalse(state.loading)
                    assertFalse(state.saving)
                    assertEquals(0, fixture.sourceValidations)
                    assertEquals(0, fixture.tracks.commits)
                    assertTrue(fixture.tracks.fences.isEmpty())
                    assertEquals(0, fixture.documents.prepared)
                    fixture.requests.takeLast(2).forEach { assertTrue(fixture.authority.isCurrent(it)) }
                    assertTrue(fixture.playback.dispatched.isEmpty())
                } finally {
                    release.complete(Unit)
                    fixture.validationOverride = null
                    fixture.vm.close()
                    runCurrent()
                }
            }
        }
    }

    @Test(timeout = 20_000L)
    fun lateOffsetValidationCannotRetireTheSuccessorOrSaveTheRetiredValue(): Unit = runTest {
        for (validationStage in listOf("context", "primary", "translation")) {
            for (lateResult in listOf(false, true)) {
                val fixture = fixture()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var activeValidations = 0
                var peakActiveValidations = 0
                var calls = 0
                try {
                    fixture.tracks.preferences = fixture.tracks.preferences.copy(translation = fixture.documents.association())
                    val originalPreferences = fixture.tracks.preferences
                    val retiredPresentation = fixture.show()
                    runCurrent()
                    val retiredRequests = fixture.requests.toList()
                    assertTrue(fixture.vm.beginOffset())
                    fixture.vm.adjustOffset(2)
                    fixture.validationOverride = { request ->
                        val matches = when (validationStage) {
                            "context" -> request == null
                            "primary" -> request?.selection is LyricsLoadSelection.Automatic
                            else -> request?.selection is LyricsLoadSelection.TextDocument
                        }
                        if (matches) withContext(NonCancellable) {
                            calls++
                            activeValidations++
                            peakActiveValidations = maxOf(peakActiveValidations, activeValidations)
                            try {
                                entered.complete(Unit)
                                release.await()
                                lateResult
                            } finally { activeValidations-- }
                        } else null
                    }
                    assertTrue(fixture.vm.saveOffset())
                    runCurrent()
                    assertTrue(entered.isCompleted)
                    assertEquals(1, calls)
                    assertEquals(1, activeValidations)
                    assertTrue(fixture.vm.uiState.value.saving)
                    val loadsAtValidation = fixture.loads
                    fixture.vm.cancelOffset()
                    fixture.vm.setActualEntryRoute(false)
                    retiredPresentation.close()
                    fixture.validationOverride = null
                    val successor = fixture.show()
                    fixture.vm.retry()
                    runCurrent()
                    assertEquals(loadsAtValidation, fixture.loads)
                    assertEquals(1, activeValidations)
                    assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
                    retiredRequests.forEach { assertFalse(fixture.authority.isCurrent(it)) }
                    release.complete(Unit)
                    runCurrent()
                    assertEquals(0, activeValidations)
                    assertEquals(1, peakActiveValidations)
                    assertEquals(loadsAtValidation + 2, fixture.loads)
                    assertEquals(1, fixture.peakActiveLoads)
                    assertTrue(successor.isCurrent)
                    val state = fixture.vm.uiState.value
                    assertTrue(state.content is LyricsPageContent.Ready)
                    assertEquals(originalPreferences, state.preferences)
                    assertEquals(originalPreferences, fixture.tracks.preferences)
                    assertNull(state.failure)
                    assertNull(state.notice)
                    assertNull(state.offsetDraftMs)
                    assertFalse(state.offsetDraftNeedsReview)
                    assertFalse(state.loading)
                    assertFalse(state.saving)
                    assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
                    if (!lateResult) {
                        assertEquals(when (validationStage) { "context" -> 0; "primary" -> 1; else -> 2 },
                            fixture.sourceValidations)
                    }
                    assertEquals(0, fixture.tracks.commits)
                    assertTrue(fixture.tracks.fences.isEmpty())
                    assertEquals(0, fixture.documents.prepared)
                    fixture.requests.takeLast(2).forEach { assertTrue(fixture.authority.isCurrent(it)) }
                    assertTrue(fixture.playback.dispatched.isEmpty())
                    assertEquals(LyricsPlayPhase.IDLE, fixture.vm.playConfirmation.state.value.phase)
                } finally {
                    release.complete(Unit)
                    fixture.validationOverride = null
                    fixture.vm.close()
                    runCurrent()
                }
            }
        }
    }

    @Test(timeout = 20_000L)
    fun lateNullOrMismatchedQueueSnapshotCannotRetireTheSuccessor(): Unit = runTest {
        for (missingSnapshot in listOf(false, true)) {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var activeSnapshots = 0
            var peakActiveSnapshots = 0
            var calls = 0
            try {
                val retiredPresentation = fixture.show()
                runCurrent()
                val retiredRequest = fixture.requests.single()
                val capturedQueue = fixture.queues.current
                fixture.queues.snapshotOverride = {
                    withContext(NonCancellable) {
                        calls++
                        activeSnapshots++
                        peakActiveSnapshots = maxOf(peakActiveSnapshots, activeSnapshots)
                        try {
                            entered.complete(Unit)
                            release.await()
                            if (missingSnapshot) null else capturedQueue.copy(queueRevision = capturedQueue.queueRevision + 1L)
                        } finally { activeSnapshots-- }
                    }
                }
                fixture.vm.retry()
                runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(1, calls)
                assertEquals(1, activeSnapshots)
                val loadsAtSnapshot = fixture.loads
                retiredPresentation.close()
                fixture.queues.snapshotOverride = null
                val successor = fixture.show()
                fixture.vm.retry()
                runCurrent()
                assertTrue(successor.isCurrent)
                assertEquals(loadsAtSnapshot, fixture.loads)
                assertEquals(1, activeSnapshots)
                release.complete(Unit)
                runCurrent()
                assertEquals(0, activeSnapshots)
                assertEquals(1, peakActiveSnapshots)
                assertEquals(loadsAtSnapshot + 1, fixture.loads)
                assertEquals(1, fixture.peakActiveLoads)
                assertTrue(successor.isCurrent)
                val state = fixture.vm.uiState.value
                assertTrue(state.content is LyricsPageContent.Ready)
                assertNull(state.failure)
                assertNull(state.notice)
                assertFalse(state.loading)
                assertFalse(state.saving)
                assertNotSame(retiredRequest, fixture.requests.last())
                assertFalse(fixture.authority.isCurrent(retiredRequest))
                assertTrue(fixture.authority.isCurrent(fixture.requests.last()))
                assertEquals(0, fixture.tracks.commits)
                assertTrue(fixture.tracks.fences.isEmpty())
                assertEquals(0, fixture.documents.prepared)
                assertTrue(fixture.playback.dispatched.isEmpty())
            } finally {
                release.complete(Unit)
                fixture.queues.snapshotOverride = null
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    @Test(timeout = 20_000L)
    fun lateFallbackInputCannotPublishOrFailAfterReentrantPresentationReplacement(): Unit = runTest {
        for (resuming in listOf(false, true)) {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val published = ArrayList<LyricsEntryUiState>()
            val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.vm.uiState.collect { published += it }
            }
            var successor: LyricsEntryViewModel.Presentation? = null
            var calls = 0
            try {
                var retiredPresentation = fixture.show()
                runCurrent()
                if (resuming) {
                    preview(fixture, LyricsDocumentPurpose.TRANSLATION)
                    retiredPresentation.close()
                }
                // A formerly synchronized primary now has no timeline, so input is first read in the final branch.
                fixture.automaticText = "plain retired primary"
                fixture.documents.beforePreview = {
                    val originalJob = checkNotNull(currentCoroutineContext()[Job])
                    withContext(NonCancellable) {
                        calls++
                        entered.complete(Unit)
                        release.await()
                        fixture.documents.beforePreview = null
                        fixture.automaticText = "[00:01]successor\n[00:02]next"
                        fixture.vm.setActualEntryRoute(false)
                        retiredPresentation.close()
                        successor = fixture.show()
                        fixture.vm.retry()
                        // Work is retired synchronously, before collectLatest gets its next scheduling turn.
                        assertTrue(originalJob.isActive)
                    }
                }
                if (resuming) retiredPresentation = fixture.show() else {
                    val request = checkNotNull(fixture.vm.requestDocument(LyricsDocumentPurpose.TRANSLATION))
                    assertTrue(fixture.vm.pickerLaunched(request))
                    fixture.vm.pickerReturned(request, DraftResumeDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(1, calls)
                val loadsAtInput = fixture.loads
                val retiredRequest = fixture.requests.last()
                assertNull(successor)
                published.clear()
                release.complete(Unit)
                runCurrent()
                assertTrue(checkNotNull(successor).isCurrent)
                assertEquals(loadsAtInput + 1, fixture.loads)
                assertFalse(published.any { it.failure != null || it.notice == LyricsEntryNotice.STALE })
                val readyStates = published.mapNotNull { it.content as? LyricsPageContent.Ready }
                assertTrue(readyStates.isNotEmpty())
                assertTrue(readyStates.all { it.document.kind == LyricsKind.SYNCHRONIZED &&
                    it.document.timeline.line(0).text == "successor" })
                val state = fixture.vm.uiState.value
                assertTrue(state.content is LyricsPageContent.Ready)
                assertNull(state.associationPreview)
                assertNull(state.offsetDraftMs)
                assertFalse(state.loading)
                assertFalse(state.saving)
                assertNotSame(retiredRequest, fixture.requests.last())
                assertFalse(fixture.authority.isCurrent(retiredRequest))
                assertTrue(fixture.authority.isCurrent(fixture.requests.last()))
                assertEquals(0, fixture.tracks.commits)
                assertTrue(fixture.tracks.fences.isEmpty())
                assertEquals(0, fixture.documents.prepared)
                assertTrue(fixture.playback.dispatched.isEmpty())
            } finally {
                release.complete(Unit)
                fixture.documents.beforePreview = null
                watcher.cancel()
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    private fun TestScope.fixture() = DraftResumeFixture(this, StandardTestDispatcher(testScheduler))

    private fun TestScope.preview(fixture: DraftResumeFixture, purpose: LyricsDocumentPurpose = LyricsDocumentPurpose.PRIMARY): String {
        val request = checkNotNull(fixture.vm.requestDocument(purpose))
        assertTrue(fixture.vm.pickerLaunched(request))
        fixture.vm.pickerReturned(request, DraftResumeDocuments.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCurrent()
        return checkNotNull(fixture.vm.uiState.value.associationPreview).previewId
    }
}

/** Fresh Registry/VM owners exercise the same SavedState factory that Main uses. Definitions are NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsEntryProcessStateTest {
    @Test fun aNewActivityRegistryDoesNotReplaceTheAuthenticatedApplicationReading(): Unit = runTest {
        val fixture = fixture()
        val business = fixture.vm
        val first = Robolectric.buildActivity(EntryDraftRestoreActivity::class.java).create().start().resume()
        var firstDestroyed = false
        var second: org.robolectric.android.controller.ActivityController<EntryDraftRestoreActivity>? = null
        var external: LyricsEntryViewModel.ExternalReading? = null
        try {
            val draft = ViewModelProvider(first.get(), lyricsEntrySavedStateFactory())[LyricsEntrySavedStateHolder::class.java]
            business.bindSavedDraftState(draft.handle)
            val presentation = fixture.show(LyricsEntrySurface.PAGE)
            runCurrent()
            external = business.bindExternalReading()
            val document = checkNotNull(business.readExternal()).document
            val saved = Bundle()
            presentation.close()
            first.pause().saveInstanceState(saved).stop().destroy()
            firstDestroyed = true
            runCurrent()
            val successor = Robolectric.buildActivity(EntryDraftRestoreActivity::class.java).create(saved).start().resume()
            second = successor
            val successorDraft = ViewModelProvider(successor.get(), lyricsEntrySavedStateFactory())[LyricsEntrySavedStateHolder::class.java]
            business.bindSavedDraftState(successorDraft.handle)
            val successorPresentation = fixture.show(LyricsEntrySurface.PAGE)
            runCurrent()
            assertSame(business, fixture.vm)
            assertSame(document, business.readExternal()?.document)
            assertTrue(external.isCurrent)
            assertEquals(1, fixture.loads)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
            successorPresentation.close()
        } finally {
            external?.close()
            fixture.vm.close()
            second?.pause()?.stop()?.destroy()
            if (!firstDestroyed) first.pause().stop().destroy()
            runCurrent()
        }
    }

    @Test(timeout = 20_000L)
    fun activityRegistryRestoresAnUnsavedOffsetIntoAFreshOwnerWithoutCommands(): Unit = runTest {
        val fixture = fixture()
        fixture.vm.close()
        val first = Robolectric.buildActivity(EntryDraftRestoreActivity::class.java).create().start().resume()
        var firstDestroyed = false
        var second: org.robolectric.android.controller.ActivityController<EntryDraftRestoreActivity>? = null
        try {
            val firstDraft = ViewModelProvider(first.get(), lyricsEntrySavedStateFactory())[LyricsEntrySavedStateHolder::class.java]
            fixture.vm = fixture.createViewModel(firstDraft.handle)
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(3)
            val old = fixture.vm
            val originalRequest = fixture.requests.single()
            val fields = fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET) as List<*>
            assertEquals(7, fields.size)
            assertTrue(fields.all { it is String && it.length <= 64 })
            assertFalse(fields.any { it.toString().contains(DraftResumeDocuments.URI) })
            val saved = Bundle()
            first.pause().saveInstanceState(saved).stop().destroy()
            firstDestroyed = true
            old.close()
            runCurrent()
            fixture.authority = LyricsLoadAuthority()
            val successor = Robolectric.buildActivity(EntryDraftRestoreActivity::class.java).create(saved).start().resume()
            second = successor
            val successorDraft = ViewModelProvider(successor.get(), lyricsEntrySavedStateFactory())[LyricsEntrySavedStateHolder::class.java]
            fixture.vm = fixture.createViewModel(successorDraft.handle)
            assertNotSame(old, fixture.vm)
            val loads = fixture.loads
            runCurrent()
            assertEquals(loads, fixture.loads)
            assertFalse(fixture.vm.uiState.value.saving)
            assertNull(fixture.vm.uiState.value.associationPreview)
            assertNull(fixture.vm.uiState.value.documentRequest)
            assertEquals(LyricsPlayPhase.IDLE, fixture.vm.playConfirmation.state.value.phase)
            fixture.show()
            runCurrent()
            assertEquals(300, fixture.vm.uiState.value.offsetDraftMs)
            assertFalse(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertEquals(0L, fixture.vm.uiState.value.preferences?.revision)
            assertEquals(0, fixture.tracks.preferences.userDelayMs)
            assertNotSame(originalRequest, fixture.requests.last())
            assertEquals(0, fixture.tracks.commits)
            assertEquals(0, fixture.documents.prepared)
            assertTrue(fixture.playback.dispatched.isEmpty())
            assertTrue(fixture.vm.saveOffset())
            runCurrent()
            assertEquals(1, fixture.tracks.commits)
            assertEquals(300, fixture.tracks.preferences.userDelayMs)
            assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
        } finally {
            fixture.vm.close()
            second?.pause()?.stop()?.destroy()
            if (!firstDestroyed) first.pause().stop().destroy()
            runCurrent()
        }
    }

    @Test fun changedRevisionPreservesTheProposalUntilAnExplicitFreshReview(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(4)
            fixture.tracks.preferences = fixture.tracks.preferences.copy(revision = 5L, userDelayMs = -200)
            fixture.recreate()
            fixture.show()
            runCurrent()
            val state = fixture.vm.uiState.value
            assertEquals(400, state.offsetDraftMs)
            assertEquals(5L, state.preferences?.revision)
            assertEquals(-200, state.preferences?.userDelayMs)
            assertEquals(LyricsEntryNotice.CONFLICT, state.notice)
            assertTrue(state.offsetDraftNeedsReview)
            assertTrue(state.canReviewOffsetDraft)
            fixture.vm.adjustOffset(1)
            assertEquals(400, fixture.vm.uiState.value.offsetDraftMs)
            assertFalse(fixture.vm.saveOffset())
            val presentation = fixture.vm.bindPresentation()
            presentation.show(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertTrue(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertTrue(fixture.vm.reviewOffsetDraft())
            runCurrent()
            assertEquals(400, fixture.vm.uiState.value.offsetDraftMs)
            assertFalse(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertFalse(fixture.vm.uiState.value.loading)
            assertFalse(fixture.vm.uiState.value.saving)
            assertTrue(fixture.authority.isCurrent(fixture.requests.last()))
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
            assertTrue(fixture.vm.saveOffset())
            runCurrent()
            assertEquals(1, fixture.tracks.commits)
            assertEquals(6L, fixture.tracks.preferences.revision)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun aDuplicateEntryCannotAdoptTheRecoveredOffsetAndCancelClearsIt(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(2)
            fixture.recreate()
            fixture.queues.selectDuplicate()
            fixture.show()
            runCurrent()
            assertEquals(200, fixture.vm.uiState.value.offsetDraftMs)
            assertTrue(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertFalse(fixture.vm.uiState.value.canReviewOffsetDraft)
            assertEquals(LyricsEntryNotice.STALE, fixture.vm.uiState.value.notice)
            assertFalse(fixture.vm.reviewOffsetDraft())
            assertFalse(fixture.vm.saveOffset())
            fixture.vm.cancelOffset()
            assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
            fixture.recreate()
            fixture.show()
            runCurrent()
            assertNull(fixture.vm.uiState.value.offsetDraftMs)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun equalBytesWithNewDocumentMetadataStillRequireReviewAfterProcessDeath(): Unit = runTest {
        val fixture = fixture()
        fixture.tracks.preferences = fixture.tracks.preferences.copy(primary = fixture.documents.association())
        try {
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(1)
            fixture.recreate()
            fixture.documents.providerContentToken = "revision-two"
            fixture.show()
            runCurrent()
            assertEquals(100, fixture.vm.uiState.value.offsetDraftMs)
            assertTrue(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertFalse(fixture.vm.saveOffset())
            assertTrue(fixture.vm.reviewOffsetDraft())
            runCurrent()
            assertFalse(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertEquals(0, fixture.tracks.commits)
            assertEquals(0, fixture.documents.prepared)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun failedRecoveryKeepsTheOffsetForAReadRetry(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(2)
            fixture.recreate()
            fixture.beforeLoad = { throw java.io.IOException() }
            fixture.show()
            runCurrent()
            assertEquals(200, fixture.vm.uiState.value.offsetDraftMs)
            assertTrue(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertFalse(fixture.vm.saveOffset())
            fixture.beforeLoad = null
            fixture.vm.retry()
            runCurrent()
            assertEquals(200, fixture.vm.uiState.value.offsetDraftMs)
            assertFalse(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertEquals(0, fixture.tracks.commits)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun cancellationRetiresALateNonCooperativeRecoveryWithoutResavingTheDraft(): Unit = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(2)
            fixture.recreate()
            fixture.nonCooperativeLoad = true
            fixture.beforeLoad = { entered.complete(Unit); release.await() }
            fixture.show()
            runCurrent()
            assertTrue(entered.isCompleted)
            fixture.vm.cancelOffset()
            assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
            fixture.vm.setActualEntryRoute(false)
            release.complete(Unit)
            runCurrent()
            assertNull(fixture.vm.uiState.value.offsetDraftMs)
            assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { release.complete(Unit); fixture.vm.close(); runCurrent() }
    }

    @Test fun committedBeforeALostReplyIsReadAsConflictAndNeverReplayed(): Unit = runTest {
        val fixture = fixture()
        val committed = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        var acknowledgments = 0
        try {
            fixture.show()
            runCurrent()
            assertTrue(fixture.vm.beginOffset())
            fixture.vm.adjustOffset(3)
            fixture.tracks.afterCommit = { committed.complete(Unit); reply.await(); acknowledgments++ }
            assertTrue(fixture.vm.saveOffset())
            runCurrent()
            assertTrue(committed.isCompleted)
            assertTrue(fixture.vm.uiState.value.saving)
            fixture.recreate()
            fixture.show()
            runCurrent()
            assertEquals(300, fixture.vm.uiState.value.offsetDraftMs)
            assertTrue(fixture.vm.uiState.value.offsetDraftNeedsReview)
            assertEquals(LyricsEntryNotice.CONFLICT, fixture.vm.uiState.value.notice)
            assertFalse(fixture.vm.uiState.value.saving)
            assertEquals(1, fixture.tracks.commits)
            assertEquals(0, acknowledgments)
            assertTrue(fixture.playback.dispatched.isEmpty())
            fixture.vm.cancelOffset()
            reply.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.tracks.commits)
            assertEquals(0, acknowledgments)
        } finally { reply.complete(Unit); fixture.vm.close(); runCurrent() }
    }

    @Test fun malformedOrOversizedSavedFieldsCannotCreateARestoreAction(): Unit = runTest {
        val fixture = fixture()
        try {
            fixture.vm.close()
            val invalid = listOf<Any>(arrayListOf("2"), arrayListOf("1", "20000", "3", "0", "a".repeat(64),
                "b".repeat(64), "c".repeat(64)), arrayListOf("1", "100", "3", "0", "a".repeat(65),
                "b".repeat(64), "c".repeat(64)), arrayListOf(1, 100, 3, 0))
            for (value in invalid) {
                fixture.vm = fixture.createViewModel(SavedStateHandle(mapOf(LyricsEntrySavedDrafts.KEY_OFFSET to value)))
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertFalse(fixture.vm.uiState.value.saving)
                fixture.vm.close()
            }
            runCurrent()
            assertEquals(0, fixture.loads)
            assertEquals(0, fixture.tracks.commits)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test(timeout = 20_000L)
    fun lateFalseRecoveryValidationCannotRetireTheSuccessorAfterCancelAndRouteExit(): Unit = runTest {
        for (validationStage in listOf("context", "primary", "translation")) {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var activeValidations = 0
            var peakActiveValidations = 0
            var lateValidationCalls = 0
            try {
                if (validationStage == "translation") {
                    fixture.tracks.preferences = fixture.tracks.preferences.copy(translation = fixture.documents.association())
                }
                fixture.show()
                runCurrent()
                assertTrue(fixture.vm.beginOffset())
                fixture.vm.adjustOffset(2)
                fixture.recreate()
                // Hold the real recovery validator after acquisition, rather than the earlier load boundary.
                fixture.validationOverride = { request ->
                    val matches = when (validationStage) {
                        "context" -> request == null
                        "primary" -> request?.selection is LyricsLoadSelection.Automatic
                        else -> request?.selection is LyricsLoadSelection.TextDocument
                    }
                    if (matches) withContext(NonCancellable) {
                        lateValidationCalls++
                        activeValidations++
                        peakActiveValidations = maxOf(peakActiveValidations, activeValidations)
                        try {
                            entered.complete(Unit)
                            release.await()
                            false
                        } finally { activeValidations-- }
                    } else null
                }
                val retiredPresentation = fixture.show()
                runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(1, lateValidationCalls)
                assertEquals(1, activeValidations)
                val loadsAtValidation = fixture.loads
                val retiredRequest = fixture.requests.last()
                fixture.vm.cancelOffset()
                fixture.vm.setActualEntryRoute(false)
                retiredPresentation.close()
                assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
                fixture.validationOverride = null
                fixture.show()
                fixture.vm.retry()
                runCurrent()
                // W2 is queued, but serial work must wait for W1's physical validation cleanup.
                assertEquals(loadsAtValidation, fixture.loads)
                assertEquals(1, activeValidations)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertFalse(fixture.vm.uiState.value.saving)
                release.complete(Unit)
                runCurrent()
                assertEquals(0, activeValidations)
                assertEquals(1, peakActiveValidations)
                assertTrue(fixture.loads > loadsAtValidation)
                assertEquals(1, fixture.peakActiveLoads)
                assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
                assertFalse(fixture.vm.uiState.value.loading)
                assertFalse(fixture.vm.uiState.value.saving)
                assertFalse(fixture.vm.uiState.value.offsetDraftNeedsReview)
                assertNull(fixture.vm.uiState.value.offsetDraftMs)
                assertNull(fixture.savedState.get<Any>(LyricsEntrySavedDrafts.KEY_OFFSET))
                assertNotSame(retiredRequest, fixture.requests.last())
                assertFalse(fixture.authority.isCurrent(retiredRequest))
                assertTrue(fixture.authority.isCurrent(fixture.requests.last()))
                assertEquals(0, fixture.tracks.commits)
                assertTrue(fixture.tracks.fences.isEmpty())
                assertEquals(0, fixture.documents.prepared)
                assertTrue(fixture.playback.dispatched.isEmpty())
                assertEquals(LyricsPlayPhase.IDLE, fixture.vm.playConfirmation.state.value.phase)
            } finally {
                release.complete(Unit)
                fixture.validationOverride = null
                fixture.vm.close()
                runCurrent()
            }
        }
    }

    private fun TestScope.fixture() = DraftResumeFixture(this, StandardTestDispatcher(testScheduler))
}

class EntryDraftRestoreActivity : ComponentActivity()

private class DraftResumeFixture(private val scope: CoroutineScope, private val dispatcher: CoroutineDispatcher) {
    var audio = LocalLyricsLoadFixture().audio
    val queues = DraftResumeQueues(QueueEntry("entry-one", audio.trackKey, 0L, 0L, location = audio.location))
    val tracks = DraftResumeTracks(audio.trackKey, queues)
    val documents = DraftResumeDocuments()
    var authority = LyricsLoadAuthority()
    val playback = DraftResumePlayback(queues)
    val requests = ArrayList<LyricsLoadRequest>()
    var automaticText = AUTOMATIC_TEXT
    var loads = 0
    var sourceValidations = 0
    var activeLoads = 0
    var peakActiveLoads = 0
    var beforeLoad: (suspend (LyricsLoadRequest) -> Unit)? = null
    var nonCooperativeLoad = false
    // A null request identifies context validation; null results keep the fixture's normal validation.
    var validationOverride: (suspend (LyricsLoadRequest?) -> Boolean?)? = null
    var savedState = SavedStateHandle()
    var vm = createViewModel(savedState)

    fun createViewModel(state: SavedStateHandle): LyricsEntryViewModel {
        savedState = state
        val ownerAuthority = authority
        return LyricsEntryViewModel(
            queues = queues,
            resolveContext = { entry ->
                check(entry.trackKey == audio.trackKey)
                LocalLyricsContext(audio, null)
            },
            validateContext = { validationOverride?.invoke(null) ?: (it.audio == audio) },
            tracks = tracks,
            documents = documents,
            authority = ownerAuthority,
            load = { request ->
                loads++
                requests += request
                if (nonCooperativeLoad) withContext(NonCancellable) { acquire(request) } else acquire(request)
            },
            validate = { request, loaded ->
                sourceValidations++
                validationOverride?.invoke(request) ?:
                    (ownerAuthority.isCurrent(request) && loaded.binding == request.binding && loaded.audio == audio)
            },
            playback = playback,
            translationMatcher = LyricsTranslationMatcher(LyricsLimits(), dispatcher),
            scopeOverride = scope,
            awaitDisplayFrame = {},
            savedStateHandle = SavedStateHandle(),
        ).also { it.bindSavedDraftState(state) }
    }

    fun recreate() {
        val captured = savedState.keys().associateWith { savedState.get<Any>(it) }
        vm.close()
        authority = LyricsLoadAuthority()
        vm = createViewModel(SavedStateHandle(captured))
    }

    fun show(surface: LyricsEntrySurface = LyricsEntrySurface.OPTIONS): LyricsEntryViewModel.Presentation {
        vm.setActualEntryRoute(true)
        return vm.bindPresentation().also { it.show(surface) }
    }

    private suspend fun acquire(request: LyricsLoadRequest): LocalLyricsLoadResult {
        activeLoads++
        peakActiveLoads = maxOf(peakActiveLoads, activeLoads)
        try {
            beforeLoad?.invoke(request)
            val input = (request.selection as? LyricsLoadSelection.TextDocument)?.input
            val bytes = (if (input == null) automaticText else documents.text).toByteArray()
            val sha256 = draftResumeSha256(bytes)
            if (input?.expectedContentSha256?.let { it != sha256 } == true) {
                return LocalLyricsLoadResult.Unavailable(request.binding, LyricsLoadFailure(LyricsLoadIssue.SOURCE_CHANGED),
                    LyricsSiblingStatus.NOT_REQUESTED, emptyList())
            }
            val document = LocalLyricsParser(LyricsLimits(), dispatcher).parse(bytes, input?.encoding ?: LyricsEncodingChoice.AUTO_BOM_OR_UTF8)
            val source = LyricsLoadedSource(
                if (input == null) LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED else LyricsSourceOrigin.EXPLICIT_DOCUMENT,
                input?.source?.sourceId ?: audio.source.spec.sourceId,
                input?.candidate?.objectId ?: audio.objectId,
                input?.documentId ?: "embedded-document", request.binding.sourceGeneration, sha256,
                if (input == null) "embedded-document" else null,
            )
            return LocalLyricsLoadResult.Loaded(request.binding, audio, source, document, emptyList(), emptyList(),
                LyricsSiblingStatus.NOT_REQUESTED, document.accountedResidentBytes)
        } finally { activeLoads-- }
    }

    companion object { const val AUTOMATIC_TEXT = "[00:01]automatic\n[00:02]second" }
}

private class DraftResumeDocuments : LyricsDocuments {
    var previews = 0
    var prepared = 0
    var text = TEXT
    var objectId = "document-one"
    var providerContentToken: String? = "revision-one"
    var beforePreview: (suspend () -> Unit)? = null

    private fun input(sourceId: String, generation: Long, encoding: LyricsEncodingChoice): LyricsDocumentInput {
        val source = LocalSourceSpec(sourceId, LocalSourceKind.DOCUMENT, URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val candidate = ObservedCandidate(source.sourceId, objectId, URI, null, "chosen.lrc", "text/plain",
            LocalNodeKind.OTHER, text.toByteArray().size.toLong()).copy(providerContentToken = providerContentToken)
        return LyricsDocumentInput(candidate.objectId, generation, source, candidate, encoding)
    }

    override suspend fun preview(uri: String, returnedFlags: Int, generation: Long, encoding: LyricsEncodingChoice): LyricsDocumentInput {
        check(uri == URI && returnedFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
        previews++
        beforePreview?.invoke()
        return input("manual-source-$previews", generation, encoding)
    }

    override suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput =
        input(association.sourceId, generation, association.encoding).copy(expectedContentSha256 = association.confirmedContentSha256)

    override suspend fun prepareAssociation(input: LyricsDocumentInput, confirmedSha256: String): PreparedLyricsAssociation {
        check(confirmedSha256 == draftResumeSha256(text.toByteArray()))
        prepared++
        return PreparedLyricsAssociation(LyricsAssociation(input.documentId, input.source.sourceId, input.candidate.uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION, input.encoding, confirmedSha256), input) {}
    }

    fun association() = LyricsAssociation(objectId, "saved-source", URI, Intent.FLAG_GRANT_READ_URI_PERMISSION,
        LyricsEncodingChoice.AUTO_BOM_OR_UTF8, draftResumeSha256(text.toByteArray()))

    companion object {
        const val URI = "content://lyrics.draft.resume.fixture/document/one"
        const val TEXT = "[00:01]chosen\n[00:02]chosen second"
    }
}

private class DraftResumeTracks(key: TrackKey, private val queues: DraftResumeQueues) : LyricsTrackStore {
    var preferences = LyricsTrackPreferences(key)
    var commits = 0
    var afterCommit: (suspend () -> Unit)? = null
    val fences = ArrayList<LyricsTrackWriteFence>()
    override suspend fun read(trackKey: TrackKey): LyricsTrackReadResult {
        check(trackKey == preferences.trackKey)
        return LyricsTrackReadResult.Ready(preferences)
    }

    override suspend fun commit(expectedRevision: Long, next: LyricsTrackPreferences, fence: LyricsTrackWriteFence,
        stillCurrent: suspend () -> Boolean): LyricsTrackCommitResult {
        if (!stillCurrent()) return LyricsTrackCommitResult.Stale(preferences)
        if (expectedRevision != preferences.revision) return LyricsTrackCommitResult.Conflict(preferences)
        check(queues.current.currentEntryId == fence.queueEntryId && queues.current.queueRevision == fence.queueRevision)
        check(next.revision == expectedRevision)
        fences += fence
        commits++
        preferences = next.copy(revision = expectedRevision + 1L)
        afterCommit?.invoke()
        return LyricsTrackCommitResult.Saved(preferences)
    }
}

private class DraftResumeQueues(val first: QueueEntry) : QueueStore {
    private val duplicate = first.copy(queueEntryId = "entry-duplicate", originalOrder = 1L, playbackOrder = 1L)
    private val state = MutableStateFlow(QueueSnapshot("queue-one", 3L, 0L, FrozenQueueView("view-one", 1L, 2L),
        QueueMode(), QueueCheckpoint(first.queueEntryId, 0L), 0L, 0L))
    val current: QueueSnapshot get() = state.value
    var snapshotOverride: (suspend () -> QueueSnapshot?)? = null
    fun reviseWithoutSelecting() { state.value = current.copy(queueRevision = current.queueRevision + 1L) }
    fun selectDuplicate() {
        state.value = current.copy(checkpointRevision = current.checkpointRevision + 1L,
            checkpoint = QueueCheckpoint(duplicate.queueEntryId, 0L), currentOriginalOrder = 1L, currentPlaybackOrder = 1L)
    }
    override suspend fun initialize(): QueueSnapshot = current
    override suspend fun snapshot(): QueueSnapshot? {
        val readSnapshot = snapshotOverride
        return if (readSnapshot == null) current else readSnapshot()
    }
    override fun snapshots(): Flow<QueueSnapshot?> = state
    override suspend fun window(generationId: String, currentEntryId: String) = QueueWindow(generationId,
        current.queueRevision, 2L, listOf(first, duplicate), if (currentEntryId == first.queueEntryId) 0 else 1)
    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> = error("No full queue page")
    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = error("No queue replacement")
    override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = error("No queue mutation")
    override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint): QueueSnapshot = error("No second checkpoint owner")
}

private class DraftResumePlayback(private val queues: DraftResumeQueues) : LyricsPlaybackPort {
    override val changes: Flow<Unit> = emptyFlow()
    override val busy = false
    var playing = false
    var clockReads = 0
    val dispatched = ArrayList<QueueSeekRequest>()
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

private fun draftResumeSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
