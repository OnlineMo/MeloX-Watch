package io.github.onlinemo.meloxwatch.ui.backup

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.onlinemo.meloxwatch.data.backup.BackupHeader
import io.github.onlinemo.meloxwatch.data.backup.BackupMergeMode
import io.github.onlinemo.meloxwatch.data.backup.BackupRestorePreview
import io.github.onlinemo.meloxwatch.data.backup.BackupScope
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.data.backup.BackupRelinkMatch
import io.github.onlinemo.meloxwatch.data.backup.BackupRelinkPreview
import io.github.onlinemo.meloxwatch.data.cue.CueAdoptionDifferences
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.ScreenShape
import org.junit.After
import org.junit.Assert.assertEquals
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

/** 真实 Compose 控件定义；不能把虚拟语义/尺寸检查换成真表视觉或功能验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w192dp-h192dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchBackupSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var controller: ActivityController<ComponentActivity>? = null

    @Before
    fun createHost() {
        compose.runOnUiThread {
            val host = Robolectric.buildActivity(ComponentActivity::class.java)
            controller = host
            host.setup()
        }
    }

    @After
    fun retireHost() {
        compose.runOnUiThread {
            try { controller?.close() }
            finally { controller = null }
        }
    }

    @Test
    fun fontConsentIsSeparateFromRestoreEffectsAndUsesAReachableFullRow() {
        val scopes = setOf(BackupScope.PREFERENCES, BackupScope.RESOURCES)
        val preview = BackupRestorePreview("generated-preview", "a".repeat(64),
            BackupHeader(scopes = scopes, createdEpochMs = 0L), BackupMergeMode.REPLACE_SELECTED, scopes,
            emptyList(), needsFontLicenseAcknowledgment = true)
        var state by mutableStateOf(BackupUiState(page = BackupUiPage.RESTORE,
            startup = BackupStartupState.Ready(false), scopes = scopes, mode = preview.mode,
            restorePreview = preview))
        var confirmed = 0
        val actions = actions(effects = { state = state.copy(effectsAcknowledged = it) },
            fontLicense = { state = state.copy(fontLicenseAcknowledged = it) },
            confirmRestore = { confirmed++ })
        compose.runOnUiThread {
            checkNotNull(controller).get().setContent { WatchBackupSettings(state, ScreenShape.ROUND, true, actions, onBack = {}) }
        }
        scrollTo("Confirm restoration")
        val confirm = compose.onNodeWithText("Confirm restoration").assertIsNotEnabled()
        val effects = "Restore these scopes using the selected merge or replace mode."
        scrollTo(effects)
        compose.onNodeWithText(effects).performClick()
        scrollTo("Confirm restoration")
        confirm.assertIsNotEnabled()
        scrollTo("I have permission to use the fonts in this restoration.")
        val license = compose.onNodeWithText("I have permission to use the fonts in this restoration.")
        license.performClick()
        scrollTo("Confirm restoration")
        confirm.assertIsEnabled()
        val bounds = confirm.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp)
        assertTrue(bounds.height >= 48.dp)
        confirm.performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun hiddenPageRetainsNoTextOrClickableBackupControls() {
        var visible by mutableStateOf(true)
        val state = BackupUiState(startup = BackupStartupState.Ready(false))
        compose.runOnUiThread {
            checkNotNull(controller).get().setContent { WatchBackupSettings(state, ScreenShape.RECTANGULAR, visible, actions(), onBack = {}) }
        }
        scrollTo("Export backup")
        compose.onNodeWithText("Export backup").assertIsEnabled()
        compose.runOnUiThread { visible = false }
        compose.onNodeWithText("Export backup").assertDoesNotExist()
        compose.onNodeWithText("Restore backup").assertDoesNotExist()
    }

    @Test
    fun cueIdentityAndContentRequireSeparateReachableConfirmationControls() {
        val preview = BackupRelinkPreview("cue-preview", "old-cue-track", TrackKey.newLocal(), "Old", "New",
            BackupRelinkMatch.DIFFERENT_CONTENT, CueAdoptionDifferences(true, true, 0, 1, 1, 0), 2, 2, 10_003L)
        var state by mutableStateOf(BackupUiState(page = BackupUiPage.RELINK,
            startup = BackupStartupState.Ready(false), relinkPreview = preview))
        val actions = actions(allowDifferent = { state = state.copy(allowDifferentContent = it) },
            cueDifferences = { state = state.copy(allowCueDifferences = it) })
        compose.runOnUiThread {
            checkNotNull(controller).get().setContent { WatchBackupSettings(state, ScreenShape.ROUND, true, actions, onBack = {}) }
        }
        val cueConsent = "Confirm the displayed source, identity and whole-group boundary changes"
        scrollTo(cueConsent)
        compose.onNodeWithText(cueConsent).performClick()
        scrollTo("Confirm file association")
        compose.onNodeWithText("Confirm file association").assertIsNotEnabled()
        compose.runOnUiThread { state = state.copy(allowDifferentContent = true, allowCueDifferences = false) }
        compose.onNodeWithText("Confirm file association").assertIsNotEnabled()
        scrollTo(cueConsent)
        compose.onNodeWithText(cueConsent).performClick()
        scrollTo("Confirm file association")
        compose.onNodeWithText("Confirm file association").assertIsEnabled()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun actions(effects: (Boolean) -> Unit = {}, fontLicense: (Boolean) -> Unit = {},
        confirmRestore: () -> Unit = {}, allowDifferent: (Boolean) -> Unit = {}, cueDifferences: (Boolean) -> Unit = {}): BackupUiActions = BackupUiActions(
        home = {}, beginExport = {}, beginRestore = {}, scope = { _, _ -> }, mode = {}, privacy = {}, effects = effects,
        fontLicense = fontLicense, chooseDestination = {}, chooseInput = {}, recheckRestore = {}, confirmRestore = confirmRestore,
        cancel = {}, retryOutputCleanup = {}, showMissing = {}, firstMissingPage = {}, nextMissingPage = {}, selectMissing = {},
        chooseExistingTarget = {}, allowDifferent = allowDifferent, allowCueDifferences = cueDifferences,
        confirmRelink = {}, openSources = {}, openAppearance = {}, retryStorage = {},
    )
}
