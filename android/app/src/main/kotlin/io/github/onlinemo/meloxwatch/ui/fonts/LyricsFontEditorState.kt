package io.github.onlinemo.meloxwatch.ui.fonts

import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings

data class LyricsFontPreviewText(
    val original: String = "MeloX Watch",
    val translation: String = "MeloX Watch",
) {
    fun forRole(role: LyricsFontRole): String = when (role) {
        LyricsFontRole.ORIGINAL -> original
        LyricsFontRole.TRANSLATION -> translation
    }
}

data class LyricsFontDraft(
    val role: LyricsFontRole,
    val baseRevision: Long,
    val selection: LyricsFontSelection,
)

/** A null saved record means storage could not be read, never an asserted default save. */
data class LyricsFontEditorState(
    val saved: LyricsFontSettings? = null,
    val original: LoadedLyricsFont = LoadedLyricsFont(),
    val translation: LoadedLyricsFont = LoadedLyricsFont(),
    val draft: LyricsFontDraft? = null,
    val retry: LyricsFontDraft? = null,
    val working: Boolean = false,
    val issue: LyricsFontIssue? = null,
    val presentationActive: Boolean = false,
) {
    fun font(role: LyricsFontRole): LoadedLyricsFont = when (role) {
        LyricsFontRole.ORIGINAL -> original
        LyricsFontRole.TRANSLATION -> translation
    }

    val canConfirm: Boolean get() = presentationActive && !working && saved != null && draft != null && retry == null &&
        font(draft.role).issue == null

    val canRetrySave: Boolean get() = presentationActive && !working && retry != null
    val canAdjustWeight: Boolean get() = presentationActive && !working && draft != null &&
        font(draft.role).capability.support == FontWeightSupport.AVAILABLE && font(draft.role).typeface != null

    internal fun withFont(role: LyricsFontRole, font: LoadedLyricsFont): LyricsFontEditorState = when (role) {
        LyricsFontRole.ORIGINAL -> copy(original = font)
        LyricsFontRole.TRANSLATION -> copy(translation = font)
    }

    internal fun retainedIds(): Set<String> = buildSet {
        saved?.assetIds()?.let(::addAll)
        draft?.selection?.asset?.id?.let(::add)
        retry?.selection?.asset?.id?.let(::add)
    }
}
