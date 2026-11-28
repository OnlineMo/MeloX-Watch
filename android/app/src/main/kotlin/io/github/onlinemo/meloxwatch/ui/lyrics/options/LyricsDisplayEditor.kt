package io.github.onlinemo.meloxwatch.ui.lyrics.options

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayState
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayWriteResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LyricsDisplayNotice { SAVED, CANCELLED, CONFLICT, READ_REQUIRED }

data class LyricsDisplayEditorState(
    val draft: LyricsDisplayPreferences? = null,
    val retry: LyricsDisplayPreferences? = null,
    val working: Boolean = false,
    val issue: LyricsDisplayIssue? = null,
    val notice: LyricsDisplayNotice? = null,
)

/**
 * A preview keeps its original revision; only an explicit save calls durable CAS. SavedState retains
 * the bounded proposal for review after process recreation, never a pending write or page event.
 * The caller owns the coroutine; admission remains occupied until the store's real write returns.
 */
@MainThread
class LyricsDisplayEditor private constructor(
    private val store: LyricsDisplayStore,
    private val drafts: LyricsDisplaySavedDrafts?,
) : ViewModel() {
    constructor(store: LyricsDisplayStore) : this(store, null)
    internal constructor(store: LyricsDisplayStore, saved: SavedStateHandle) : this(store, LyricsDisplaySavedDrafts(saved))

    private val occupied = AtomicBoolean(false)
    private val mutable = MutableStateFlow(drafts?.restore() ?: LyricsDisplayEditorState())
    val state = mutable.asStateFlow()
    val settings = store.state

    fun begin(): Boolean {
        if (occupied.get() || mutable.value.draft != null || mutable.value.retry != null) return false
        val actual = store.state.value as? LyricsDisplayState.Ready
        if (actual == null) {
            publish(mutable.value.copy(issue = (store.state.value as? LyricsDisplayState.Unavailable)?.issue
                ?: LyricsDisplayIssue.BUSY, notice = null))
            return false
        }
        publish(LyricsDisplayEditorState(draft = actual.preferences))
        return true
    }

    fun preview(next: LyricsDisplayPreferences): Boolean {
        val draft = mutable.value.draft ?: return false
        if (occupied.get() || next.revision != draft.revision) return false
        publish(LyricsDisplayEditorState(draft = next))
        return true
    }

    fun previewDefaults(): Boolean {
        val draft = mutable.value.draft ?: return false
        return preview(LyricsDisplayPreferences(revision = draft.revision))
    }

    /** A retained proposal is reviewed against verified storage before it can become a new preview. */
    fun reviewRetry(): Boolean {
        if (occupied.get() || mutable.value.draft != null) return false
        val proposal = mutable.value.retry ?: return false
        val actual = store.state.value as? LyricsDisplayState.Ready
        if (actual == null) {
            publish(mutable.value.copy(issue = (store.state.value as? LyricsDisplayState.Unavailable)?.issue
                ?: LyricsDisplayIssue.BUSY, notice = LyricsDisplayNotice.READ_REQUIRED))
            return false
        }
        when {
            actual.preferences.revision > proposal.revision &&
                actual.preferences == proposal.copy(revision = actual.preferences.revision) ->
                publish(LyricsDisplayEditorState(notice = LyricsDisplayNotice.SAVED))
            actual.preferences.revision != proposal.revision ->
                publish(LyricsDisplayEditorState(notice = LyricsDisplayNotice.CONFLICT))
            else -> publish(LyricsDisplayEditorState(draft = proposal))
        }
        return true
    }

    fun cancel(): Boolean {
        if (occupied.get()) return false
        publish(LyricsDisplayEditorState(notice = LyricsDisplayNotice.CANCELLED))
        return true
    }

    fun retryRead() = store.retryRead()

    suspend fun save() {
        if (!occupied.compareAndSet(false, true)) return
        val proposal = mutable.value.draft
        if (proposal == null) {
            occupied.set(false)
            return
        }
        try {
            publish(mutable.value.copy(working = true, issue = null, notice = null))
            // A changed companion/restore revision is a conflict, never permission to rebase a draft.
            val result = store.save(proposal.revision, proposal)
            currentCoroutineContext().ensureActive()
            when (result) {
                is LyricsDisplayWriteResult.Saved, is LyricsDisplayWriteResult.Unchanged ->
                    publish(LyricsDisplayEditorState(working = true, notice = LyricsDisplayNotice.SAVED))
                is LyricsDisplayWriteResult.Conflict ->
                    publish(LyricsDisplayEditorState(working = true, notice = LyricsDisplayNotice.CONFLICT))
                is LyricsDisplayWriteResult.Cancelled ->
                    publish(LyricsDisplayEditorState(retry = proposal, working = true,
                        notice = LyricsDisplayNotice.READ_REQUIRED))
                is LyricsDisplayWriteResult.Failed ->
                    publish(LyricsDisplayEditorState(retry = proposal, working = true, issue = result.issue))
            }
        } catch (cancelled: CancellationException) {
            // Cancellation can precede or follow a commit. Retain only the proposal for explicit
            // review against verified storage; no pending write or command survives recreation.
            publish(LyricsDisplayEditorState(retry = proposal, working = true,
                notice = LyricsDisplayNotice.READ_REQUIRED))
            throw cancelled
        } catch (_: IOException) {
            publish(LyricsDisplayEditorState(retry = proposal, working = true, issue = LyricsDisplayIssue.IO))
        } catch (_: RuntimeException) {
            publish(LyricsDisplayEditorState(retry = proposal, working = true, issue = LyricsDisplayIssue.IO))
        } catch (_: OutOfMemoryError) {
            publish(LyricsDisplayEditorState(retry = proposal, working = true, issue = LyricsDisplayIssue.LOW_MEMORY))
        } finally {
            try { publish(mutable.value.copy(working = false)) }
            finally { occupied.set(false) }
        }
    }

    private fun publish(next: LyricsDisplayEditorState) {
        drafts?.record(next.draft ?: next.retry)
        mutable.value = next
    }
}
