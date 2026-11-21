package io.github.onlinemo.meloxwatch.ui.meting

import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.data.meting.ControlledMetingStore
import io.github.onlinemo.meloxwatch.data.meting.MetingCredentialEdit
import io.github.onlinemo.meloxwatch.data.meting.MetingIssue
import io.github.onlinemo.meloxwatch.data.meting.MetingPlaybackTestState
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.meting.metingConfiguration
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.playback.resources.FixturePlaybackClock
import io.github.onlinemo.meloxwatch.playback.resources.MediaHttpTarget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResourceStatus
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.playback.resources.ResourceMatch
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import io.github.onlinemo.meloxwatch.playback.resources.onlineEntry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** UI 状态回归使用控制器投影；不运行播放器、解码或首声测量。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetingSettingsViewModelTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test(timeout = 5_000) fun openingSettingsHasNoDefaultIdOrHttpRequestAndHideDiscardsSecretEditing() = runBlocking {
        fixture(this).use { fixture ->
            fixture.viewModel.setVisible(true)
            settled(fixture.viewModel)
            assertEquals("", fixture.viewModel.state.value.testTrackId)
            assertEquals(0, fixture.httpCreations.get())
            fixture.viewModel.beginEdit()
            settled(fixture.viewModel)
            assertEquals("", checkNotNull(fixture.viewModel.state.value.draft).endpoint)
            fixture.viewModel.changeDraft(checkNotNull(fixture.viewModel.state.value.draft).changed(endpoint = "https://service.invalid/api?token=fixture"))
            assertTrue(checkNotNull(fixture.viewModel.state.value.draft).endpoint.contains("token=fixture"))
            fixture.viewModel.setVisible(false)
            assertNull(fixture.viewModel.state.value.draft)
            assertFalse(fixture.viewModel.state.value.toString().contains("token"))
            assertEquals(0, fixture.httpCreations.get())
        }
    }

    @Test(timeout = 5_000) fun hideAndRapidReshowKeepTheAcceptedSaveSlotUntilTheActualEncryptedCommitEnds() = runBlocking {
        fixture(this).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.viewModel.setVisible(true)
            settled(fixture.viewModel)
            fixture.viewModel.beginEdit()
            settled(fixture.viewModel)
            fixture.viewModel.changeDraft(checkNotNull(fixture.viewModel.state.value.draft).changed(
                endpoint = "https://replacement.invalid/service", credentialEdit = MetingCredentialEdit.CLEAR))
            fixture.store.beforeWrite = { entered.complete(Unit); release.await() }
            fixture.viewModel.save()
            try {
                entered.await()
                fixture.viewModel.setVisible(false)
                fixture.viewModel.setVisible(true)
                fixture.viewModel.beginEdit()
                assertTrue(fixture.viewModel.state.value.working)
                assertEquals(0, fixture.store.writes)
                assertNull(fixture.viewModel.state.value.draft)
                release.complete(Unit)
                settled(fixture.viewModel)
                assertEquals(1, fixture.store.writes)
                assertEquals("replacement.invalid", fixture.viewModel.state.value.saved.hostLabel)
                assertNull(fixture.viewModel.state.value.draft)
                assertEquals(0, fixture.httpCreations.get())
            } finally { release.complete(Unit) }
        }
    }

    @Test(timeout = 5_000) fun externalConfigurationChangeDiscardsTheOldDraftAndItsDeletionConfirmation() = runBlocking {
        fixture(this).use { fixture ->
            fixture.viewModel.setVisible(true)
            settled(fixture.viewModel)
            fixture.viewModel.beginEdit()
            settled(fixture.viewModel)
            fixture.viewModel.confirmDeletion(true)
            fixture.owner.configuration.setEnabled(fixture.owner.configuration.snapshot.value.generation, false)
            repeat(10) { yield() }
            assertNull(fixture.viewModel.state.value.draft)
            assertFalse(fixture.viewModel.state.value.deleteConfirmation)
            assertEquals(MetingIssue.CONFIGURATION_CHANGED, fixture.viewModel.state.value.issue)
            fixture.viewModel.save()
            assertEquals(1, fixture.store.writes)
        }
    }

    @Test(timeout = 5_000) fun aPreviouslyPendingOrHealthyOperationCannotClaimNewPlaybackVerification() = runBlocking {
        val resources = RuntimePlaybackResources()
        val oldBudget = PlaybackResolutionBudget(FixturePlaybackClock())
        val observation = MutableStateFlow(MetingPlaybackObservation(true, true, onlineEntry().queueEntryId, true, Player.STATE_READY))
        fixture(this, resources, observation).use { fixture ->
            fixture.viewModel.setVisible(true)
            settled(fixture.viewModel)
            val use = checkNotNull(fixture.owner.configuration.capture(true))
            val held = mutableListOf<RuntimePlaybackResource>()
            fun openProjection(id: String, budget: PlaybackResolutionBudget): RuntimePlaybackResource {
                resources.openedResource.value?.let { previous -> held.forEach { item ->
                    if (item.entry.queueEntryId == previous.queueEntryId) item.online?.opened?.set(false)
                } }
                val entry = onlineEntry(id)
                val resource = resources.register(entry, MediaHttpTarget.serviceReference("/audio", use.configuration.endpoint.url, use.context),
                    budget, null, PlaybackResourceStatus("meting-custom", ResourceMatch.SERVICE_UNVERIFIED, ResourceCompleteness.UNKNOWN,
                        StreamingQuality.STANDARD, null, null, use.generation), { true }, use::isCurrent)
                held.add(resource)
                val state = checkNotNull(resource.online)
                state.opened.set(true)
                resources.notifyOpened(state)
                return resource
            }
            try {
                fixture.viewModel.setTrackId("201")
                fixture.request = { openProjection("201", oldBudget); true }
                fixture.viewModel.playCurrentTrack()
                settled(fixture.viewModel)
                assertEquals(MetingPlaybackTestState.EXISTING_STREAM, fixture.viewModel.state.value.report?.playback)
                openProjection("201", PlaybackResolutionBudget(FixturePlaybackClock()))
                repeat(10) { yield() }
                assertEquals(MetingPlaybackTestState.PLAYING, fixture.viewModel.state.value.report?.playback)
                openProjection("202", PlaybackResolutionBudget(FixturePlaybackClock()))
                repeat(10) { yield() }
                assertEquals(MetingPlaybackTestState.OTHER_SOURCE, fixture.viewModel.state.value.report?.playback)
                fixture.owner.configuration.setEnabled(use.generation, false)
                repeat(10) { yield() }
                assertNull(fixture.viewModel.state.value.report)
                assertEquals(0, fixture.httpCreations.get())
            } finally { held.forEach(RuntimePlaybackResource::close) }
        }
    }

    private class Fixture(val store: ControlledMetingStore, val owner: MetingRuntimeOwner,
        val httpCreations: AtomicInteger, val http: BoundedHttpGetClient) : AutoCloseable {
        lateinit var viewModel: MetingSettingsViewModel
        var request: suspend () -> Boolean = { true }
        override fun close() { viewModel.setVisible(false); owner.close(); http.close() }
    }
    private fun fixture(scope: CoroutineScope, resources: RuntimePlaybackResources = RuntimePlaybackResources(),
        playback: MutableStateFlow<MetingPlaybackObservation> = MutableStateFlow(MetingPlaybackObservation())): Fixture {
        val store = ControlledMetingStore(metingConfiguration(enabled = true))
        val creations = AtomicInteger()
        val http = BoundedHttpGetClient(RuntimeEnvironment.getApplication())
        val owner = MetingRuntimeOwner(RuntimeEnvironment.getApplication(), resources,
            { creations.incrementAndGet(); http }, { store }, scope)
        val fixture = Fixture(store, owner, creations, http)
        fixture.viewModel = MetingSettingsViewModel(owner, resources,
            MetingSettingsEnvironment(playback, { onlineEntry() }, { fixture.request() }), scope)
        return fixture
    }
    private suspend fun settled(viewModel: MetingSettingsViewModel) = withTimeout(2_000L) {
        while (viewModel.state.value.working || !viewModel.state.value.saved.loaded) yield()
        repeat(10) { yield() }
    }
}
