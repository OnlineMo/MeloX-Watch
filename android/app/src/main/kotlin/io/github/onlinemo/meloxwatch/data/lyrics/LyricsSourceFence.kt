package io.github.onlinemo.meloxwatch.data.lyrics

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.TrackKey

/** The actual source supplies this fence; local file grants are never invented for online text. */
interface LyricsSourceFence {
    val trackKey: TrackKey
    val sourceGeneration: Long
    fun matchesSeekBinding(binding: QueueSeekBinding): Boolean
}
