package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslation

/** Borrowed immutable reading from the same owner/reservation, never a second parser or cache. */
class LyricsExternalRead internal constructor(
    val identity: Any,
    val queueEntryId: String,
    val sourceGeneration: Long,
    val document: LyricsDocument,
    val translation: LyricsTranslation?,
    val mapping: LyricsClockMapping,
) {
    override fun toString(): String = "<current-external-reading>"
}
