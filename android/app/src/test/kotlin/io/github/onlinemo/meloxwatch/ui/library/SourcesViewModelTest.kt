package io.github.onlinemo.meloxwatch.ui.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.data.local.FixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureDescriptor
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.LocalQueueCommand
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** Real document reads and main-Looper cancellation; native audio decoding remains device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, application = MeloXApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SourcesViewModelTest {
    private lateinit var app: MeloXApplication
    private lateinit var store: ViewModelStore
    private lateinit var viewModel: SourcesViewModel
    private var beforeMetadata: (() -> Unit)? = null
    private val releases = mutableListOf<CountDownLatch>()

    @Before
    fun createViewModel() = runBlocking {
        app = RuntimeEnvironment.getApplication() as MeloXApplication
        // 正常预览用例须先等真实恢复退出共享IO通道，不能把启动占用当成选择结果。
        withTimeout(10_000L) {
            while (app.backups.startup.value is BackupStartupState.Loading) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(1L)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(app.backups.startup.value is BackupStartupState.Ready)
        store = ViewModelStore()
        viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[SourcesViewModel::class.java]
        // Only the platform decoder is controlled; source identity, file bytes and proof use production code.
        DataSource.setFileDescriptorTransform { _, _ -> beforeMetadata?.invoke(); METADATA_SOURCE }
        ShadowMediaMetadataRetriever.addMetadata(METADATA_SOURCE, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(METADATA_SOURCE, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "no")
        ShadowMediaMetadataRetriever.addMetadata(METADATA_SOURCE, MediaMetadataRetriever.METADATA_KEY_DURATION, "60000")
        ShadowMediaMetadataRetriever.addMetadata(METADATA_SOURCE, MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "audio/mpeg")
    }

    @After
    fun releaseViewModel() = runBlocking {
        releases.forEach { it.countDown() }
        store.clear()
        awaitState { !it.working }
        withTimeout(10_000L) { app.localProviderIo.awaitIdle() }
        beforeMetadata = null
        ShadowMediaMetadataRetriever.reset()
    }

    @Test
    fun latestSelectionWaitsForAnIgnoringReaderAndDiscardsTheIntermediateRequest() = runBlocking {
        val old = fixture("old")
        val intermediate = fixture("intermediate")
        val latest = fixture("latest")
        val gate = blockQuery(old.provider)
        select(old)
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))

        select(intermediate)
        select(latest)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.uiState.value.stopping)
        assertNull(viewModel.uiState.value.selected)
        assertEquals(0, intermediate.provider.queryReads)
        assertEquals(0, latest.provider.queryReads)

        gate.release.countDown()
        val state = awaitState { !it.working && it.selected?.observation?.source?.uri == latest.uri.toString() }
        assertEquals(0, intermediate.provider.queryReads)
        assertNull(state.sourceIssue)
        assertFalse(state.stopping)
        assertEquals(latest.file.name, state.selected?.entry?.displayName)
        assertNotNull(state.selected?.verifiedContentToken)
    }

    @Test
    fun hidingInvalidatesBothTheRunningPreviewAndItsPendingReplacement() = runBlocking {
        val old = fixture("hidden-old")
        val next = fixture("hidden-next")
        val gate = blockQuery(old.provider)
        select(old)
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        select(next)

        viewModel.onHidden()
        gate.release.countDown()
        val state = awaitState { !it.working }
        assertNull(state.selected)
        assertEquals(0, next.provider.queryReads)
    }

    @Test
    fun beginningReauthorizationCannotLetTheOldPreviewOverwriteTheNewSource() = runBlocking {
        val old = fixture("reauth-old")
        val replacement = fixture("reauth-original")
        val original = fixtureDescriptor(replacement.authority, replacement.file.length()).source
        val gate = blockQuery(old.provider)
        select(old)
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))

        viewModel.beginReauthorization(original)
        gate.release.countDown()
        val cancelled = awaitState { !it.working }
        assertNull(cancelled.selected)
        assertEquals(original, cancelled.reauthorization)

        select(replacement)
        val state = awaitState { !it.working && it.selected != null }
        val selection = requireNotNull(state.selected)
        assertEquals(original, selection.original)
        assertEquals(original.spec.sourceId, selection.observation.source.sourceId)
        assertEquals(original.spec.sourceId, selection.observation.root.sourceId)
        assertNull(state.sourceIssue)
    }

    @Test
    fun aStableWeakPreviewNeedsAnExplicitPlayAndRetainsItsQueueIdentityOnRefresh() = runBlocking {
        val selectedFile = fixture("explicit-play")
        val played = mutableListOf<QueueEntry>()
        select(selectedFile)
        val preview = requireNotNull(awaitState { !it.working && it.selected != null }.selected)
        assertTrue(requireNotNull(preview.verifiedContentToken).matches(Regex("sha256:[0-9a-f]{64}")))
        assertEquals(ObservedCapability.UNAVAILABLE, preview.observation.access.persistedRead)
        assertTrue(played.isEmpty())

        viewModel.playSelected { played += it }
        val state = awaitState { !it.working && played.size == 1 }
        val current = requireNotNull(state.selected)
        assertEquals(preview.entry?.queueEntryId, played.single().queueEntryId)
        assertEquals(preview.entry?.trackKey, played.single().trackKey)
        assertEquals(preview.verifiedContentToken, current.verifiedContentToken)
        assertNull(state.sourceIssue)
    }

    @Test
    fun aFullLengthMetadataTitleSurvivesPreviewAndExplicitPlayUsesABoundedCommandSummary() = runBlocking {
        val title = "音".repeat(4096)
        ShadowMediaMetadataRetriever.addMetadata(METADATA_SOURCE, MediaMetadataRetriever.METADATA_KEY_TITLE, title)
        val selectedFile = fixture("long-title")
        select(selectedFile)
        val preview = requireNotNull(awaitState { !it.working && it.selected != null }.selected)
        assertEquals(title, preview.metadata?.title)
        assertEquals(title, preview.entry?.displayName)
        var command: LocalQueueCommand? = null

        viewModel.playSelected { current ->
            command = PlaybackCommands.readLocal(PlaybackCommands.localArguments(current, null, "long-title-play"))
        }
        val state = awaitState { !it.working && command != null }
        val transmitted = requireNotNull(command).source.read(0L, 1).entries.single()
        assertEquals(title, state.selected?.metadata?.title)
        assertEquals(title, state.selected?.entry?.displayName)
        assertEquals(title.take(2048), transmitted.displayName)
        assertEquals(preview.entry?.queueEntryId, transmitted.queueEntryId)
        assertEquals(preview.entry?.trackKey, transmitted.trackKey)
        assertEquals(preview.entry?.location?.uri, transmitted.location?.uri)
        assertEquals(preview.verifiedContentToken, state.selected?.verifiedContentToken)
        assertNull(state.sourceIssue)
    }

    @Test
    fun sameNameAndSizeReplacementAfterPreviewCannotBePlayed() = runBlocking {
        val selectedFile = fixture("replaced-after-preview")
        val played = mutableListOf<QueueEntry>()
        select(selectedFile)
        awaitState { !it.working && it.selected != null }
        val previousSize = selectedFile.file.length()
        selectedFile.file.writeBytes(ByteArray(previousSize.toInt()) { 7 })
        assertEquals(previousSize, selectedFile.file.length())

        viewModel.playSelected { played += it }
        val state = awaitState { !it.working && it.sourceIssue != null }
        assertEquals(SourceReadIssue.SOURCE_CHANGED, state.sourceIssue)
        assertNull(state.selected)
        assertTrue(played.isEmpty())
    }

    @Test
    fun sameSizeReplacementDuringMetadataExtractionCannotPublishAPreviewProof() = runBlocking {
        val selectedFile = fixture("replaced-during-metadata")
        var replaced = false
        beforeMetadata = {
            if (!replaced) {
                replaced = true
                selectedFile.file.writeBytes(ByteArray(selectedFile.file.length().toInt()) { 9 })
            }
        }
        select(selectedFile)

        val state = awaitState { !it.working && it.sourceIssue != null }
        assertTrue(replaced)
        assertEquals(SourceReadIssue.SOURCE_CHANGED, state.sourceIssue)
        assertNull(state.selected)
    }

    @Test
    fun aLargeWeakAudioCanBePreviewedWithoutInventingACompleteContentProof() = runBlocking {
        val selectedFile = fixture("large-weak")
        RandomAccessFile(selectedFile.file, "rw").use { it.setLength(LocalInputLimits.READ_PROOF_BYTES + 1L) }
        select(selectedFile)

        val state = awaitState { !it.working && it.selected != null }
        assertNotNull(state.selected?.metadata)
        assertNotNull(state.selected?.entry)
        assertNull(state.selected?.verifiedContentToken)
        assertNull(state.sourceIssue)
    }

    @Test
    fun openOnlyPermissionLossAfterPreviewCannotReachThePlayCallback() = runBlocking {
        val selectedFile = fixture("open-denied")
        val played = mutableListOf<QueueEntry>()
        select(selectedFile)
        awaitState { !it.working && it.selected != null }
        selectedFile.provider.openDenied = true

        viewModel.playSelected { played += it }
        val state = awaitState { !it.working && it.sourceIssue != null }
        assertEquals(SourceReadIssue.PERMISSION_DENIED, state.sourceIssue)
        assertTrue(played.isEmpty())
    }

    @Test
    fun retainingAccessWithoutReturnedGrantFlagsCannotFabricatePersistence() = runBlocking {
        val selectedFile = fixture("missing-grant")
        select(selectedFile)
        val preview = requireNotNull(awaitState { !it.working && it.selected != null }.selected)

        viewModel.persistSelectedAccess()
        val state = awaitState { !it.working && it.selected?.observation?.access?.persistenceAttempted == true }
        val retained = requireNotNull(state.selected)
        assertEquals(ObservedCapability.UNAVAILABLE, retained.observation.access.persistedRead)
        assertTrue(retained.observation.access.persistenceRejected)
        assertEquals(preview.entry?.queueEntryId, retained.entry?.queueEntryId)
        assertEquals(preview.verifiedContentToken, retained.verifiedContentToken)
    }

    private fun select(fixture: AudioFixture) {
        viewModel.selected(LocalSourceKind.DOCUMENT, fixture.uri, 0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun awaitState(predicate: (SourcesUiState) -> Boolean): SourcesUiState = withTimeout(10_000L) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        var state = viewModel.uiState.value
        while (!predicate(state)) {
            delay(1L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            state = viewModel.uiState.value
        }
        state
    }

    private fun fixture(name: String): AudioFixture {
        val directory = File(app.cacheDir, "preview-$name").apply { mkdirs() }
        val file = File(directory, "$name.mp3").apply { writeBytes(ByteArray(64) { 3 }) }
        val authority = "preview.$name"
        return AudioFixture(authority, file, fixtureAudioProvider(authority, file))
    }

    private fun blockQuery(provider: FixtureAudioProvider): QueryGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        releases += release
        provider.beforeQuery = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Fixture query gate timed out" }
        }
        return QueryGate(entered, release)
    }

    private data class AudioFixture(val authority: String, val file: File, val provider: FixtureAudioProvider) {
        val uri: Uri get() = Uri.parse("content://$authority/audio")
    }
    private data class QueryGate(val entered: CountDownLatch, val release: CountDownLatch)

    private companion object {
        const val METADATA_SOURCE = "document-preview-metadata"
    }
}
