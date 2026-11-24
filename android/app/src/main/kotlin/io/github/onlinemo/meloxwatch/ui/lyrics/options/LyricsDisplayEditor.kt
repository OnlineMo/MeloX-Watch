package io.github.onlinemo.meloxwatch.ui.lyrics.options

import androidx.annotation.MainThread
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
import kotlinx.coroutines.flow.update

enum class LyricsDisplayNotice { SAVED, CANCELLED, CONFLICT, READ_REQUIRED }

data class LyricsDisplayEditorState(
    val draft: LyricsDisplayPreferences? = null,
    val retry: LyricsDisplayPreferences? = null,
    val working: Boolean = false,
    val issue: LyricsDisplayIssue? = null,
    val notice: LyricsDisplayNotice? = null,
)

/**
 * Preview is memory-only and keeps its original revision. Only an explicit save calls durable CAS.
 * The caller owns the coroutine; admission remains occupied until the store's real write returns.
 */
@MainThread
class LyricsDisplayEditor(private val store: LyricsDisplayStore) : ViewModel() {
    private val occupied = AtomicBoolean(false)
    private val mutable = MutableStateFlow(LyricsDisplayEditorState())
    val state = mutable.asStateFlow()
    val settings = store.state

    fun begin(): Boolean {
        if (occupied.get() || mutable.value.draft != null || mutable.value.retry != null) return false
        val actual = store.state.value as? LyricsDisplayState.Ready
        if (actual == null) {
            mutable.update { it.copy(issue = (store.state.value as? LyricsDisplayState.Unavailable)?.issue
                ?: LyricsDisplayIssue.BUSY, notice = null) }
            return false
        }
        mutable.value = LyricsDisplayEditorState(draft = actual.preferences)
        return true
    }

    fun preview(next: LyricsDisplayPreferences): Boolean {
        val draft = mutable.value.draft ?: return false
        if (occupied.get() || next.revision != draft.revision) return false
        mutable.value = LyricsDisplayEditorState(draft = next)
        return true
    }

    fun previewDefaults(): Boolean {
        val draft = mutable.value.draft ?: return false
        return preview(LyricsDisplayPreferences(revision = draft.revision))
    }

    /** A failed proposal is reviewed against verified storage before it can become a new preview. */
    fun reviewRetry(): Boolean {
        if (occupied.get() || mutable.value.draft != null) return false
        val proposal = mutable.value.retry ?: return false
        val actual = store.state.value as? LyricsDisplayState.Ready
        if (actual == null) {
            mutable.update { it.copy(issue = (store.state.value as? LyricsDisplayState.Unavailable)?.issue
                ?: LyricsDisplayIssue.BUSY, notice = LyricsDisplayNotice.READ_REQUIRED) }
            return false
        }
        when {
            actual.preferences.revision > proposal.revision &&
                actual.preferences == proposal.copy(revision = actual.preferences.revision) ->
                mutable.value = LyricsDisplayEditorState(notice = LyricsDisplayNotice.SAVED)
            actual.preferences.revision != proposal.revision ->
                mutable.value = LyricsDisplayEditorState(notice = LyricsDisplayNotice.CONFLICT)
            else -> mutable.value = LyricsDisplayEditorState(draft = proposal)
        }
        return true
    }

    fun cancel(): Boolean {
        if (occupied.get()) return false
        mutable.value = LyricsDisplayEditorState(notice = LyricsDisplayNotice.CANCELLED)
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
        mutable.update { it.copy(working = true, issue = null, notice = null) }
        try {
            // A changed companion/restore revision is a conflict, never permission to rebase a draft.
            val result = store.save(proposal.revision, proposal)
            currentCoroutineContext().ensureActive()
            when (result) {
                is LyricsDisplayWriteResult.Saved, is LyricsDisplayWriteResult.Unchanged ->
                    mutable.value = LyricsDisplayEditorState(working = true, notice = LyricsDisplayNotice.SAVED)
                is LyricsDisplayWriteResult.Conflict ->
                    mutable.value = LyricsDisplayEditorState(working = true, notice = LyricsDisplayNotice.CONFLICT)
                is LyricsDisplayWriteResult.Cancelled ->
                    mutable.value = LyricsDisplayEditorState(working = true, notice = LyricsDisplayNotice.READ_REQUIRED)
                is LyricsDisplayWriteResult.Failed ->
                    mutable.value = LyricsDisplayEditorState(retry = proposal, working = true, issue = result.issue)
            }
        } catch (cancelled: CancellationException) {
            // The store may have committed before cancellation. Render its verified state on return;
            // retain no action that can be replayed automatically after Activity recreation.
            mutable.value = LyricsDisplayEditorState(working = true, notice = LyricsDisplayNotice.READ_REQUIRED)
            throw cancelled
        } catch (_: IOException) {
            mutable.value = LyricsDisplayEditorState(retry = proposal, working = true, issue = LyricsDisplayIssue.IO)
        } catch (_: RuntimeException) {
            mutable.value = LyricsDisplayEditorState(retry = proposal, working = true, issue = LyricsDisplayIssue.IO)
        } catch (_: OutOfMemoryError) {
            mutable.value = LyricsDisplayEditorState(retry = proposal, working = true, issue = LyricsDisplayIssue.LOW_MEMORY)
        } finally {
            mutable.update { it.copy(working = false) }
            occupied.set(false)
        }
    }
}
