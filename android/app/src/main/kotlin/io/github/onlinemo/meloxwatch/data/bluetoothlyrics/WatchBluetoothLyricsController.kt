package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.playback.lyrics.BluetoothLyricsSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class BluetoothLyricsSelectionState(
    val page: BluetoothLyricsTargets? = null,
    val working: Boolean = false,
    val issue: BluetoothLyricsIssue? = null,
) {
    override fun toString(): String = "<local-bluetooth-selection>"
}

/** Explicit feature intent is independent of local playback, account and page visibility. */
@MainThread
class WatchBluetoothLyricsController(private val application: MeloXApplication) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = BluetoothLyricsSessionCoordinator(application, BluetoothLyricsRole.WATCH, scope, ::authorized)
    private val mutableSelection = MutableStateFlow(BluetoothLyricsSelectionState())
    val selection = mutableSelection.asStateFlow()
    private var preparing: Job? = null
    private var query: Job? = null
    private var visible = false
    private var presentation: Presentation? = null
    private var open = true
    private val preferenceObserver = scope.launch {
        application.preferences.state.collect { if (!authorized()) sessions.stop() }
    }

    private fun authorized(): Boolean {
        val saved = application.preferences.state.value as? PreferencesState.Ready ?: return false
        return open && saved.canWrite && saved.preferences.bluetoothLyricsEnabled &&
            !application.filePlaybackHost.isReadBlocked()
    }

    fun bindPresentation(): Presentation {
        presentation?.close()
        return Presentation().also { presentation = it }
    }

    inner class Presentation internal constructor() : AutoCloseable {
        private var open = true
        val isCurrent: Boolean get() = open && presentation === this
        fun show() { if (isCurrent) showCurrent() }
        fun hide() { if (isCurrent) hideCurrent() }
        fun approve(comparison: BluetoothLyricsComparison): Boolean =
            isCurrent && visible && sessions.approve(comparison)
        fun reject() { if (isCurrent && visible) sessions.reject() }
        override fun close() {
            if (!open) return
            open = false
            if (presentation === this) {
                presentation = null
                hideCurrent()
            }
        }
    }

    private fun showCurrent() { visible = true; sessions.uiVisible(true) }
    private fun hideCurrent() {
        visible = false
        query?.cancel()
        preparing?.cancel()
        mutableSelection.value = BluetoothLyricsSelectionState()
        sessions.uiVisible(false)
    }

    fun listPaired(offset: Int = 0) {
        if (!open || !visible || query != null || preparing != null) return
        mutableSelection.value = BluetoothLyricsSelectionState(working = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = BluetoothLyricsPlatform.targets(application, offset)
                if (visible && open) mutableSelection.value = BluetoothLyricsSelectionState(page)
            } catch (error: CancellationException) { throw error }
            catch (error: BluetoothLyricsException) {
                if (visible && open) mutableSelection.value = BluetoothLyricsSelectionState(issue = error.issue)
            } catch (_: SecurityException) {
                if (visible && open) mutableSelection.value = BluetoothLyricsSelectionState(issue = BluetoothLyricsIssue.PERMISSION_DENIED)
            } catch (_: Exception) {
                if (visible && open) mutableSelection.value = BluetoothLyricsSelectionState(issue = BluetoothLyricsIssue.UNAVAILABLE)
            }
        }
        query = job
        job.invokeOnCompletion { if (query === job) query = null }
        job.start()
    }

    fun connect(target: BluetoothLyricsTarget): Boolean {
        if (!open || !visible || !authorized() || preparing != null || query != null) return false
        mutableSelection.value = BluetoothLyricsSelectionState(selection.value.page, working = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                application.requireBackupStorageReady()
                application.fileOperations.assertPlaybackReadable()
                if (!visible || !authorized()) return@launch
                val accepted = sessions.connect(target) {
                    if (!authorized()) throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
                    BluetoothLyricsSender(application.lyricsEntryOwner.model, application.lyricsEntryOwner.playback,
                        scope, sessions::offer, onFailure = sessions::stop)
                }
                mutableSelection.value = BluetoothLyricsSelectionState(selection.value.page,
                    issue = if (accepted) null else BluetoothLyricsIssue.BUSY)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (open && visible) mutableSelection.value = BluetoothLyricsSelectionState(
                    selection.value.page, issue = BluetoothLyricsIssue.STORAGE_UNAVAILABLE)
            }
        }
        preparing = job
        job.invokeOnCompletion { if (preparing === job) preparing = null }
        job.start()
        return true
    }

    fun stop() { preparing?.cancel(); sessions.stop() }

    suspend fun quiesce() {
        preparing?.cancelAndJoin()
        query?.cancelAndJoin()
        sessions.awaitStopped()
    }

    override fun close() {
        if (!open) return
        open = false
        presentation?.close()
        stop()
        query?.cancel()
        preferenceObserver.cancel()
        scope.cancel()
    }
}
