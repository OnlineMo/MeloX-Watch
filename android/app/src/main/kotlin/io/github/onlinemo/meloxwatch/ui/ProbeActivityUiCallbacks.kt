package io.github.onlinemo.meloxwatch.ui

import io.github.onlinemo.meloxwatch.ui.fonts.ProbeFontPickerViewModel
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel

/** Main and its tests share the producer callback owner for one exact Activity Runtime. */
internal class ProbeActivityUiCallbacks(
    private val runtime: ProbePageRuntime,
    private val activityBinding: () -> ProbeUiPresentation.Binding?,
    private val lyricsEntry: LyricsEntryViewModel,
    private val fontPicker: ProbeFontPickerViewModel,
    private val display: ProbeLyricsDisplayEvents,
) : AutoCloseable {
    fun onUiStateChanged(actual: ProbeUiState) {
        if (activityBinding()?.isCurrent != true) return
        noteActualRoute(actual)
        try { runtime.onUiStateChanged() }
        finally { display.retireInvisibleEvent() }
    }

    fun onWindowVisibilityChanged() {
        if (activityBinding()?.isCurrent != true) return
        // Window loss retires presentation work but preserves the retained route and small drafts.
        try { runtime.onWindowVisibilityChanged() }
        finally { display.retireInvisibleEvent() }
    }

    fun noteActualRoute(actual: ProbeUiState) {
        // bind delivers its first callback before Main assigns the returned binding into this cell.
        if (activityBinding()?.isCurrent != true) return
        lyricsEntry.setActualEntryRoute(actual.page in ProbeLyricsEntryRoutes)
        if (actual.page == ProbePage.LYRICS_FONTS) fontPicker.enterFontsRoute(ProbePage.LYRICS_FONTS)
        else fontPicker.leaveFontsRoute(ProbePage.LYRICS_FONTS)
    }

    override fun close() {
        // A replaced Activity can only close its own Runtime; it cannot record a route departure.
        try { runtime.close() }
        finally { display.retireInvisibleEvent() }
    }
}
