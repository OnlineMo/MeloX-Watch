package io.github.onlinemo.meloxwatch.ui.dsp

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.playback.dsp.DspActualState
import io.github.onlinemo.meloxwatch.playback.dsp.DspLimits
import io.github.onlinemo.meloxwatch.playback.dsp.DspPreset
import io.github.onlinemo.meloxwatch.playback.dsp.DspRuntime
import io.github.onlinemo.meloxwatch.playback.dsp.DspSaveResult
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettings
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsStore
import io.github.onlinemo.meloxwatch.playback.dsp.DspSnapshot
import io.github.onlinemo.meloxwatch.playback.dsp.DspStored
import io.github.onlinemo.meloxwatch.ui.DspVisibleFontText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Clipped Compose viewport proof; circular corners and a real IME need separate device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w192dp-h192dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchDspFontTextPlacementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private val corpus = DspVisibleFontText { visible }

    @Before fun createHost() {
        compose.runOnUiThread {
            val host = Robolectric.buildActivity(ComponentActivity::class.java)
            controller = host
            host.setup()
        }
    }

    @After fun retireHost() {
        compose.runOnUiThread {
            try { controller?.close() }
            finally { controller = null; corpus.close() }
        }
    }

    @Test fun clippedPresetRowsEnterAndLeaveTheCorpusAsTheViewportScrolls() {
        showSettings()
        openPresets()
        scrollTo("Curve 0 音")
        compose.runOnIdle {
            assertTrue("Curve 0 音" in corpus.snapshot.parts)
            assertFalse("Curve 31 音" in corpus.snapshot.parts)
            assertNull(corpus.snapshot.issue)
        }
        scrollTo("Curve 31 音")
        compose.runOnIdle {
            assertTrue("Curve 31 音" in corpus.snapshot.parts)
            assertFalse("Curve 0 音" in corpus.snapshot.parts)
            assertTrue(corpus.snapshot.parts.size < DspLimits.MAX_PRESETS)
            assertNull(corpus.snapshot.issue)
        }
        scrollTo("Curve 0 音")
        compose.runOnIdle {
            assertTrue("Curve 0 音" in corpus.snapshot.parts)
            assertFalse("Curve 31 音" in corpus.snapshot.parts)
        }
    }

    @Test fun actualNameInputEditsAndSectionRetirementClearOnlyCurrentVisibleText() {
        showSettings()
        openPresets()
        val input = compose.onNodeWithContentDescription("Preset name")
        input.performScrollTo().performTextInput("Draft 音")
        compose.runOnIdle { assertTrue("Draft 音" in corpus.snapshot.parts) }
        input.performTextReplacement("Edited 永")
        compose.runOnIdle {
            assertTrue("Edited 永" in corpus.snapshot.parts)
            assertFalse("Draft 音" in corpus.snapshot.parts)
        }
        scrollTo("All sound settings")
        compose.onNodeWithText("All sound settings").performClick()
        compose.runOnIdle { assertTrue(corpus.snapshot.parts.isEmpty()) }
        scrollTo("EQ presets")
        compose.onNodeWithText("EQ presets").performClick()
        scrollTo("Curve 0 音")
        compose.runOnIdle {
            assertTrue("Curve 0 音" in corpus.snapshot.parts)
            assertFalse("Edited 永" in corpus.snapshot.parts)
        }
        compose.runOnUiThread { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(corpus.snapshot.parts.isEmpty()) }
    }

    private fun showSettings() {
        val saved = DspStored(presets = List(DspLimits.MAX_PRESETS) {
            DspPreset("curve-$it", "Curve $it 音", List(10) { 0 })
        })
        val snapshot = DspSnapshot(saved)
        val state = DspEditorState(saved = snapshot, draft = saved.settings, presets = saved.presets,
            baseRevision = saved.revision, visible = true, requiresRead = false)
        val store = object : DspSettingsStore {
            override val state = MutableStateFlow(snapshot)
            override suspend fun read(): DspSnapshot = error("Viewport and input tests must not read storage")
            override suspend fun compareAndSet(expectedRevision: Long, settings: DspSettings,
                presets: List<DspPreset>): DspSaveResult = error("Input edits must not save storage")
            override suspend fun recoverDefaults(expectedRecoveryToken: String): DspSaveResult =
                error("Viewport tests must not recover storage")
        }
        val model = DspViewModel(store, DspRuntime(), BackupMutationBarrier())
        compose.runOnUiThread {
            checkNotNull(controller).get().setContent {
                Column(Modifier.fillMaxSize().clipToBounds().verticalScroll(rememberScrollState())) {
                    WatchDspSettings(state, DspActualState(), model, visible, corpus, onBack = {})
                }
            }
        }
    }

    private fun openPresets() {
        scrollTo("EQ presets")
        compose.onNodeWithText("EQ presets").performClick()
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithText(text).performScrollTo()
        compose.waitForIdle()
    }
}
