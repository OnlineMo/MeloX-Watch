package io.github.onlinemo.meloxwatch.ui.library

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryFolder
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibraryOverview
import io.github.onlinemo.meloxwatch.data.library.LibraryPage
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real indexed Room results; gates control late delivery, not matching, paging or lease creation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, application = MeloXApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryViewModelSearchTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var app: MeloXApplication
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var controlled: ControlledLibrary
    private val models = mutableListOf<LibraryViewModel>()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun prepareLibrary() = runBlocking {
        app = RuntimeEnvironment.getApplication() as MeloXApplication
        awaitCondition { app.backups.startup.value !is BackupStartupState.Loading }
        assertTrue(app.backups.startup.value is BackupStartupState.Ready)
        database = Room.databaseBuilder(app, MeloXDatabase::class.java, File(temporaryFolder.root, "search.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2).build()
        library = RoomLocalLibraryRepository(database, { 1_000L }, { 1_790_000_000_000L })
        library.initialize()
        controlled = ControlledLibrary(library)
    }

    @After fun releaseLibrary() = runBlocking {
        try {
            if (::controlled.isInitialized) controlled.releaseAll()
            models.forEach { it.onHidden() }
            stores.forEach { it.clear() }
            if (::controlled.isInitialized) awaitCondition { controlled.activeCount == 0 }
        } finally { if (::database.isInitialized) database.close() }
    }

    @Test(timeout = 30_000L)
    fun editingRetiresAnIgnoringNextPageBeforeASubmittedReplacement() = runBlocking {
        index((0..100).map { Fixture("alpha-$it.mp3", "Alpha $it") } + Fixture("beta.mp3", "Beta"))
        val model = visibleModel()
        search(model, "alpha")
        awaitState(model) { !it.loading && it.page?.view?.query?.search == "alpha" }
        val gate = controlled.blockPage("alpha", afterOnly = true)
        model.nextPage()
        awaitCondition { gate.entered.isCompleted }

        model.updateSearch("beta")
        assertTrue(checkNotNull(gate.owner).isCancelled)
        assertNull(model.uiState.value.page)
        assertFalse(model.uiState.value.canPlay)
        assertFalse(model.uiState.value.canReload)
        assertTrue(model.uiState.value.searchIdle)
        model.submitSearch()
        val latest = awaitState(model) { !it.loading && it.page?.view?.query?.search == "beta" }
        assertEquals(listOf("Beta"), latest.page?.tracks?.map { it.title })

        gate.release.complete(Unit)
        awaitCondition { gate.finished.isCompleted }
        assertEquals(latest.page?.view, model.uiState.value.page?.view)
        assertEquals(listOf("Beta"), model.uiState.value.page?.tracks?.map { it.title })
        assertNull(model.uiState.value.issue)
    }

    @Test(timeout = 30_000L)
    fun aCancelledInitialPageCannotPublishItsLateError() = runBlocking {
        index(listOf(Fixture("alpha.mp3", "Alpha"), Fixture("beta.mp3", "Beta")))
        val model = visibleModel()
        val gate = controlled.blockPage("alpha", failure = LibraryIssue.STORAGE_UNAVAILABLE)
        search(model, "alpha")
        awaitCondition { gate.entered.isCompleted }
        model.updateSearch("beta")
        assertTrue(checkNotNull(gate.owner).isCancelled)
        model.submitSearch()
        gate.release.complete(Unit)
        awaitCondition { gate.finished.isCompleted }
        val latest = awaitState(model) { !it.loading && it.page?.view?.query?.search == "beta" }
        assertEquals(listOf("Beta"), latest.page?.tracks?.map { it.title })
        assertNull(latest.issue)
        assertTrue(latest.canPlay)
    }

    @Test(timeout = 30_000L)
    fun aCancelledMiddleWaiterCannotOpenBeforeTheOldLeaseCloses() = runBlocking {
        index(listOf(Fixture("alpha.mp3", "Alpha"), Fixture("beta.mp3", "Beta"), Fixture("gamma.mp3", "Gamma")))
        val model = visibleModel()
        search(model, "alpha")
        awaitState(model) { !it.loading && it.page?.view?.query?.search == "alpha" }
        val gate = controlled.blockClose("alpha")
        model.updateSearch("beta")
        awaitCondition { gate.entered.isCompleted }
        model.submitSearch()
        model.updateSearch("gamma")
        model.submitSearch()
        var playbackCommits = 0
        model.preparePlay(null) { playbackCommits += 1 }

        assertEquals(0, playbackCommits)
        assertEquals(1, controlled.activeCount)
        assertFalse("beta" in controlled.openedSearches)
        assertFalse("gamma" in controlled.openedSearches)
        gate.release.complete(Unit)
        val latest = awaitState(model) { !it.loading && it.page?.view?.query?.search == "gamma" }
        assertEquals(listOf("Gamma"), latest.page?.tracks?.map { it.title })
        assertFalse("beta" in controlled.openedSearches)
        assertEquals(1, controlled.maximumActiveCount)
    }

    @Test(timeout = 30_000L)
    fun searchMatchesEveryIndexedFieldAndBoundsEmptyAndOversizedInput() = runBlocking {
        index(listOf(Fixture("one.mp3", "Title Needle"), Fixture("two.mp3", "Second", artist = "Artist Needle"),
            Fixture("three.mp3", "Third", album = "Album Needle"), Fixture("File Needle.mp3", "Fourth"),
            Fixture("100%_\\ ' 音.mp3", "Symbols")))
        val model = visibleModel()
        for ((term, title) in listOf("title needle" to "Title Needle", "artist needle" to "Second",
            "album needle" to "Third", "file needle" to "Fourth", "%_\\ ' 音" to "Symbols")) {
            search(model, term)
            val state = awaitState(model) { !it.loading && it.page?.view?.query?.search == term }
            assertEquals(listOf(title), state.page?.tracks?.map { it.title })
            assertNull(state.issue)
        }
        search(model, "absent needle")
        val empty = awaitState(model) { !it.loading && it.page?.view?.query?.search == "absent needle" }
        assertEquals(0L, empty.page?.view?.totalCount)
        assertFalse(empty.searchIdle)
        assertNull(empty.issue)

        val before = controlled.openedSearches.size
        model.updateSearch("x".repeat(4097))
        assertEquals(4096, model.uiState.value.searchDraft.length)
        assertFalse(model.uiState.value.searchInputValid)
        assertNull(model.uiState.value.page)
        assertEquals(LibraryIssue.INVALID_INPUT, model.uiState.value.issue)
        model.submitSearch()
        model.reload()
        assertEquals(before, controlled.openedSearches.size)
        model.updateSearch("x".repeat(4096))
        assertTrue(model.uiState.value.searchInputValid)
        model.submitSearch()
        awaitState(model) { !it.loading && it.page?.view?.query?.search?.length == 4096 }
        model.clearSearch()
        model.submitSearch()
        assertTrue(model.uiState.value.searchIdle)
        assertEquals("", model.uiState.value.searchDraft)
        assertEquals("", model.uiState.value.query.search)
        assertNull(model.uiState.value.page)
        assertNull(model.uiState.value.issue)
    }

    @Test(timeout = 30_000L)
    fun backFromSearchDetailReturnsThroughResultsToTheOriginalFolder() = runBlocking {
        index(listOf(Fixture("target.mp3", "Target"), Fixture("other.mp3", "Other")))
        val folder = rootFolder()
        val model = visibleModel()
        model.openFolder(folder)
        val origin = awaitState(model) { !it.loading && it.context == LibraryListContext.FOLDER && it.folder != null }
        val anchor = origin.page?.tracks?.firstOrNull()?.trackKey
        search(model, "target")
        val results = awaitState(model) { !it.loading && it.page?.view?.query?.search == "target" }
        model.openDetail(checkNotNull(results.page).tracks.single())
        assertEquals(LibraryScreen.DETAIL, model.uiState.value.screen)
        assertTrue(model.back())
        assertEquals(LibraryScreen.SEARCH, model.uiState.value.screen)
        assertEquals("target", model.uiState.value.searchDraft)
        assertTrue(model.back())
        val returned = awaitState(model) { !it.loading && it.context == LibraryListContext.FOLDER && it.page != null }
        assertEquals(origin.query, returned.query)
        assertEquals(folder.nodeId, returned.folder?.nodeId)
        assertEquals(anchor, returned.page?.tracks?.firstOrNull()?.trackKey)
        assertEquals("", returned.searchDraft)
        assertFalse(returned.searchSubmitted)
    }

    @Test(timeout = 30_000L)
    fun visibilityKeepsAnUnsubmittedDraftButANewModelRestoresOnlyTheBrowseSource() = runBlocking {
        index(listOf(Fixture("one.mp3", "One")))
        val saved = SavedStateHandle()
        val first = visibleModel(saved)
        first.show(LibraryScreen.RECENT)
        awaitState(first) { !it.loading && it.context == LibraryListContext.RECENT && it.page != null }
        first.show(LibraryScreen.SEARCH)
        first.updateSearch("unsubmitted term")
        val before = controlled.openedSearches.size
        first.reload()
        val subscriptions = controlled.overviewSubscriptionCount
        first.onHidden()
        first.onVisible()
        awaitCondition { controlled.overviewSubscriptionCount > subscriptions }
        assertEquals("unsubmitted term", first.uiState.value.searchDraft)
        assertTrue(first.uiState.value.searchIdle)
        assertFalse(first.uiState.value.loading)
        assertEquals(before, controlled.openedSearches.size)
        assertFalse("library.search" in saved.keys())
        assertEquals(LibraryScreen.RECENT.name, saved.get<String>("library.screen"))

        first.onHidden()
        awaitCondition { controlled.activeCount == 0 }
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val next = visibleModel(restored)
        assertEquals(LibraryScreen.RECENT, next.uiState.value.screen)
        assertEquals("", next.uiState.value.searchDraft)
        assertEquals("", next.uiState.value.query.search)
        val legacy = SavedStateHandle(mapOf("library.screen" to LibraryScreen.SEARCH.name,
            "library.context" to LibraryListContext.SEARCH.name, "library.search" to "retired term"))
        val sanitized = model(legacy)
        assertEquals(LibraryScreen.ALL, sanitized.uiState.value.screen)
        assertEquals("", sanitized.uiState.value.searchDraft)
        assertFalse("library.search" in legacy.keys())
        assertNotNull(LibraryViewModel::class.java.getConstructor(Application::class.java, SavedStateHandle::class.java))
    }

    private fun model(saved: SavedStateHandle = SavedStateHandle()): LibraryViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == LibraryViewModel::class.java)
                return LibraryViewModel(app, saved, controlled) as T
            }
        }
        return ViewModelProvider(store, factory)[LibraryViewModel::class.java].also { models += it }
    }

    private suspend fun visibleModel(saved: SavedStateHandle = SavedStateHandle()): LibraryViewModel = model(saved).also {
        it.onVisible()
        awaitState(it) { state -> !state.loading && state.page != null }
    }

    private fun search(model: LibraryViewModel, term: String) {
        model.show(LibraryScreen.SEARCH)
        model.updateSearch(term)
        model.submitSearch()
    }

    private suspend fun awaitState(model: LibraryViewModel, predicate: (LibraryUiState) -> Boolean): LibraryUiState =
        withTimeout(10_000L) {
            while (!predicate(model.uiState.value)) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(1L)
            }
            model.uiState.value
        }

    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(10_000L) {
        while (!predicate()) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(1L) }
    }

    private data class Fixture(val filename: String, val title: String, val artist: String? = null, val album: String? = null)

    private suspend fun index(fixtures: List<Fixture>) {
        val source = library.registerSource(LocalSourceSpec("search-source", LocalSourceKind.TREE,
            "content://search-fixture.invalid/tree/root", authority = "search-fixture.invalid", rootObjectId = "root",
            providerIncarnation = "fixture-instance"), LocalGrantObservation(read = ObservedCapability.AVAILABLE,
            enumerate = ObservedCapability.AVAILABLE))
        val token = library.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.FULL)
        library.commitSeen(token, listOf(ObservedCandidate(source.spec.sourceId, "root", source.spec.uri, null,
            "Fixture folder", "vnd.android.document/directory", LocalNodeKind.DIRECTORY)))
        for (batch in fixtures.withIndex().toList().chunked(LibraryLimits.PAGE_SIZE)) {
            library.commitSeen(token, batch.map { (index, fixture) -> ObservedCandidate(source.spec.sourceId, "audio-$index",
                "content://search-fixture.invalid/document/audio-$index", "root", fixture.filename, "audio/mpeg",
                LocalNodeKind.AUDIO, sizeBytes = 128L, providerContentToken = "native:audio-$index:1") })
            library.commitMetadata(token, batch.map { (index, fixture) -> MetadataOutcome("audio-$index",
                LocalAudioMetadata(title = fixture.title, artist = fixture.artist, album = fixture.album, durationMs = 120_000L)) })
        }
        while (true) {
            val directory = library.nextDirectory(token) ?: break
            library.completeDirectory(token, directory.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        var afterAlias: String? = null
        while (true) {
            val staged = library.stageAbsence(token, afterAlias)
            if (staged.complete) break
            afterAlias = staged.nextAliasId
        }
        library.finishScan(token, ScanCounters(), coverage)
    }

    private suspend fun rootFolder(): LibraryFolder {
        val view = library.openView()
        try {
            val node = checkNotNull(database.libraryDao().node("search-source", "root"))
            return checkNotNull(library.folder(view, node.nodeId))
        } finally { library.closeView(view.viewId) }
    }

    private class Gate(val search: String, val afterOnly: Boolean = false, val failure: LibraryIssue? = null) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var owner: Job? = null
    }

    private class ControlledLibrary(private val actual: LocalLibraryRepository) : LocalLibraryRepository by actual {
        private val lock = Any()
        private val live = linkedMapOf<String, LibraryViewToken>()
        private val opened = mutableListOf<String>()
        private val gates = mutableListOf<Gate>()
        private var pendingPage: Gate? = null
        private var pendingClose: Gate? = null
        private var subscriptions = 0
        private var maximum = 0
        val activeCount: Int get() = synchronized(lock) { live.size }
        val maximumActiveCount: Int get() = synchronized(lock) { maximum }
        val openedSearches: List<String> get() = synchronized(lock) { opened.toList() }
        val overviewSubscriptionCount: Int get() = synchronized(lock) { subscriptions }

        override fun observeOverview(): Flow<LibraryOverview> = actual.observeOverview().onStart {
            synchronized(lock) { subscriptions += 1 }
        }

        override suspend fun openView(query: LibraryQuery, sort: LibrarySort, owner: LibraryViewOwner): LibraryViewToken =
            actual.openView(query, sort, owner).also { view -> synchronized(lock) {
                live[view.viewId] = view
                opened += query.search
                maximum = maxOf(maximum, live.size)
            } }

        override suspend fun page(view: LibraryViewToken, after: LibraryCursor?, limit: Int): LibraryPage {
            val gate = synchronized(lock) { pendingPage?.takeIf {
                it.search == view.query.search && (!it.afterOnly || after != null)
            }?.also { pendingPage = null } }
            val page = actual.page(view, after, limit)
            if (gate != null) {
                val owner = checkNotNull(currentCoroutineContext()[Job])
                gate.owner = owner
                owner.invokeOnCompletion { gate.finished.complete(Unit) }
                // This artificial result delivery ignores cancellation after the actual query exits.
                withContext(NonCancellable) {
                    gate.entered.complete(Unit)
                    gate.release.await()
                    gate.failure?.let { throw LibraryException(it) }
                }
            }
            return page
        }

        override suspend fun closeView(viewId: String) {
            val gate = synchronized(lock) { pendingClose?.takeIf {
                it.search == live[viewId]?.query?.search
            }?.also { pendingClose = null } }
            if (gate != null) withContext(NonCancellable) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            try { actual.closeView(viewId) }
            finally { synchronized(lock) { live.remove(viewId) } }
        }

        fun blockPage(search: String, afterOnly: Boolean = false, failure: LibraryIssue? = null): Gate = synchronized(lock) {
            Gate(search, afterOnly, failure).also { pendingPage = it; gates += it }
        }
        fun blockClose(search: String): Gate = synchronized(lock) {
            Gate(search).also { pendingClose = it; gates += it }
        }
        fun releaseAll() = synchronized(lock) { gates.forEach { it.release.complete(Unit) } }
    }
}
