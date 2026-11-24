package io.github.onlinemo.meloxwatch.ui

import androidx.annotation.MainThread

/**
 * One Activity observes navigation and media validity before the producing VM call returns.
 * Composition may be delayed or coalesce a hidden interval. Each callback therefore owns only
 * that Activity's resource bindings, and replacement retires the old Activity first.
 */
@MainThread
internal class ProbeUiPresentation {
    private var current: Binding? = null

    fun bind(state: ProbeUiState, update: (ProbeUiState) -> Unit, retire: () -> Unit): Binding {
        current?.close()
        return Binding(update, retire).also {
            current = it
            it.update(state)
        }
    }

    fun update(state: ProbeUiState) {
        current?.update(state)
    }

    fun close() {
        current?.close()
    }

    /** Closing an old Activity is idempotent and cannot clear its successor. */
    inner class Binding internal constructor(
        private val onUpdate: (ProbeUiState) -> Unit,
        private val retire: () -> Unit,
    ) : AutoCloseable {
        private var open = true
        val isCurrent: Boolean get() = open && current === this

        internal fun update(state: ProbeUiState) {
            if (isCurrent) onUpdate(state)
        }

        override fun close() {
            if (!open) return
            open = false
            if (current === this) current = null
            retire()
        }
    }
}
