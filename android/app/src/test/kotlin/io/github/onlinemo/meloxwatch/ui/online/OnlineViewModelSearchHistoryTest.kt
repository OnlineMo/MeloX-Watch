package io.github.onlinemo.meloxwatch.ui.online

import android.app.Application
import android.content.res.Configuration
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.online.OnlineSearchHistoryIssue
import io.github.onlinemo.meloxwatch.data.online.OnlineSearchHistoryScope
import io.github.onlinemo.meloxwatch.ui.ProbePage
import io.github.onlinemo.meloxwatch.ui.ProbeUiState
import io.github.onlinemo.meloxwatch.ui.fonts.ThemeUiFontText
import io.github.onlinemo.meloxwatch.ui.fonts.themeUiFontCoverage
import io.github.onlinemo.meloxwatch.ui.library.LibraryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistUiState
import io.github.onlinemo.meloxwatch.ui.probePageVisibleText
import io.github.onlinemo.meloxwatch.ui.queue.QueueViewerState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production page ownership and native storage definitions; no real account or device acceptance is asserted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OnlineViewModelSearchHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: OnlineSearchFixture
    @Before fun open() = runBlocking {
        fixture = OnlineSearchFixture(temporary.root)
        fixture.signIn()
    }
    @After fun close() = runBlocking { if (::fixture.isInitialized) fixture.close() }

    @Test(timeout = 30_000L) fun selectionAndInputClearNeverRequestHttpAndOnlyAnExplicitSearchRecordsHistory() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Existing fixture")
        val model = fixture.visibleModel()
        model.showSearch()
        val search = awaitOnlineState(model) { !it.busy && it.searchHistory != null }
        val before = fixture.file.readBytes()
        model.chooseSearchHistory(checkNotNull(search.searchHistory), 0)
        assertEquals("Existing fixture", model.state.value.searchDraft)
        assertTrue(fixture.calls.isEmpty())
        assertArrayEquals(before, fixture.file.readBytes())
        model.clearSearchDraft()
        assertEquals("", model.state.value.searchDraft)
        assertEquals(1, fixture.writeStarts.get())
        model.editSearch("  New fixture  ")
        model.search()
        val loaded = awaitOnlineState(model) { !it.busy && it.snapshot != null }
        assertEquals(1L, loaded.snapshot?.count)
        assertEquals("201", loaded.rows.single().id)
        assertEquals(listOf("New fixture"), fixture.calls.toList())
        assertEquals(listOf("New fixture", "Existing fixture"), fixture.history.read(fixture.sessions.snapshot.value).queries)
        assertEquals(2, fixture.writeStarts.get())
        model.refresh()
        awaitOnlineState(model) { !it.busy && it.snapshot != null }
        assertEquals(2, fixture.writeStarts.get())
        assertEquals(2, fixture.calls.size)
        assertTrue(model.back())
        awaitOnlineState(model) { !it.busy && it.screen == OnlineScreen.SEARCH && it.searchHistory != null }
        assertEquals(2, fixture.calls.size)
        assertEquals(2, fixture.writeStarts.get())
    }

    @Test(timeout = 30_000L) fun identicalWordsInANewAccountRejectTheOldHistorySelectionAndClearCallbacks() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Shared fixture")
        val model = fixture.visibleModel()
        model.showSearch()
        val old = checkNotNull(awaitOnlineState(model) { !it.busy && it.searchHistory != null }.searchHistory)
        fixture.signIn("102")
        val newAccount = fixture.sessions.snapshot.value
        val newScope = OnlineSearchHistoryScope.of(newAccount)
        awaitOnlineState(model) {
            !it.busy && it.account.account?.accountId == "102" && it.account.generation == newAccount.generation &&
                it.searchHistory != null && it.searchHistory?.scope?.matches(newScope) == true
        }
        // Pump the paused Main looper until the new owner's native history IO has retired.
        val retiredRead = async { fixture.history.read(newAccount) }
        awaitOnlineCondition { retiredRead.isCompleted }
        retiredRead.await()
        fixture.history.record(newAccount, "Shared fixture")
        model.showSearch()
        val current = checkNotNull(awaitOnlineState(model) { !it.busy && it.searchHistory?.queries?.isNotEmpty() == true }.searchHistory)
        assertNotSame(old, current)
        model.editSearch("Current draft")
        val before = fixture.file.readBytes()
        model.chooseSearchHistory(old, 0)
        model.clearSearchHistory(old)
        assertEquals("Current draft", model.state.value.searchDraft)
        assertArrayEquals(before, fixture.file.readBytes())
        assertTrue(fixture.calls.isEmpty())
        model.chooseSearchHistory(current, 0)
        assertEquals("Shared fixture", model.state.value.searchDraft)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test(timeout = 30_000L) fun aHistoryWriteFailurePreservesItsFileAndStillLoadsRealRoomSearchResults() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Original fixture")
        val before = fixture.file.readBytes()
        val model = fixture.visibleModel()
        model.showSearch()
        awaitOnlineState(model) { !it.busy && it.searchHistory != null }
        fixture.failNextWrite.set(true)
        model.editSearch("New fixture")
        model.search()
        val loaded = awaitOnlineState(model) { !it.busy && it.snapshot != null }
        assertNull(loaded.issue)
        assertEquals(OnlineSearchHistoryIssue.WRITE_FAILED, loaded.searchHistoryIssue)
        assertEquals("201", loaded.rows.single().id)
        assertEquals(listOf("New fixture"), fixture.calls.toList())
        assertArrayEquals(before, fixture.file.readBytes())
        assertEquals(listOf("Original fixture"), fixture.history.read(fixture.sessions.snapshot.value).queries)
    }

    @Test(timeout = 30_000L) fun leavingAndReturningDuringAnAcceptedWriteKeepsTheOldSlotUntilActualRetirement() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Original fixture")
        val model = fixture.visibleModel()
        model.showSearch()
        awaitOnlineState(model) { !it.busy && it.searchHistory != null }
        val gate = fixture.blockNextWrite()
        try {
            model.editSearch("Accepted fixture")
            model.search()
            awaitOnlineCondition { gate.entered.count == 0L }
            model.setVisible(false)
            assertNull(model.state.value.searchHistory)
            model.setVisible(true)
            model.showSearch()
            assertTrue(model.state.value.busy)
            assertTrue(fixture.calls.isEmpty())
            gate.release.countDown()
            val returned = awaitOnlineState(model) { !it.busy && it.screen == OnlineScreen.SEARCH && it.searchHistory != null }
            assertEquals(listOf("Accepted fixture", "Original fixture"), returned.searchHistory?.queries)
            assertNull(returned.searchHistoryIssue)
            assertNull(returned.snapshot)
            assertTrue(returned.rows.isEmpty())
            assertTrue(fixture.calls.isEmpty())
            assertEquals(2, fixture.writeStarts.get())
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L) fun aLateNativeReplyCannotRepaintTheSearchPageOrRepeatTheSelectedHistoryTerm() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Original fixture")
        val model = fixture.visibleModel()
        model.showSearch()
        awaitOnlineState(model) { !it.busy && it.searchHistory != null }
        val gate = fixture.blockNextReply()
        try {
            model.editSearch("Delayed fixture")
            model.search()
            awaitOnlineCondition { gate.entered.isCompleted }
            assertTrue(model.back())
            assertTrue(model.state.value.busy)
            gate.release.complete(Unit)
            val search = awaitOnlineState(model) { !it.busy && it.screen == OnlineScreen.SEARCH && it.searchHistory != null }
            assertTrue(gate.exited.isCompleted)
            assertNull(search.snapshot)
            assertTrue(search.rows.isEmpty())
            assertEquals(0L, fixture.database.catalog().viewCount())
            model.chooseSearchHistory(checkNotNull(search.searchHistory), 1)
            assertEquals("Original fixture", model.state.value.searchDraft)
            assertEquals(listOf("Delayed fixture"), fixture.calls.toList())
        } finally { gate.release.complete(Unit) }
    }

    @Test(timeout = 30_000L) fun damagedHistoryIsShownAsAnErrorUntilItsExplicitClearCompletes() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        fixture.file.writeBytes(bytes)
        val model = fixture.visibleModel()
        model.showSearch()
        val damaged = awaitOnlineState(model) { !it.busy && it.searchHistory?.issue == OnlineSearchHistoryIssue.CORRUPT }
        assertEquals(OnlineSearchHistoryIssue.CORRUPT, damaged.searchHistoryIssue)
        assertArrayEquals(bytes, fixture.file.readBytes())
        model.clearSearchHistory(checkNotNull(damaged.searchHistory))
        val cleared = awaitOnlineState(model) { !it.busy && it.searchHistory != null && it.searchHistoryIssue == null }
        assertTrue(cleared.searchHistory?.queries?.isEmpty() == true)
        assertFalse(fixture.file.exists())
        assertTrue(fixture.calls.isEmpty())
    }

    @Test(timeout = 30_000L) fun logoutReportsAnOwnedCleanupFailureAndItsCapturedRetryPreservesLocalFiles() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Original fixture")
        val local = temporary.newFile("local-library").apply { writeText("preserve local music") }
        val pending = File(fixture.file.path + ".new").apply { check(mkdir()) }
        val obstacle = File(pending, "blocked").apply { writeText("synthetic obstruction") }
        val model = fixture.visibleModel()
        model.showLogoutConfirmation()
        model.confirmLogout()
        val signedOut = awaitOnlineState(model) { !it.busy && it.account.account == null && it.searchHistoryCleanup != null }
        val failure = checkNotNull(signedOut.searchHistoryCleanup)
        assertTrue(failure.issue in setOf(OnlineSearchHistoryIssue.CLEAR_FAILED, OnlineSearchHistoryIssue.READ_FAILED))
        assertEquals("preserve local music", local.readText())
        assertTrue(obstacle.delete())
        assertTrue(pending.delete())
        model.retrySearchHistoryCleanup(failure)
        awaitOnlineState(model) { !it.busy && it.searchHistoryCleanup == null }
        assertFalse(fixture.file.exists())
        assertEquals("preserve local music", local.readText())
        assertTrue(fixture.calls.isEmpty())
    }

    @Test(timeout = 30_000L) fun ordinaryPartialClearReopensWithItsOwnedRetryAndCompletesAfterTheObstructionIsRemoved() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Original fixture")
        val model = fixture.visibleModel()
        model.showSearch()
        val original = checkNotNull(awaitOnlineState(model) { !it.busy && it.searchHistory != null }.searchHistory)
        val pending = File(fixture.file.path + ".new").apply { check(mkdir()) }
        val obstruction = File(pending, "obstruction").apply { writeText("synthetic obstruction") }
        model.clearSearchHistory(original)
        val failed = awaitOnlineState(model) {
            !it.busy && it.searchHistoryIssue == OnlineSearchHistoryIssue.CLEAR_FAILED && it.searchHistory?.clearToken != null
        }
        assertFalse(fixture.file.exists())
        assertTrue(failed.searchHistory?.clearToken != null)
        assertTrue(obstruction.delete())
        model.showSearch()
        val reopened = awaitOnlineState(model) {
            !it.busy && it.screen == OnlineScreen.SEARCH && it.searchHistory?.clearToken != null
        }
        assertEquals(OnlineSearchHistoryIssue.CLEAR_FAILED, reopened.searchHistoryIssue)
        model.clearSearchHistory(checkNotNull(reopened.searchHistory))
        val cleared = awaitOnlineState(model) {
            !it.busy && it.searchHistoryIssue == null && it.searchHistory?.queries?.isEmpty() == true
        }
        assertNull(cleared.searchHistory?.clearToken)
        assertFalse(fixture.file.exists())
        assertFalse(pending.exists())
        assertFalse(File(fixture.file.path + ".bak").exists())
        assertTrue(fixture.calls.isEmpty())
    }

    @Test(timeout = 30_000L) fun onlyVisibleHistoryWordsReachTheActualBilingualFontCorpus() = runBlocking {
        fixture.history.record(fixture.sessions.snapshot.value, "Hidden 音 fixture")
        fixture.history.record(fixture.sessions.snapshot.value, "Visible 永 fixture")
        val history = fixture.history.read(fixture.sessions.snapshot.value)
        val state = OnlineUiState(screen = OnlineScreen.SEARCH, searchDraft = "Draft 乐 fixture", searchHistory = history)
        fun text(page: ProbePage, keys: List<Any>) = probePageVisibleText(page, ProbeUiState(), LibraryUiState(),
            QueueViewerState(), PlaylistUiState(), PlaylistEntryUiState(), keys, online = state)
        assertEquals(listOf("Visible 永 fixture"), text(ProbePage.ONLINE, listOf("online:history:0")))
        assertTrue(text(ProbePage.ONLINE, emptyList()).isEmpty())
        assertTrue(text(ProbePage.HOME, listOf("online:history:0")).isEmpty())
        val visible = text(ProbePage.ONLINE, listOf("online:search", "online:history:0"))
        for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(locale) }
            val resources = fixture.context.createConfigurationContext(configuration).resources
            val coverage = themeUiFontCoverage(resources, ThemeUiFontText.capture(visible)) { true }
            assertEquals(coverage.original, coverage.translation)
            assertTrue(coverage.original.contains("Visible 永 fixture"))
            assertTrue(coverage.original.contains("Draft 乐 fixture"))
            assertFalse(coverage.original.contains("Hidden 音 fixture"))
            for (id in intArrayOf(R.string.online_search_clear_input, R.string.online_search_history,
                R.string.online_search_history_empty, R.string.online_search_history_local, R.string.online_search_history_clear,
                R.string.online_search_history_clear_damaged, R.string.online_search_history_retry,
                R.string.online_search_history_cleanup_retry, R.string.online_search_history_cleanup_failed,
                R.string.online_search_history_read_failed, R.string.online_search_history_write_failed,
                R.string.online_search_history_clear_failed, R.string.online_search_history_clear_retry, R.string.online_search_history_corrupt,
                R.string.online_search_history_busy, R.string.online_search_history_stale)) {
                assertTrue(coverage.original.contains(resources.getString(id)))
            }
        }
    }
}
