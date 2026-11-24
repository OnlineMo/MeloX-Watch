package io.github.onlinemo.meloxwatch.companion.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.companion.data.about.CompanionLicenseEntry
import io.github.onlinemo.meloxwatch.companion.data.about.CompanionLicenseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CompanionLicensesState(
    val entries: List<CompanionLicenseEntry> = emptyList(),
    val selected: CompanionLicenseEntry? = null,
    val sections: List<String> = emptyList(),
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val cancelled: Boolean = false,
)

/** A conflated intent retires its previous read before admitting another packaged asset. */
internal class CompanionLicensesViewModel(private val repository: CompanionLicenseRepository) : ViewModel() {
    private class Request(val generation: Long, val entry: CompanionLicenseEntry?)
    private val requests = MutableStateFlow<Request?>(null)
    private val mutableState = MutableStateFlow(CompanionLicensesState())
    val state: StateFlow<CompanionLicensesState> = mutableState.asStateFlow()
    private var generation = 0L
    private var visible = false
    private var foreground = false

    init {
        viewModelScope.launch {
            requests.collectLatest { request ->
                if (request == null) return@collectLatest
                try {
                    if (request.entry == null) {
                        val entries = repository.entries()
                        if (current(request)) mutableState.update { it.copy(entries = entries, loading = false) }
                    } else {
                        val text = repository.notice(request.entry)
                        val sections = withContext(Dispatchers.IO) {
                            val context = currentCoroutineContext()
                            companionLicenseSections(text) { context.ensureActive() }
                        }
                        if (current(request)) mutableState.update { it.copy(sections = sections, loading = false) }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (current(request)) mutableState.update { it.copy(loading = false, unavailable = true) }
                } catch (_: OutOfMemoryError) {
                    currentCoroutineContext().ensureActive()
                    if (current(request)) mutableState.update { it.copy(loading = false, unavailable = true) }
                }
            }
        }
    }

    fun show() {
        if (visible) return
        visible = true
        if (foreground) read(null)
    }

    fun hide() {
        visible = false
        clear()
    }

    fun foreground() {
        foreground = true
        if (visible) read(null)
    }

    fun background() {
        foreground = false
        clear()
    }

    fun open(id: String) {
        if (!visible || !foreground) return
        val entry = state.value.entries.firstOrNull { it.id == id } ?: return
        read(entry)
    }

    fun retry() { if (visible && foreground) read(state.value.selected) }

    fun cancel() {
        invalidate()
        mutableState.update { it.copy(sections = emptyList(), loading = false, unavailable = false, cancelled = true) }
    }

    fun backToList(): Boolean {
        if (state.value.selected == null) return false
        invalidate()
        mutableState.update { it.copy(selected = null, sections = emptyList(), loading = false, unavailable = false, cancelled = false) }
        return true
    }

    private fun read(entry: CompanionLicenseEntry?) {
        if (!visible || !foreground) return
        generation = Math.addExact(generation, 1L)
        mutableState.update { it.copy(selected = entry, sections = emptyList(), loading = true, unavailable = false, cancelled = false) }
        requests.value = Request(generation, entry)
    }

    private fun invalidate() {
        generation = Math.addExact(generation, 1L)
        requests.value = null
    }

    private fun clear() {
        invalidate()
        mutableState.value = CompanionLicensesState()
    }

    private fun current(request: Request): Boolean = visible && foreground && generation == request.generation

    override fun onCleared() {
        background()
        super.onCleared()
    }
}

/** Complete text is partitioned for bounded layout, retaining whitespace and surrogate pairs. */
internal fun companionLicenseSections(text: String, cancelled: () -> Unit = {}): List<String> {
    val sections = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        cancelled()
        var end = minOf(start + 2048, text.length)
        if (end < text.length) {
            var boundary = -1
            for (index in end - 1 downTo start + 1024) {
                if (text[index] == '\n') { boundary = index + 1; break }
            }
            if (boundary < 0) for (index in end - 1 downTo start + 1024) {
                if (text[index] == ' ') { boundary = index + 1; break }
            }
            if (boundary >= 0) end = boundary
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        sections.add(text.substring(start, end))
        start = end
    }
    cancelled()
    return sections
}
