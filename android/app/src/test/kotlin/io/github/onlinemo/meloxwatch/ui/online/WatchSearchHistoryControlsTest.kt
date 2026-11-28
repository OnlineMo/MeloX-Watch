package io.github.onlinemo.meloxwatch.ui.online

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.online.OnlineSearchHistoryIssue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Actual online controls in constrained hosts; Main window/insets and physical round-screen acceptance remain separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE,
    qualifiers = "en-rUS-w192dp-h192dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WatchSearchHistoryControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private var host: ActivityController<ComponentActivity>? = null
    private lateinit var fixture: OnlineSearchFixture
    @Before fun open() = runBlocking {
        fixture = OnlineSearchFixture(temporary.root)
        fixture.signIn()
        compose.runOnUiThread { host = Robolectric.buildActivity(ComponentActivity::class.java).also { it.setup() } }
    }
    @After fun close() = runBlocking {
        try { if (::fixture.isInitialized) fixture.close() }
        finally { compose.runOnUiThread { try { host?.close() } finally { host = null } } }
    }

    @Test(timeout = 30_000L) fun roundHistoryUsesTwoVisibleLinesAndA48DpRowBeforeAnExplicitSearch() = runBlocking {
        val query = "Long 永 fixture ".repeat(20).take(256).trim()
        fixture.history.record(fixture.sessions.snapshot.value, query)
        val model = showSearch()
        scrollTo(query)
        val row = compose.onNode(hasText(query) and hasClickAction()).assertIsDisplayed()
        val bounds = row.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp)
        assertTrue(bounds.height >= 48.dp)
        assertTrue(bounds.height <= 96.dp)
        val layouts = ArrayList<TextLayoutResult>()
        compose.onNodeWithText(query, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get -> get(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
        row.performClick()
        assertEquals(query, compose.runOnIdle { model.state.value.searchDraft })
        assertTrue(fixture.calls.isEmpty())
        assertEquals(1, fixture.writeStarts.get())
        val search = fixture.context.getString(R.string.online_search)
        scrollTo(search)
        compose.onNode(hasText(search) and hasClickAction()).performClick()
        awaitOnlineState(model) { !it.busy && it.snapshot != null }
        assertEquals(listOf(query), fixture.calls.toList())
        assertEquals(2, fixture.writeStarts.get())
    }

    @Test(timeout = 30_000L)
    @Config(qualifiers = "en-rUS-w240dp-h280dp-notround-mdpi")
    fun rectangularInputClearAndHistoryClearPerformSeparateLocalActions() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Existing fixture")
        val model = showSearch()
        compose.runOnIdle { model.editSearch("Current draft") }
        val inputClear = fixture.context.getString(R.string.online_search_clear_input)
        scrollTo(inputClear)
        val input = compose.onNodeWithText(inputClear)
        assertTrue(input.getUnclippedBoundsInRoot().height >= 48.dp)
        input.performClick()
        assertEquals("", compose.runOnIdle { model.state.value.searchDraft })
        assertEquals(listOf("Existing fixture"), fixture.history.read(fixture.sessions.snapshot.value).queries)
        val historyClear = fixture.context.getString(R.string.online_search_history_clear)
        scrollTo(historyClear)
        val history = compose.onNodeWithText(historyClear)
        assertTrue(history.getUnclippedBoundsInRoot().height >= 48.dp)
        history.performClick()
        awaitOnlineState(model) { !it.busy && it.searchHistory?.queries?.isEmpty() == true }
        assertFalse(fixture.file.exists())
        val empty = fixture.context.getString(R.string.online_search_history_empty)
        scrollTextTo(empty)
        compose.onNodeWithText(empty).assertIsDisplayed()
        assertTrue(fixture.calls.isEmpty())
    }

    @Test(timeout = 30_000L) fun damagedHistoryHasAnExplicitReachableClearAndNeverDisplaysTheNormalEmptyState() = runBlocking {
        fixture.file.writeBytes(byteArrayOf(1, 2, 3))
        val model = showSearch()
        assertEquals(OnlineSearchHistoryIssue.CORRUPT, model.state.value.searchHistoryIssue)
        val empty = fixture.context.getString(R.string.online_search_history_empty)
        assertThrows(AssertionError::class.java) { scrollTextTo(empty) }
        compose.onNodeWithText(empty).assertDoesNotExist()
        val clear = fixture.context.getString(R.string.online_search_history_clear_damaged)
        scrollTo(clear)
        val row = compose.onNodeWithText(clear).assertIsDisplayed()
        val bounds = row.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp)
        assertTrue(bounds.height >= 48.dp)
        row.performClick()
        awaitOnlineState(model) { !it.busy && it.searchHistory != null && it.searchHistoryIssue == null }
        assertFalse(fixture.file.exists())
        scrollTextTo(empty)
        compose.onNodeWithText(empty).assertIsDisplayed()
        assertTrue(fixture.calls.isEmpty())
    }

    private suspend fun showSearch(): OnlineViewModel {
        val model = fixture.visibleModel()
        model.showSearch()
        awaitOnlineState(model) { !it.busy && it.searchHistory != null }
        compose.runOnUiThread {
            checkNotNull(host).get().setContent {
                val state by model.state.collectAsState()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val round = fixture.context.resources.configuration.isScreenRound
                    val contentWidth = if (round) maxWidth * .78f else maxWidth - 24.dp
                    LazyColumn(Modifier.width(contentWidth).align(Alignment.Center),
                        contentPadding = PaddingValues(vertical = 44.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        onlineContent(state, model, visible = true)
                    }
                }
            }
        }
        compose.waitForIdle()
        return model
    }
    private fun scrollTo(label: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(label) and hasClickAction())
    private fun scrollTextTo(label: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
}
