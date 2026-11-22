package io.github.onlinemo.meloxwatch.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.listening.ListeningDayPage
import io.github.onlinemo.meloxwatch.data.listening.ListeningException
import io.github.onlinemo.meloxwatch.data.listening.ListeningHistoryPage
import io.github.onlinemo.meloxwatch.data.listening.ListeningIssue
import io.github.onlinemo.meloxwatch.data.listening.ListeningSummary
import io.github.onlinemo.meloxwatch.data.listening.ListeningTodaySummary
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackPage
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ListeningClear { HISTORY, STATISTICS }

data class ListeningUiState(
    val summary: ListeningSummary? = null,
    val today: ListeningTodaySummary? = null,
    val history: ListeningHistoryPage? = null,
    val tracks: ListeningTrackPage? = null,
    val days: ListeningDayPage? = null,
    val loading: Boolean = false,
    val issue: ListeningIssue? = null,
    val previousData: Boolean = false,
    val clear: ListeningClear? = null,
    val expectedClearEpoch: Long? = null,
)

/** Clear confirmations bind independent epochs; late playback writes cannot resurrect cleared data. */
class ListeningViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MeloXApplication).listening
    private val mutableState = MutableStateFlow(ListeningUiState())
    val uiState = mutableState.asStateFlow()
    private var visibleScreen: LibraryScreen? = null
    private var observer: Job? = null
    private var query: Job? = null
    private var todayQuery: Job? = null
    private var dateObserver: Job? = null
    private var todayRequested = false
    private var generation = 0L
    private var summaryObservationRevision = 0L

    fun onVisible(screen: LibraryScreen) {
        if (visibleScreen == screen) return
        onHidden()
        visibleScreen = screen
        val token = generation
        observer = viewModelScope.launch {
            try { repository.observeSummary().collect { summary ->
                if (token != generation || visibleScreen != screen) return@collect
                if (isOlderSummary(summary, mutableState.value.summary)) return@collect
                summaryObservationRevision += 1L
                mutableState.update { state ->
                    mergeSummary(state, summary)
                }
                refreshToday()
            } } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (token == generation) failure(error) }
        }
        if (screen == LibraryScreen.STATISTICS) {
            dateObserver = viewModelScope.launch {
                while (isActive) {
                    delay(60_000L)
                    val zone = ZoneId.systemDefault()
                    val date = LocalDate.now(zone).toString()
                    val shown = mutableState.value.today
                    if (shown == null || shown.localDate != date || shown.currentZoneId != zone.id) refreshToday()
                }
            }
        }
        reload()
    }

    fun onHidden() {
        generation += 1L
        visibleScreen = null
        observer?.cancel(); observer = null
        query?.cancel(); query = null
        todayQuery?.cancel(); todayQuery = null
        dateObserver?.cancel(); dateObserver = null
        todayRequested = false
        mutableState.update { it.copy(loading = false, clear = null, expectedClearEpoch = null) }
    }

    fun reload() = read { token, screen ->
        val observedRevision = summaryObservationRevision
        val summary = repository.summary()
        if (screen == LibraryScreen.HISTORY) {
            val history = repository.historyPage()
            publishRead(token, observedRevision, summary) { it.copy(history = history) }
        } else {
            val tracks = repository.trackPage()
            val days = repository.dayPage()
            publishRead(token, observedRevision, summary) { it.copy(tracks = tracks, days = days) }
            if (token == generation) refreshToday()
        }
    }

    fun nextHistory() {
        val cursor = mutableState.value.history?.nextCursor ?: return
        read { token, _ -> val page = repository.historyPage(cursor); publish(token) { it.copy(history = page) } }
    }

    fun nextTracks() {
        val cursor = mutableState.value.tracks?.nextCursor ?: return
        read { token, _ -> val page = repository.trackPage(cursor); publish(token) { it.copy(tracks = page) } }
    }

    fun nextDays() {
        val cursor = mutableState.value.days?.nextCursor ?: return
        read { token, _ -> val page = repository.dayPage(cursor); publish(token) { it.copy(days = page) } }
    }

    fun askClear(kind: ListeningClear) {
        val epochs = mutableState.value.summary?.epochs ?: return
        val expected = if (kind == ListeningClear.HISTORY) epochs.historyEpoch else epochs.statisticsEpoch
        mutableState.update { it.copy(clear = kind, expectedClearEpoch = expected) }
    }

    fun cancelClear() { mutableState.update { it.copy(clear = null, expectedClearEpoch = null) } }

    fun confirmClear() {
        val state = mutableState.value
        val kind = state.clear ?: return
        val epoch = state.expectedClearEpoch ?: return
        read { token, _ ->
            if (kind == ListeningClear.HISTORY) repository.clearHistory(epoch) else repository.clearStatistics(epoch)
            val observedRevision = summaryObservationRevision
            val summary = repository.summary()
            val history = repository.historyPage()
            val tracks = repository.trackPage()
            val days = repository.dayPage()
            publishRead(token, observedRevision, summary) { it.copy(history = history, tracks = tracks, days = days,
                clear = null, expectedClearEpoch = null) }
            if (token == generation) refreshToday()
        }
    }

    /** One coalesced read follows saved progress; the minute tick only detects a changed date or zone. */
    private fun refreshToday() {
        if (visibleScreen != LibraryScreen.STATISTICS) return
        todayRequested = true
        if (todayQuery?.isActive == true) return
        val token = generation
        todayQuery = viewModelScope.launch {
            while (todayRequested && token == generation && visibleScreen == LibraryScreen.STATISTICS) {
                todayRequested = false
                try {
                    val zone = ZoneId.systemDefault()
                    val result = repository.today(LocalDate.now(zone).toString(), zone.id)
                    val currentZone = ZoneId.systemDefault()
                    val currentDate = LocalDate.now(currentZone).toString()
                    if (token != generation) return@launch
                    if (result.localDate != currentDate || result.currentZoneId != currentZone.id) {
                        todayRequested = true
                    } else if (result.statisticsEpoch == mutableState.value.summary?.epochs?.statisticsEpoch) {
                        publish(token) { it.copy(today = result) }
                    }
                    // A changed epoch is refreshed by its summary emission, never by a busy retry loop.
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (token == generation) failure(error) }
            }
        }
    }

    private fun read(work: suspend (Long, LibraryScreen) -> Unit) {
        val screen = visibleScreen ?: return
        if (query?.isActive == true) return
        mutableState.update { it.copy(loading = true, issue = null) }
        val token = generation
        query = viewModelScope.launch {
            try { work(token, screen) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (token == generation) failure(error)
            } finally { if (token == generation) mutableState.update { it.copy(loading = false) } }
        }
    }

    private fun publish(token: Long, update: (ListeningUiState) -> ListeningUiState) {
        if (token == generation && visibleScreen != null) mutableState.update(update)
    }

    private fun publishRead(token: Long, observedRevision: Long, summary: ListeningSummary,
        update: (ListeningUiState) -> ListeningUiState,
    ) = publish(token) { current ->
        val stale = observedRevision != summaryObservationRevision || isOlderSummary(summary, current.summary)
        mergeSummary(update(current), summary).copy(previousData = stale)
    }

    /** Epochs and cumulative counts only advance; a delayed read cannot undo a clear or newer progress. */
    private fun isOlderSummary(incoming: ListeningSummary, current: ListeningSummary?): Boolean {
        current ?: return false
        return incoming.epochs.historyEpoch < current.epochs.historyEpoch ||
            incoming.epochs.statisticsEpoch < current.epochs.statisticsEpoch ||
            (incoming.epochs.statisticsEpoch == current.epochs.statisticsEpoch &&
                (incoming.totalListeningMs < current.totalListeningMs || incoming.totalPlayCount < current.totalPlayCount)) ||
            (incoming.epochs.historyEpoch == current.epochs.historyEpoch && incoming.historyCount < current.historyCount)
    }

    private fun mergeSummary(state: ListeningUiState, incoming: ListeningSummary): ListeningUiState {
        if (isOlderSummary(incoming, state.summary)) return state
        val old = state.summary?.epochs
        return state.copy(summary = incoming,
            today = if (old != null && old.statisticsEpoch != incoming.epochs.statisticsEpoch) null else state.today,
            previousData = state.previousData || (old != null && old != incoming.epochs))
    }

    private fun failure(error: Exception) {
        mutableState.update { it.copy(issue = (error as? ListeningException)?.issue ?: ListeningIssue.STORAGE_UNAVAILABLE,
            previousData = it.history != null || it.tracks != null || it.days != null || it.today != null) }
    }
}
