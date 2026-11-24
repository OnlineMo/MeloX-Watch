package io.github.onlinemo.meloxwatch.ui

import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A retained editor has one exact UI caller; retirement never frees an unfinished disk write. */
internal class ProbeLyricsDisplayEvents(private val editor: LyricsDisplayEditor) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var event: Event? = null

    suspend fun save(page: ProbePageRuntime.PageBinding) = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val exact = synchronized(lock) {
            if (closed || !page.visibleNow || event != null) null
            else Event(page, job).also { event = it }
        } ?: return@coroutineScope
        try {
            currentCoroutineContext().ensureActive()
            if (page.visibleNow) editor.save()
        } finally {
            // Editor/Store await the real NonCancellable write, ACK and readback before returning.
            // Cancelling this child must not admit a successor while that work still owns storage.
            synchronized(lock) { if (event === exact) event = null }
        }
    }

    fun retireInvisibleEvent() {
        val retired = synchronized(lock) { event?.takeUnless { it.page.visibleNow }?.job }
        // Cancellation handlers can reenter owner/page code, so cancel outside the event monitor.
        retired?.cancel()
    }

    override fun close() {
        val retired = synchronized(lock) {
            closed = true
            event?.job
        }
        retired?.cancel()
    }

    private class Event(val page: ProbePageRuntime.PageBinding, val job: Job)
}
