package io.github.onlinemo.meloxwatch.ui.lyrics.options

import androidx.lifecycle.SavedStateHandle
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayAlignment
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences

/** Only bounded display values survive recreation; a write, page ticket or media text never does. */
internal class LyricsDisplaySavedDrafts(private val saved: SavedStateHandle) {
    fun restore(): LyricsDisplayEditorState {
        if (!saved.contains(KEY_PROPOSAL)) return LyricsDisplayEditorState()
        val fields = saved.get<Any?>(KEY_PROPOSAL) as? List<*>
        if (fields == null || fields.size != FIELD_COUNT || fields[0] as? Int != FORMAT_VERSION) return rejected()
        val revision = fields[1] as? Long ?: return rejected()
        val textSize = fields[2] as? Float ?: return rejected()
        val blockSpacing = fields[3] as? Float ?: return rejected()
        val alignment = when (fields[4] as? Int) {
            0 -> LyricsDisplayAlignment.START
            1 -> LyricsDisplayAlignment.CENTER
            else -> return rejected()
        }
        val anchor = fields[5] as? Float ?: return rejected()
        val margin = fields[6] as? Float ?: return rejected()
        val translation = fields[7] as? Boolean ?: return rejected()
        val words = fields[8] as? Boolean ?: return rejected()
        val legacyKeepScreenOn = fields[9] as? Boolean ?: return rejected()
        val proposal = try {
            LyricsDisplayPreferences(revision, textSize, blockSpacing, alignment, anchor, margin,
                translation, words, legacyKeepScreenOn)
        } catch (_: IllegalArgumentException) { return rejected() }
        // A fresh owner cannot tell whether an interrupted save committed. Existing review logic
        // checks verified storage before this proposal may preview or be explicitly saved again.
        return LyricsDisplayEditorState(retry = proposal)
    }

    fun record(proposal: LyricsDisplayPreferences?) {
        if (proposal == null) {
            saved.remove<Any?>(KEY_PROPOSAL)
            return
        }
        saved[KEY_PROPOSAL] = arrayListOf<Any>(FORMAT_VERSION, proposal.revision, proposal.textSizeSp,
            proposal.blockSpacingDp, when (proposal.alignment) {
                LyricsDisplayAlignment.START -> 0
                LyricsDisplayAlignment.CENTER -> 1
            }, proposal.anchorFraction,
            proposal.horizontalMarginDp, proposal.showTranslation, proposal.emphasizeWords,
            proposal.legacyKeepScreenOn)
    }

    private fun rejected(): LyricsDisplayEditorState {
        saved.remove<Any?>(KEY_PROPOSAL)
        return LyricsDisplayEditorState(issue = LyricsDisplayIssue.INVALID_FIELDS,
            notice = LyricsDisplayNotice.READ_REQUIRED)
    }

    companion object {
        internal const val KEY_PROPOSAL = "lyrics_display:proposal_v1"
        private const val FORMAT_VERSION = 1
        private const val FIELD_COUNT = 10
    }
}
