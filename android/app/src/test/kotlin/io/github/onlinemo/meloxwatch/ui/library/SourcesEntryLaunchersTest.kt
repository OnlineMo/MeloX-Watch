package io.github.onlinemo.meloxwatch.ui.library

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.data.local.FixtureAudioProvider
import io.github.onlinemo.meloxwatch.data.local.fixtureAudioProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** Real registration/dispatch with production Sources reads; Main/Window and native audio remain device gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = MeloXApplication::class,
    qualifiers = "en-rUS-w192dp-h192dp-round-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SourcesEntryLaunchersTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: MeloXApplication
    private var store = ViewModelStore()
    private var saved = SavedStateHandle()
    private lateinit var model: SourcesViewModel
    private lateinit var library: LibraryViewModel
    private var controller: ActivityController<ComponentActivity>? = null
    private var currentActivity: ComponentActivity? = null
    private val launchers = AtomicReference<SourcesEntryLaunchers?>()
    private val activity: ComponentActivity get() = checkNotNull(controller).get()

    @Before
    fun createHostAndModels(): Unit {
        app = RuntimeEnvironment.getApplication() as MeloXApplication
        compose.waitUntil(10_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            app.backups.startup.value !is BackupStartupState.Loading
        }
        assertTrue(app.backups.startup.value is BackupStartupState.Ready)
        DataSource.setFileDescriptorTransform { _, _ -> "sources-launcher-metadata" }
        ShadowMediaMetadataRetriever.addMetadata("sources-launcher-metadata", MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata("sources-launcher-metadata", MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "no")
        ShadowMediaMetadataRetriever.addMetadata("sources-launcher-metadata", MediaMetadataRetriever.METADATA_KEY_DURATION, "60000")
        ShadowMediaMetadataRetriever.addMetadata("sources-launcher-metadata", MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "audio/mpeg")
        compose.runOnUiThread {
            createModels()
            mountHost(null)
        }
        awaitReady()
    }

    @After
    fun releaseHostAndPhysicalReads(): Unit = runBlocking {
        compose.runOnUiThread {
            controller?.close()
            controller = null
            currentActivity = null
            store.clear()
        }
        withTimeout(10_000L) { app.localProviderIo.awaitIdle() }
        ShadowMediaMetadataRetriever.reset()
    }

    @Test(timeout = 30_000L)
    fun theRegisteredFileReturnBeforeStartWaitsForForegroundAndRepeatedClicksDoNotLaunchTwice(): Unit {
        installSourcesPicker(app, SourcesPickerKind.FILE)
        val file = audio("registry-stopped")
        compose.runOnIdle {
            checkNotNull(launchers.get()).chooseFile()
            checkNotNull(launchers.get()).chooseFile()
        }
        val started = requireNotNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        compose.runOnUiThread {
            checkNotNull(controller).pause().stop()
            assertTrue(activity.activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_OK, Intent().setData(file.uri)))
        }
        assertTrue(model.uiState.value.awaitingPickerForeground)
        assertEquals(0, file.provider.queryReads)
        assertNull(model.uiState.value.selected)
        compose.runOnUiThread { checkNotNull(controller).start().resume().visible() }
        awaitPreview(file)
        assertFalse(model.uiState.value.awaitingPickerForeground)
        assertNotNull(model.uiState.value.selected?.verifiedContentToken)
    }

    @Test(timeout = 30_000L)
    fun registryRecreationRetainsLiveMemoryButAFreshSourcesOwnerOnlyDrainsSavedPlatformState(): Unit {
        installSourcesPicker(app, SourcesPickerKind.FILE)
        val retained = audio("registry-retained")
        val first = launchFile()
        recreateHost(freshSourcesOwner = false)
        dispatch(first, retained)
        awaitPreview(retained)
        assertTrue(retained.provider.queryReads > 0)

        val expired = audio("registry-fresh-owner")
        val second = launchFile()
        recreateHost(freshSourcesOwner = true)
        assertTrue(model.uiState.value.drainingPicker)
        dispatch(second, expired)
        compose.waitForIdle()
        assertEquals(0, expired.provider.queryReads)
        assertNull(model.uiState.value.selected)
        assertFalse(model.uiState.value.awaitingPicker)
        assertTrue(saved.keys().isEmpty())
        assertEquals(0L, runBlocking { app.library.overview().sourceCount })
        assertNull(app.scans.state.value)

        val fresh = audio("registry-after-drain")
        val third = launchFile()
        dispatch(third, fresh)
        awaitPreview(fresh)
        assertTrue(fresh.provider.queryReads > 0)
    }

    private fun createModels() {
        model = ViewModelProvider(store, viewModelFactory {
            initializer { SourcesViewModel(app, saved) }
        })[SourcesViewModel::class.java]
        library = ViewModelProvider(store, viewModelFactory {
            initializer { LibraryViewModel(app, SavedStateHandle()) }
        })[LibraryViewModel::class.java]
        library.show(LibraryScreen.SOURCES)
    }

    private fun mountHost(state: Bundle?) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java)
        controller = host
        host.create(state).start().resume().visible()
        val exact = host.get()
        currentActivity = exact
        val sources = model
        val navigationModel = library
        exact.setContent {
            val navigation by navigationModel.navigation.collectAsStateWithLifecycle()
            val entries = rememberSourcesEntryLaunchers(sources, exact,
                synchronizeRoute = { sources.notePageSelected(true, navigationModel.navigation.value) },
                isCurrent = { currentActivity === exact })
            SideEffect { launchers.set(entries) }
            LifecycleStartEffect(navigation) {
                if (currentActivity === exact) sources.onVisible(navigation)
                onStopOrDispose { if (currentActivity === exact) sources.onHidden() }
            }
        }
    }

    private fun recreateHost(freshSourcesOwner: Boolean) {
        compose.runOnUiThread {
            val state = Bundle()
            val old = checkNotNull(controller)
            old.pause().stop().saveInstanceState(state)
            currentActivity = null
            launchers.set(null)
            old.destroy()
            if (freshSourcesOwner) {
                val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                store.clear()
                store = ViewModelStore()
                saved = restored
                createModels()
            }
            mountHost(state)
        }
        awaitReady()
    }

    private fun launchFile(): ShadowActivity.IntentForResult {
        compose.runOnIdle { checkNotNull(launchers.get()).chooseFile() }
        return requireNotNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
    }

    private fun dispatch(started: ShadowActivity.IntentForResult, file: Audio) {
        compose.runOnUiThread {
            assertTrue(activity.activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_OK, Intent().setData(file.uri)))
        }
    }

    private fun awaitReady() {
        compose.waitUntil(5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            launchers.get() != null && !model.uiState.value.working && model.uiState.value.rules != null
        }
    }

    private fun awaitPreview(file: Audio) {
        compose.waitUntil(5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !model.uiState.value.working && model.uiState.value.selected?.observation?.source?.uri == file.uri.toString()
        }
    }

    private fun audio(name: String): Audio {
        val file = File(app.cacheDir, "$name.mp3").apply { writeBytes(ByteArray(64) { 3 }) }
        return Audio(android.net.Uri.parse("content://sources.$name/audio"), fixtureAudioProvider("sources.$name", file))
    }

    private data class Audio(val uri: android.net.Uri, val provider: FixtureAudioProvider)
}
