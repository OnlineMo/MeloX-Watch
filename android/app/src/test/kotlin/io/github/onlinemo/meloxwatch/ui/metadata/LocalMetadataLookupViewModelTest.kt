package io.github.onlinemo.meloxwatch.ui.metadata

import androidx.lifecycle.SavedStateHandle
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsMemoryOwner
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadFixture
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadBinding
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataClient
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataStore
import io.github.onlinemo.meloxwatch.data.metadata.MetadataArtifact
import io.github.onlinemo.meloxwatch.data.metadata.MetadataAssociation
import io.github.onlinemo.meloxwatch.data.metadata.MetadataClearReceipt
import io.github.onlinemo.meloxwatch.data.metadata.MetadataClearTarget
import io.github.onlinemo.meloxwatch.data.metadata.MetadataCommitReceipt
import io.github.onlinemo.meloxwatch.data.metadata.MetadataLookupTarget
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.MetadataSearchPage
import io.github.onlinemo.meloxwatch.data.metadata.MetadataSearchQuery
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreException
import io.github.onlinemo.meloxwatch.data.metadata.MetadataStoreIssue
import io.github.onlinemo.meloxwatch.data.metadata.metadataSha256
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
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
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled timing boundaries exercise intents; actual Room/files and protocol have separate regressions. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalMetadataLookupViewModelTest {
    @Test fun startupEntryAndPersistingExplicitEnableProduceNoRequests(): Unit = runTest {
        val fixture = LookupFixture(this, initiallyEnabled = false)
        try {
            runCurrent()
            assertEquals(0, fixture.clientCreations)
            fixture.vm.setVisible(true)
            runCurrent()
            assertFalse(fixture.vm.state.value.enabled)
            assertTrue(fixture.vm.state.value.lookupTargetReady)
            assertEquals(0, fixture.api.searches.size)
            fixture.vm.setEnabled(true)
            runCurrent()
            assertTrue(fixture.preferences.current.metadataLookupEnabled)
            assertTrue(fixture.preferences.current.bluetoothLyricsEnabled)
            assertFalse(fixture.preferences.current.restorePosition)
            assertEquals(1, fixture.preferences.saves)
            assertEquals(0, fixture.clientCreations)
            assertEquals(0, fixture.store.applies)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun searchUsesReviewedFieldsAndMoreReplacesABoundedPageOnlyAfterAnotherAction(): Unit = runTest {
        val fixture = LookupFixture(this)
        try {
            fixture.api.pages = listOf(MetadataSearchPage((201..220).map { source(it.toString()) }, 20, true),
                MetadataSearchPage(listOf(source("221")), 1, false))
            fixture.vm.setVisible(true)
            runCurrent()
            fixture.vm.setSendArtist(false)
            fixture.vm.search()
            runCurrent()
            assertEquals(listOf(0L), fixture.api.searches.map { it.second })
            assertEquals("Fixture title", fixture.api.searches.single().first.words)
            assertEquals(null, fixture.api.searches.single().first.artist)
            assertEquals(20, fixture.vm.state.value.candidates.size)
            assertTrue(fixture.vm.state.value.more)
            runCurrent()
            assertEquals(1, fixture.api.searches.size)
            fixture.vm.more()
            runCurrent()
            assertEquals(listOf(0L, 20L), fixture.api.searches.map { it.second })
            assertEquals(listOf("221"), fixture.vm.state.value.candidates.map { it.id })
            assertFalse(fixture.vm.state.value.more)
            assertEquals(0, fixture.store.applies)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun aFilenameIsDisplayedLocallyButNeverPrefilledAsAQuery(): Unit = runTest {
        val fixture = LookupFixture(this).apply { store.rawTitle = null; store.rawArtist = null }
        try {
            fixture.vm.setVisible(true)
            runCurrent()
            assertEquals("Private fixture filename.mp3", fixture.vm.state.value.targetTitle)
            assertEquals("", fixture.vm.state.value.title)
            assertEquals("", fixture.vm.state.value.artist)
            fixture.vm.search()
            runCurrent()
            assertEquals(LocalMetadataIssue.INVALID_QUERY, fixture.vm.state.value.issue)
            assertEquals(0, fixture.clientCreations)
            fixture.vm.setTitle("Reviewed fixture title")
            fixture.vm.search()
            runCurrent()
            assertEquals("Reviewed fixture title", fixture.api.searches.single().first.words)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun previewCancelAndAnUnrecognizedConfirmationNeverWrite(): Unit = runTest {
        val fixture = LookupFixture(this)
        try {
            fixture.openPreview()
            val id = checkNotNull(fixture.vm.state.value.preview).id
            assertEquals("Fixture lyric", fixture.vm.state.value.preview?.lyricsText)
            assertEquals(0, fixture.store.applies)
            fixture.vm.confirm("unrecognized-ticket")
            runCurrent()
            assertEquals(0, fixture.store.applies)
            fixture.vm.cancelPreview()
            fixture.vm.confirm(id)
            runCurrent()
            assertNull(fixture.vm.state.value.preview)
            assertEquals(0, fixture.store.applies)
            assertEquals(1, fixture.api.lyricsCalls)
            assertNotNull(fixture.memory.acquire(Any())?.also { it.close() })
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun onlyTheDisplayedPreviewCommitsAndRecreationDoesNotReplayTheRequestOrWrite(): Unit = runTest {
        val fixture = LookupFixture(this)
        var recreated: LocalMetadataLookupViewModel? = null
        try {
            fixture.openPreview()
            fixture.vm.confirm(checkNotNull(fixture.vm.state.value.preview).id)
            runCurrent()
            assertEquals(1, fixture.store.applies)
            assertEquals(LocalMetadataNotice.SAVED, fixture.vm.state.value.notice)
            assertEquals("201", fixture.vm.state.value.associated?.id)
            assertNull(fixture.vm.state.value.preview)
            val searches = fixture.api.searches.size
            fixture.vm.close()
            runCurrent()
            recreated = fixture.model(fixture.saved)
            recreated.setVisible(true)
            runCurrent()
            assertEquals("Fixture title", recreated.state.value.title)
            assertEquals("201", recreated.state.value.associated?.id)
            assertEquals(searches, fixture.api.searches.size)
            assertEquals(1, fixture.api.lyricsCalls)
            assertEquals(1, fixture.store.applies)
            assertNull(recreated.state.value.preview)
        } finally { recreated?.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun checkpointSamplingKeepsThePreviewButSelectingADuplicateEntryRevokesIt(): Unit = runTest {
        val fixture = LookupFixture(this)
        try {
            fixture.openPreview()
            val before = checkNotNull(fixture.vm.state.value.preview).id
            fixture.queues.positionOnly()
            runCurrent()
            assertEquals(before, fixture.vm.state.value.preview?.id)
            assertEquals(1, fixture.api.lyricsCalls)
            fixture.queues.selectDuplicate()
            runCurrent()
            assertNull(fixture.vm.state.value.preview)
            assertEquals(LocalMetadataIssue.SOURCE_CHANGED, fixture.vm.state.value.issue)
            fixture.vm.confirm(before)
            assertEquals(0, fixture.store.applies)
            assertEquals(1, fixture.api.lyricsCalls)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun hidingRetainsTheSingleMemoryReservationUntilNonCooperativeFetchActuallyRetires(): Unit = runTest {
        val fixture = LookupFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.api.beforeLyrics = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            fixture.vm.setVisible(true)
            runCurrent()
            fixture.vm.search()
            runCurrent()
            fixture.vm.preview("201")
            runCurrent()
            assertTrue(entered.isCompleted)
            assertNull(fixture.memory.acquire(Any()))
            val captures = fixture.store.captures
            fixture.vm.setVisible(false)
            fixture.vm.setVisible(true)
            runCurrent()
            assertNull(fixture.vm.state.value.preview)
            assertEquals(captures, fixture.store.captures)
            assertNull(fixture.memory.acquire(Any()))
            release.complete(Unit)
            runCurrent()
            assertTrue(fixture.store.captures > captures)
            assertNotNull(fixture.memory.acquire(Any())?.also { it.close() })
            assertEquals(1, fixture.api.lyricsCalls)
            assertEquals(0, fixture.store.applies)
        } finally { release.complete(Unit); fixture.vm.close(); runCurrent() }
    }

    @Test fun aSavedDisableCancelsThePreviewAndProducesNoAutomaticRefetch(): Unit = runTest {
        val fixture = LookupFixture(this)
        try {
            fixture.openPreview()
            fixture.preferences.replace(fixture.preferences.current.copy(revision = 10L, metadataLookupEnabled = false))
            runCurrent()
            assertFalse(fixture.vm.state.value.enabled)
            assertNull(fixture.vm.state.value.preview)
            fixture.vm.search()
            fixture.vm.retry()
            runCurrent()
            assertEquals(1, fixture.api.searches.size)
            assertEquals(1, fixture.api.lyricsCalls)
            assertEquals(0, fixture.store.applies)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun anUnreadableAudioSourceStillAllowsClearingItsOwnAssociationWithoutHTTP(): Unit = runTest {
        val fixture = LookupFixture(this, initiallyEnabled = false)
        fixture.store.available = false
        fixture.store.association = MetadataAssociation(fixture.store.key, MetadataPurpose.LYRICS, 3L,
            assetId = "00000000-0000-0000-0000-000000000201", sha256 = "a".repeat(64), byteCount = 20L, provenance = source())
        try {
            fixture.vm.setVisible(true)
            runCurrent()
            assertEquals(LocalMetadataIssue.SOURCE_CHANGED, fixture.vm.state.value.issue)
            assertTrue(fixture.vm.state.value.canClear)
            assertFalse(fixture.vm.state.value.lookupTargetReady)
            fixture.vm.clear()
            runCurrent()
            assertEquals(1, fixture.store.clears)
            assertNull(fixture.store.association.sha256)
            assertEquals(4L, fixture.store.association.revision)
            assertEquals(LocalMetadataNotice.CLEARED, fixture.vm.state.value.notice)
            assertEquals(0, fixture.clientCreations)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun cancellationAfterADurableWriteRecapturesTheResultWithoutRepeatingConfirmation(): Unit = runTest {
        val fixture = LookupFixture(this)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.afterApply = { committed.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            fixture.openPreview()
            fixture.vm.confirm(checkNotNull(fixture.vm.state.value.preview).id)
            runCurrent()
            assertTrue(committed.isCompleted)
            fixture.vm.setVisible(false)
            release.complete(Unit)
            runCurrent()
            fixture.vm.setVisible(true)
            runCurrent()
            assertEquals("201", fixture.vm.state.value.associated?.id)
            fixture.vm.retry()
            runCurrent()
            assertEquals(1, fixture.store.applies)
            assertEquals(1, fixture.api.lyricsCalls)
            assertNull(fixture.vm.state.value.preview)
        } finally { release.complete(Unit); fixture.vm.close(); runCurrent() }
    }

    @Test fun maliciousLyricsAndInvalidArtworkKeepTheOldAssociation(): Unit = runTest {
        val fixture = LookupFixture(this)
        val old = MetadataAssociation(fixture.store.key, MetadataPurpose.LYRICS, 3L,
            sha256 = "a".repeat(64), byteCount = 20L, provenance = source("299"))
        fixture.store.association = old
        fixture.api.text = "Fixture\u0000lyric"
        try {
            fixture.openPreview()
            assertNull(fixture.vm.state.value.preview)
            assertEquals(LocalMetadataIssue.INVALID_RESPONSE, fixture.vm.state.value.issue)
            assertEquals(old, fixture.store.association)
            assertEquals(0, fixture.store.applies)
            fixture.vm.setVisible(false)
            fixture.store.association = MetadataAssociation(fixture.store.key, MetadataPurpose.ARTWORK, 3L,
                sha256 = "a".repeat(64), byteCount = 20L, provenance = source("299"))
            fixture.vm.setVisible(true, LocalMetadataKind.ARTWORK)
            runCurrent()
            fixture.vm.search()
            runCurrent()
            fixture.vm.preview("201")
            runCurrent()
            assertEquals(LocalMetadataIssue.INVALID_IMAGE, fixture.vm.state.value.issue)
            assertNull(fixture.vm.state.value.preview)
            assertEquals("299", fixture.store.association.provenance?.songId)
            assertEquals(0, fixture.store.applies)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun memoryPressureRetiresTheReservationAndKeepsTheWorkerAvailableForExplicitRetry(): Unit = runTest {
        val fixture = LookupFixture(this)
        fixture.api.beforeLyrics = { throw OutOfMemoryError("Controlled metadata pressure") }
        try {
            fixture.openPreview()
            assertEquals(LocalMetadataIssue.LOW_MEMORY, fixture.vm.state.value.issue)
            assertNull(fixture.vm.state.value.preview)
            assertEquals(0, fixture.store.applies)
            assertNotNull(fixture.memory.acquire(Any())?.also { it.close() })
            fixture.api.beforeLyrics = null
            fixture.vm.retry()
            runCurrent()
            assertNotNull(fixture.vm.state.value.preview)
            assertEquals(2, fixture.api.lyricsCalls)
            assertEquals(0, fixture.store.applies)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun aFailedCaptureDisablesSearchAndAnExplicitRetryRestoresTheActualTarget(): Unit = runTest {
        val fixture = LookupFixture(this)
        fixture.store.captureFailure = MetadataStoreException(MetadataStoreIssue.BUSY)
        try {
            fixture.vm.setVisible(true)
            runCurrent()
            assertEquals(LocalMetadataIssue.BUSY, fixture.vm.state.value.issue)
            assertFalse(fixture.vm.state.value.lookupTargetReady)
            fixture.vm.search()
            runCurrent()
            assertEquals(0, fixture.clientCreations)
            fixture.store.captureFailure = null
            fixture.vm.retry()
            runCurrent()
            assertTrue(fixture.vm.state.value.lookupTargetReady)
            assertEquals(0, fixture.api.searches.size)
            fixture.vm.search()
            runCurrent()
            assertEquals(1, fixture.api.searches.size)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun aFailedObserverRetiresPreviewAndIsResubscribedOnlyByAnExplicitReadRetry(): Unit = runTest {
        val fixture = LookupFixture(this)
        try {
            fixture.openPreview()
            fixture.queues.observationFailure.value = IOException("Controlled metadata observation failure")
            runCurrent()
            assertFalse(fixture.vm.state.value.lookupTargetReady)
            assertNull(fixture.vm.state.value.preview)
            fixture.queues.observationFailure.value = null
            runCurrent()
            assertFalse(fixture.vm.state.value.lookupTargetReady)
            assertEquals(1, fixture.api.searches.size)
            fixture.vm.retry()
            runCurrent()
            assertTrue(fixture.vm.state.value.lookupTargetReady)
            assertEquals(1, fixture.api.searches.size)
            fixture.vm.search()
            runCurrent()
            assertEquals(2, fixture.api.searches.size)
        } finally { fixture.vm.close(); runCurrent() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class LookupFixture(private val scope: TestScope, initiallyEnabled: Boolean = true) {
    val audio = LocalLyricsLoadFixture().audio
    val queues = MetadataQueues(QueueEntry("metadata-entry", audio.trackKey, 0L, 0L, location = audio.location))
    val store = MetadataStoreFixture(audio, queues)
    val api = MetadataClientFixture()
    val memory = LyricsMemoryOwner()
    val saved = SavedStateHandle()
    val preferences = MetadataPreferencesFixture(UserPreferences(revision = 4L, restorePosition = false,
        bluetoothLyricsEnabled = true, metadataLookupEnabled = initiallyEnabled))
    var clientCreations = 0
    fun model(handle: SavedStateHandle = SavedStateHandle()) = LocalMetadataLookupViewModel(store, { clientCreations++; api },
        preferences, queues, memory, previewArtwork = { _, _ -> throw ArtworkException(ArtworkIssue.INVALID_IMAGE) },
        savedStateHandle = handle, scopeOverride = scope.backgroundScope, workDispatcher = StandardTestDispatcher(scope.testScheduler))
    val vm = model(saved)
    fun openPreview() {
        vm.setVisible(true)
        scope.runCurrent()
        vm.search()
        scope.runCurrent()
        vm.preview("201")
        scope.runCurrent()
    }
}

private class MetadataClientFixture : LocalMetadataClient {
    var pages = listOf(MetadataSearchPage(listOf(source()), 1, false))
    val searches = ArrayList<Pair<MetadataSearchQuery, Long>>()
    var lyricsCalls = 0
    var text = "[00:01]Fixture lyric"
    var beforeLyrics: (suspend () -> Unit)? = null
    override suspend fun search(query: MetadataSearchQuery, offset: Long): MetadataSearchPage {
        searches += query to offset
        return pages[if (offset == 0L) 0 else 1]
    }
    override suspend fun lyrics(candidate: MetadataProvenance, maxAccountedBytes: Long): MetadataArtifact {
        lyricsCalls++
        beforeLyrics?.invoke()
        return MetadataArtifact(text.toByteArray(), candidate)
    }
    override suspend fun artwork(candidate: MetadataProvenance) = MetadataArtifact(byteArrayOf(1, 2, 3), candidate)
}

private class MetadataPreferencesFixture(initial: UserPreferences) : UserPreferencesRepository {
    override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(initial))
    val current get() = (state.value as PreferencesState.Ready).preferences
    var saves = 0
    fun replace(value: UserPreferences) { state.value = PreferencesState.Ready(value) }
    override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult {
        if (expectedRevision != current.revision) return PreferencesWriteResult.Conflict(current)
        check(draft.copy(metadataLookupEnabled = current.metadataLookupEnabled) == current.asDraft())
        saves++
        val next = current.copy(revision = current.revision + 1, metadataLookupEnabled = draft.metadataLookupEnabled)
        replace(next)
        return PreferencesWriteResult.Saved(next)
    }
    override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation) = PreferencesWriteResult.Cancelled(current)
    override fun retryRead() = Unit
}

private class MetadataStoreFixture(private val audio: io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor,
    private val queues: MetadataQueues) : LocalMetadataStore {
    val key = audio.trackKey
    var association = MetadataAssociation(key, MetadataPurpose.LYRICS)
    var lyricsRevision = 0L
    var available = true
    var captureFailure: Exception? = null
    var rawTitle: String? = "Fixture title"
    var rawArtist: String? = "Fixture artist"
    var captures = 0
    var applies = 0
    var clears = 0
    var afterApply: (suspend () -> Unit)? = null
    private val revisions = MutableStateFlow(0L)
    override fun changes(trackKey: TrackKey, purpose: MetadataPurpose): Flow<Unit> = revisions.map { Unit }
    override suspend fun capture(purpose: MetadataPurpose): MetadataLookupTarget {
        captures++
        captureFailure?.let { throw it }
        if (!available) throw SourceReadException(SourceReadIssue.PERMISSION_REVOKED)
        val queue = queues.current
        val binding = LyricsLoadBinding(key, audio.location.locationId, audio.location.contentRevision,
            audio.source.spec.sourceId, audio.source.revision, audio.source.authEpoch, 0L, 0L)
        return MetadataLookupTarget(purpose, LocalLyricsContext(audio, null),
            LyricsTrackWriteFence(binding, queue.generationId, queue.queueRevision, checkNotNull(queue.currentEntryId)),
            "metadata-version", read(key, purpose).revision, lyricsRevision, "Private fixture filename.mp3", rawTitle, rawArtist)
    }
    override suspend fun captureClear(purpose: MetadataPurpose): MetadataClearTarget {
        val queue = queues.current
        return MetadataClearTarget(key, purpose, queue.generationId, queue.queueRevision, checkNotNull(queue.currentEntryId),
            "metadata-version", read(key, purpose).revision, lyricsRevision, "Private fixture filename.mp3", rawTitle, rawArtist)
    }
    override suspend fun read(trackKey: TrackKey, purpose: MetadataPurpose) =
        if (association.purpose == purpose) association else MetadataAssociation(key, purpose)
    override suspend fun isCurrent(target: MetadataLookupTarget): Boolean = available &&
        queues.current.generationId == target.fence.queueGenerationId && queues.current.queueRevision == target.fence.queueRevision &&
        queues.current.currentEntryId == target.fence.queueEntryId && read(key, target.purpose).revision == target.associationRevision &&
        (target.purpose != MetadataPurpose.LYRICS || target.lyricsRevision == lyricsRevision)
    override suspend fun clearStillCurrent(target: MetadataClearTarget): Boolean =
        queues.current.generationId == target.generationId && queues.current.queueRevision == target.queueRevision &&
        queues.current.currentEntryId == target.entryId && read(key, target.purpose).revision == target.associationRevision &&
        (target.purpose != MetadataPurpose.LYRICS || target.lyricsRevision == lyricsRevision)
    override suspend fun apply(target: MetadataLookupTarget, bytes: ByteArray, provenance: MetadataProvenance,
        stillCurrent: () -> Boolean): MetadataCommitReceipt {
        if (!isCurrent(target) || !stillCurrent()) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
        currentCoroutineContext().ensureActive()
        applies++
        association = MetadataAssociation(key, target.purpose, target.associationRevision + 1,
            "00000000-0000-0000-0000-000000000201", metadataSha256(bytes), bytes.size.toLong(), provenance)
        if (target.purpose == MetadataPurpose.LYRICS) lyricsRevision++
        revisions.value++
        afterApply?.invoke()
        return MetadataCommitReceipt(target.copy(associationRevision = association.revision, lyricsRevision = lyricsRevision), association)
    }
    override suspend fun clear(target: MetadataClearTarget, stillCurrent: () -> Boolean): MetadataClearReceipt {
        if (!clearStillCurrent(target) || !stillCurrent()) throw MetadataStoreException(MetadataStoreIssue.SOURCE_CHANGED)
        clears++
        association = MetadataAssociation(key, target.purpose, target.associationRevision + 1)
        revisions.value++
        return MetadataClearReceipt(target.copy(associationRevision = association.revision), association)
    }
}

private class MetadataQueues(private val first: QueueEntry) : QueueStore {
    private val duplicate = first.copy(queueEntryId = "metadata-duplicate", originalOrder = 1L, playbackOrder = 1L)
    private val state = MutableStateFlow(QueueSnapshot("metadata-generation", 3L, 0L, FrozenQueueView("metadata-view", 1L, 2L),
        QueueMode(), QueueCheckpoint(first.queueEntryId, 0L), 0L, 0L))
    val observationFailure = MutableStateFlow<Exception?>(null)
    val current get() = state.value
    fun positionOnly() { state.value = current.copy(checkpointRevision = current.checkpointRevision + 1,
        checkpoint = QueueCheckpoint(current.currentEntryId, 1_000L)) }
    fun selectDuplicate() { state.value = current.copy(checkpointRevision = current.checkpointRevision + 1,
        checkpoint = QueueCheckpoint(duplicate.queueEntryId, 0L), currentOriginalOrder = 1L, currentPlaybackOrder = 1L) }
    override suspend fun initialize() = current
    override suspend fun snapshot() = current
    override fun snapshots(): Flow<QueueSnapshot?> = combine(state, observationFailure) { queue, failure ->
        if (failure != null) throw failure
        queue
    }
    override suspend fun window(generationId: String, currentEntryId: String) = QueueWindow(generationId, current.queueRevision,
        2L, listOf(first, duplicate), if (currentEntryId == first.queueEntryId) 0 else 1)
    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> = error("No full queue read")
    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = error("No metadata playback replacement")
    override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = error("No metadata playback mutation")
    override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint): QueueSnapshot = error("No metadata checkpoint owner")
}

private fun source(id: String = "201") = MetadataProvenance(id, "Fixture song", "Fixture artist", "Fixture album")
